package com.catacomb5099.naviseerr.curator;

import com.catacomb5099.naviseerr.settings.InstallSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Once a week (cron {@code curator.cron}) asks the playlist curator for fresh suggested playlists and
 * follows the run until it finishes. The curator answers the trigger instantly and does the slow
 * work (minutes) in the background, so this polls {@code GET /v1/runs/{id}} every
 * {@code poll-interval-ms} for at most {@code run-budget-ms}. A poll that fails is logged and
 * polling continues: a slow curator is not a failed run. See
 * docs/decisions/curator-weekly-trigger-27-09-2026.md.
 *
 * <p>Two switches, both needed ({@link #isOn()}): CONFIGURED = {@code curator.url} and {@code curator.token}
 * are both set (the same "off unless configured" rule as the library organiser; an install without a
 * playlist maker), and ON = the person said yes to "Make weekly playlists for me" on the first-run setup
 * or in Settings ({@code install_settings.suggested_playlists}, 19-10-2026, the owner's "opt in"). Off by
 * default, so a fresh install makes no playlists until someone asks; read from the row at each tick, so
 * flipping the switch needs no restart.
 *
 * <p>A fresh install has no editions and would otherwise wait for the first Monday 03:00: when
 * {@code curator.first-run-on-start} is on, {@link #onStart()} asks the curator for its editions two
 * minutes after boot and runs one refresh if there are none at all. compose.yaml turns it on for the
 * Docker install; the jar's own default is off, so an IntelliJ start or the test suite (both read the
 * dev .env, which may hold a CURATOR_TOKEN) never calls a curator on their own. Switching the setting on
 * in the app runs the same check at once ({@link #firstRun(Duration)} with no grace).
 */
@Slf4j
@Component
public class CuratorScheduler {

    private static final Map<String, DayOfWeek> CRON_DAYS = Map.of(
            "MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY, "WED", DayOfWeek.WEDNESDAY,
            "THU", DayOfWeek.THURSDAY, "FRI", DayOfWeek.FRIDAY, "SAT", DayOfWeek.SATURDAY, "SUN", DayOfWeek.SUNDAY);

    private final CuratorClient client;
    private final InstallSettingsRepository settings;
    private final Duration pollInterval;
    private final Duration runBudget;
    private final boolean configured;
    private final boolean firstRunOnStart;
    private final DayOfWeek refreshDay;
    private final AtomicBoolean running = new AtomicBoolean();
    /** Package-private so the test can shorten it; two minutes lets croissant come up after `compose up`. */
    Duration firstRunGrace = Duration.ofMinutes(2);

    public CuratorScheduler(CuratorClient client, InstallSettingsRepository settings,
                            @Value("${curator.url:}") String url,
                            @Value("${curator.token:}") String token,
                            @Value("${curator.cron:}") String cron,
                            @Value("${curator.poll-interval-ms}") Duration pollInterval,
                            @Value("${curator.run-budget-ms}") Duration runBudget,
                            @Value("${curator.first-run-on-start:false}") Boolean firstRunOnStart) {
        this.client = client;
        this.settings = settings;
        this.pollInterval = pollInterval;
        this.runBudget = runBudget;
        this.configured = !url.isBlank() && !token.isBlank();
        // Boxed: an empty CURATOR_FIRST_RUN_ON_START= line in a copied .env.example is null, not a start-up crash.
        this.firstRunOnStart = Boolean.TRUE.equals(firstRunOnStart);
        this.refreshDay = refreshDay(cron);
        if (!configured) {
            log.info("Weekly curator refresh OFF: this install has no playlist maker. Set CURATOR_URL "
                    + "and CURATOR_TOKEN (the same token croissant is started with) to have one.");
        }
    }

    /** This install has a playlist maker to talk to ({@code curator.url} and {@code curator.token} set). */
    public boolean isConfigured() {
        return configured;
    }

    /** Configured AND the person switched weekly playlists on (one SELECT on the one-row table; no cache to get wrong). */
    public Mono<Boolean> isOn() {
        return configured ? settings.suggestedPlaylists() : Mono.just(false);
    }

    public boolean isFirstRunOnStart() {
        return firstRunOnStart;
    }

    /**
     * The weekday the cron fires on, when it is a plain weekly cron ("0 0 3 * * MON", or a digit 0-7 in
     * the last field); null for anything fancier ("MON-FRI", "*", two days), because then there is no one
     * day to tell the user. The client says "New edition every Monday" from this.
     */
    public DayOfWeek getRefreshDay() {
        return refreshDay;
    }

    static DayOfWeek refreshDay(String cron) {
        String[] fields = cron == null ? new String[0] : cron.trim().split("\\s+");
        if (fields.length != 6) {
            return null;
        }
        String day = fields[5].toUpperCase(Locale.ROOT);
        if (day.matches("[0-7]")) {
            int n = Integer.parseInt(day);
            return n == 0 || n == 7 ? DayOfWeek.SUNDAY : DayOfWeek.of(n);
        }
        return CRON_DAYS.get(day);
    }

    /** On boot: one refresh when the curator holds no editions at all, so a new install does not wait for Monday. */
    @PostConstruct
    void onStart() {
        if (configured && firstRunOnStart) {
            whenOn(firstRun()).subscribe();
        }
    }

    Mono<Void> firstRun() {
        return firstRun(firstRunGrace);
    }

    /**
     * Wait the grace period, list the curator's editions, and refresh only when the list is empty (same
     * trigger, polling and log lines as a cron tick). An error while asking is one WARN line, not a run:
     * a 90-minute job must not start on a guess, and the weekly cron still stands. No grace when the
     * switch was just turned on in the app: the person is looking at the screen.
     */
    public Mono<Void> firstRun(Duration grace) {
        return Mono.delay(grace)
                .then(Mono.defer(client::getEditions))
                .filter(List::isEmpty)
                .doOnNext(none -> log.info("No suggested playlists yet: asking the curator for a first edition"))
                .flatMap(none -> refresh())
                .onErrorResume(error -> {
                    log.warn("Could not check for suggested playlists at start; the weekly refresh still stands: {}",
                            error.getMessage());
                    return Mono.empty();
                });
    }

    /** The cron entry point. Never throws: refresh() swallows its own errors into a log line. */
    @Scheduled(cron = "${curator.cron}")
    public void tick() {
        if (!configured) {
            return;
        }
        whenOn(refresh()).subscribe();
    }

    /** {@code work} only while the switch is on; a database that does not answer is one WARN, not a run. */
    private Mono<Void> whenOn(Mono<Void> work) {
        return isOn().filter(on -> on).flatMap(on -> work)
                .onErrorResume(error -> {
                    log.warn("Could not read the suggested-playlists switch; no refresh this time: {}", error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * One full refresh: trigger, poll to a final state or the budget, log the outcome. Overlapping
     * calls are skipped, not queued. The manual path is {@link #refreshNow()}.
     */
    public Mono<Void> refresh() {
        return Mono.defer(() -> {
            if (!running.compareAndSet(false, true)) {
                log.warn("Curator refresh skipped: the previous one is still running");
                return Mono.empty();
            }
            Instant started = Instant.now();
            return client.triggerRun()
                    .doOnNext(run -> log.info("Curator run {} {} ({} categories)", run.runId(), run.status(),
                            run.categories().size()))
                    .flatMap(run -> run.isFinal() ? Mono.just(run) : poll(run))
                    .doOnNext(run -> logOutcome(run, started))
                    .then()
                    .onErrorResume(error -> {
                        log.error("Curator refresh failed: {}", error.getMessage());
                        return Mono.empty();
                    })
                    .doFinally(signal -> running.set(false));
        });
    }

    /**
     * A person's "make this week's playlists now": trigger the curator and hand the run back at once,
     * then keep following it in the background so the log tells the same story as after a cron tick.
     * The trigger is not retried (the person is waiting and can press again), and a run the weekly
     * refresh is already following is not followed twice. The curator's POST is idempotent, so pressing
     * the button during a run simply returns that run.
     */
    public Mono<CuratorRun> refreshNow() {
        return client.triggerRunOnce().doOnNext(this::followInBackground);
    }

    private void followInBackground(CuratorRun run) {
        log.info("Curator run {} {} requested by hand ({} categories)", run.runId(), run.status(),
                run.categories().size());
        if (run.isFinal() || !running.compareAndSet(false, true)) {
            return;
        }
        Instant started = Instant.now();
        poll(run)
                .doOnNext(finished -> logOutcome(finished, started))
                .onErrorResume(error -> {
                    log.error("Curator refresh failed: {}", error.getMessage());
                    return Mono.empty();
                })
                .doFinally(signal -> running.set(false))
                .subscribe();
    }

    private Mono<CuratorRun> poll(CuratorRun triggered) {
        return Flux.interval(pollInterval)
                .concatMap(i -> client.getRun(triggered.runId())
                        .onErrorResume(error -> {
                            log.warn("Curator poll of run {} failed; will ask again: {}", triggered.runId(),
                                    error.getMessage());
                            return Mono.empty();
                        }))
                .takeUntil(CuratorRun::isFinal)
                .take(runBudget)
                .last(triggered)
                .flatMap(run -> {
                    if (!run.isFinal()) {
                        log.warn("Curator run {} still running after {}; check the curator's /v1/runs/latest",
                                run.runId(), runBudget);
                    }
                    return Mono.just(run);
                });
    }

    private void logOutcome(CuratorRun run, Instant started) {
        for (CuratorCategoryResult c : run.categories()) {
            log.info("Curator category {}: {} edition={} tracks={}{}", c.key(), c.status(), c.editionDate(),
                    c.trackCount(), c.message() == null ? "" : " -- " + c.message());
        }
        log.info("Curator run {} ended with status {} after {}", run.runId(), run.status(),
                Duration.between(started, Instant.now()));
    }
}
