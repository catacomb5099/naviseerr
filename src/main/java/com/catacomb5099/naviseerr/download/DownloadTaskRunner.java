package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The level-triggered driver. Every pass asks the database what is due and acts on the answer, so a
 * lost wakeup costs one interval instead of a download and there is nothing in the heap to lose on
 * restart.
 *
 * <p>Passes are serialised with {@code concatMap}, so a slow pass delays the next one. Accepted for
 * simplicity: leases already make overlapping passes safe, so switching later needs no other change.
 * Stepping the rows CLAIMED WITHIN one pass is concurrent ({@code flatMap(batchSize)}) — the earlier
 * {@code concatMap} there processed claimed rows strictly one at a time, so a batch of 10 rows each
 * waiting on a 10s slskd timeout could make one pass take up to 100 seconds. Nothing needs a thread
 * pool for this: WebFlux already runs an event loop per core: the only thing wrong was the sequencing.
 */
@Slf4j
@Component
public class DownloadTaskRunner {

    private final DownloadTaskRepository repository;
    private final DownloadStepExecutor executor;
    private final DownloadService downloadService;
    private final SlskdService slskdService;
    private final Clock clock;
    private final Duration loopInterval;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxConcurrentDownloads;
    private final int maxConcurrentTransfers;
    /** Identifies this process in lease_owner. Nothing depends on it surviving a restart. */
    private final String instanceId = UUID.randomUUID().toString();
    private Disposable subscription;

    public DownloadTaskRunner(
            DownloadTaskRepository repository,
            DownloadStepExecutor executor,
            DownloadService downloadService,
            SlskdService slskdService,
            Clock clock,
            @Value("${download-task.loop-interval-ms:2000}") Duration loopInterval,
            @Value("${download-task.batch-size:10}") int batchSize,
            @Value("${download-task.lease-duration-ms:60000}") Duration leaseDuration,
            @Value("${download-task.max-concurrent-downloads:20}") int maxConcurrentDownloads,
            @Value("${download-task.max-concurrent-transfers:20}") int maxConcurrentTransfers) {
        this.repository = repository;
        this.executor = executor;
        this.downloadService = downloadService;
        this.slskdService = slskdService;
        this.clock = clock;
        this.loopInterval = loopInterval;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxConcurrentDownloads = maxConcurrentDownloads;
        this.maxConcurrentTransfers = maxConcurrentTransfers;
    }

    @PostConstruct
    void start() {
        subscription = Flux.interval(loopInterval)
                .onBackpressureDrop()
                .concatMap(tick -> pass())
                .subscribe();
    }

    Mono<Void> pass() {
        Instant now = clock.instant();
        return admit(now)
                .then(stepDueTasks(now))
                .onErrorResume(error -> {
                    log.error("Download task pass failed", error);
                    return Mono.empty();
                });
    }

    /**
     * Claims only up to remaining capacity, so no download is ever admitted and then rejected — which
     * is why nothing needs to revert a row to PENDING. Counts in-flight DOWNLOADS, not tasks: a
     * 500-song collection is one download, so it cannot starve admission for everything else.
     */
    private Mono<Void> admit(Instant now) {
        return repository.countActiveDownloads()
                .flatMap(inFlight -> {
                    int slots = Math.min(batchSize, maxConcurrentDownloads - inFlight.intValue());
                    if (slots <= 0) {
                        return Mono.empty();
                    }
                    return repository.admitNewDownloads(slots, now)
                            .doOnNext(admitted -> {
                                if (admitted > 0) log.info("Admitted {} download(s)", admitted);
                            });
                })
                .then();
    }

    /**
     * Gates only the step that STARTS a transfer. Searches and polls of already-running transfers are
     * never gated — polling is one cheap GET, and starving it stalls a download slskd is finishing.
     */
    private Mono<Void> stepDueTasks(Instant now) {
        return repository.countActiveTransfers()
                .flatMapMany(active -> {
                    boolean transferSlotsFree = active < maxConcurrentTransfers;
                    return repository.claimDueTasks(batchSize, instanceId, now, leaseDuration,
                            transferSlotsFree);
                })
                .collectList()
                .flatMap(this::stepAll);
    }

    /**
     * Fetches the two batched slskd lists ONCE per pass — not once per claimed row — and only when at
     * least one claimed row actually needs one, so an idle pass (nothing claimed) and a pass with only
     * SEARCH_INIT/DOWNLOAD_INIT rows make zero calls to either endpoint. This, together with
     * {@link DownloadStepExecutor} reading from the resulting maps instead of calling slskd itself, is
     * what turns "one call per download per poll" into "two calls per pass, however many downloads
     * are in flight."
     */
    private Mono<Void> stepAll(List<DownloadTask> claimed) {
        if (claimed.isEmpty()) {
            return Mono.empty();
        }
        boolean needsSearches = claimed.stream().anyMatch(t -> t.phase() == DownloadPhase.SEARCH_POLL);
        boolean needsTransfers = claimed.stream().anyMatch(t -> t.phase() == DownloadPhase.DOWNLOAD_POLL);

        Mono<Map<String, SearchState>> searches = needsSearches
                ? slskdService.getAllSearches().collectMap(SearchState::getId)
                : Mono.just(Map.of());
        Mono<Map<String, TransferedFile>> transfers = needsTransfers
                ? slskdService.getAllDownloads().collectMap(TransferedFile::getId)
                : Mono.just(Map.of());

        return Mono.zip(searches, transfers)
                .flatMap(fetched -> Flux.fromIterable(claimed)
                        .flatMap(task -> stepOne(task, fetched.getT1(), fetched.getT2()), batchSize)
                        .then());
    }

    /** Every task is isolated: one bad step must never abort the rest of the pass. */
    private Mono<Void> stepOne(DownloadTask task, Map<String, SearchState> searchesById,
                               Map<String, TransferedFile> transfersById) {
        return executor.execute(task, searchesById, transfersById)
                .flatMap(decision -> apply(task, decision))
                .onErrorResume(error -> {
                    log.error("Download {} step {} could not be applied; the lease will expire and "
                            + "the row will be retried", task.downloadId(), task.phase(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> apply(DownloadTask task, DownloadDecision decision) {
        return switch (decision) {
            case DownloadDecision.Advance advance -> repository.save(advance.next()).then();
            case DownloadDecision.Continue proceed -> repository.save(proceed.next()).then();
            case DownloadDecision.Terminal terminal -> {
                log.info("Download {} finished as {}{}", task.downloadId(), terminal.status(),
                        terminal.message() == null ? "" : " (" + terminal.message() + ")");
                yield downloadService.finishDownload(task.downloadId(), terminal.status(),
                        terminal.message(), clock.instant()).then();
            }
        };
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }
}
