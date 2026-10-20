package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.curator.CuratorScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Duration;

/**
 * {@code /settings/suggested-playlists}: the one switch "Make weekly playlists for me" (the setup's third
 * screen and Settings), off unless the person says yes: Home Assistant's analytics opt-in, where Next
 * means no. Switching it on asks the playlist maker for a first set at once (no two-minute grace) when it
 * holds none yet, so the Home page does not wait for Monday; the answer waits for that one question, so a
 * maker that is down is said so, not promised. Read by the weekly clock and every suggested-playlists
 * request from the row, so no restart.
 */
@Slf4j
@RestController
@RequestMapping("/settings/suggested-playlists")
public class SuggestedPlaylistsSettingsController {

    static final String NOT_CONFIGURED = "This install has no playlist maker (no curator address or token).";
    static final String ON = "On. If there are no playlists yet the first set is being made now; "
            + "the Home page shows it when it is ready (about 90 minutes).";
    static final String ON_NOT_ANSWERING = "On, but the playlist maker is not answering right now, so no first set was "
            + "started; the weekly run will try again.";
    static final String OFF = "Off. No playlists are made until you switch this on.";

    public record Change(boolean on) {}

    /**
     * {@code configured}: this install has a playlist maker at all (false on a developer run: the switch is
     * disabled); {@code refreshDay} the weekday of the weekly run, null when the cron is not one plain weekday;
     * {@code message} the sentence for the status line, null on GET.
     */
    public record View(boolean on, boolean configured, DayOfWeek refreshDay, String message) {}

    private final InstallSettingsRepository settings;
    private final CuratorScheduler scheduler;

    public SuggestedPlaylistsSettingsController(InstallSettingsRepository settings, CuratorScheduler scheduler) {
        this.settings = settings;
        this.scheduler = scheduler;
    }

    @GetMapping
    Mono<View> get() {
        return settings.suggestedPlaylists().map(on -> view(on, null));
    }

    /** 200 with the saved switch and a sentence; 400 when asked for playlists on an install that has no maker. */
    @PutMapping
    Mono<View> save(@RequestBody Change body) {
        if (body.on() && !scheduler.isConfigured()) {
            return Mono.error(new SettingsProblem(NOT_CONFIGURED));
        }
        if (!body.on()) {
            return settings.setSuggestedPlaylists(false).thenReturn(view(false, OFF));
        }
        // The sentence waits for the curator's one answer (bounded by curator.timeout-ms), not for the run it may start.
        return settings.setSuggestedPlaylists(true).then(Mono.defer(() -> scheduler.firstRun(Duration.ZERO).thenReturn(ON)
                .onErrorResume(error -> {
                    log.warn("Weekly playlists switched on, but the playlist maker did not answer: {}", error.getMessage());
                    return Mono.just(ON_NOT_ANSWERING);
                })))
                .map(sentence -> view(true, sentence));
    }

    private View view(boolean on, String message) {
        return new View(on, scheduler.isConfigured(), scheduler.getRefreshDay(), message);
    }
}
