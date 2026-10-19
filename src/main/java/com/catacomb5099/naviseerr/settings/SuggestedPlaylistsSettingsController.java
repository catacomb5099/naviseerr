package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.curator.CuratorScheduler;
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
 * holds none yet, so the Home page does not wait for Monday. Read by the weekly clock and every
 * suggested-playlists request from the row, so no restart.
 */
@RestController
@RequestMapping("/settings/suggested-playlists")
public class SuggestedPlaylistsSettingsController {

    static final String NOT_CONFIGURED = "This install has no playlist maker (no curator address or token).";

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
        return settings.setSuggestedPlaylists(body.on()).then(Mono.fromSupplier(() -> {
            if (body.on()) {
                scheduler.firstRun(Duration.ZERO).subscribe();
                return view(true, "On. If there are no playlists yet the first set is being made now; "
                        + "the Home page shows it when it is ready (about 90 minutes).");
            }
            return view(false, "Off. No playlists are made until you switch this on.");
        }));
    }

    private View view(boolean on, String message) {
        return new View(on, scheduler.isConfigured(), scheduler.getRefreshDay(), message);
    }
}
