package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Whether this install has been through its first-run setup (the four screens the web app shows the
 * first time it opens, as Jellyfin's setup wizard does), and the one fact the setup's first and last
 * screens need: where the music lands. The answers themselves live in their own places
 * ({@code /settings/soulseek}, {@code /settings/media-servers}, {@code /settings/suggested-playlists},
 * {@code /settings/login}); this only remembers that the questions were asked.
 */
@RestController
public class SetupController {

    /** {@code libraryRoot} null when the organiser is off (a developer run): the web app then drops its "your music lands in" line. */
    public record View(boolean completed, String libraryRoot) {}

    private final InstallSettingsRepository settings;
    private final LibraryOrganiser organiser;

    public SetupController(InstallSettingsRepository settings, LibraryOrganiser organiser) {
        this.settings = settings;
        this.organiser = organiser;
    }

    @GetMapping("/settings/setup")
    Mono<View> get() {
        return settings.find().map(s -> new View(s.setupCompleted(), organiser.libraryRoot()));
    }

    /** Finish on the last screen, or skipping every screen: both mean "do not show the setup again". */
    @PostMapping("/settings/setup/done")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> done() {
        return settings.markSetupDone();
    }
}
