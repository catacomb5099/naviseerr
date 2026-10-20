package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.mediaserver.JellyfinClient;
import com.catacomb5099.naviseerr.mediaserver.MediaServerConfig;
import com.catacomb5099.naviseerr.mediaserver.MediaServerException;
import com.catacomb5099.naviseerr.mediaserver.MediaServerNotifier;
import com.catacomb5099.naviseerr.mediaserver.MediaServerNotifier.MediaServerStatus;
import com.catacomb5099.naviseerr.mediaserver.NavidromeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * {@code /settings/media-servers}: which music app (Navidrome or Jellyfin) is told to rescan when a
 * download lands, chosen in the web app instead of five lines in the install file (the "Connect" screen
 * Lidarr and Sonarr have: pick a type, fill the fields, Test). "Save and test" (PUT) runs ONE real
 * rescan with the typed values, which proves the address, the login and, for Navidrome, that the account
 * is an admin, and only then stores them and points the clients at them, live. What the app saved wins;
 * a server never saved here keeps the install file's {@code NAVIDROME_*} / {@code JELLYFIN_*} lines as
 * its fallback ({@code fromInstallFile}). The password and the API key are write-only: GET says only
 * whether one is set, and an empty field on PUT keeps the stored one (for the host it was saved for).
 */
@Slf4j
@RestController
@RequestMapping("/settings/media-servers")
public class MediaServerSettingsController {

    public record NavidromeChange(String url, String username, String password) {}
    public record JellyfinChange(String url, String apiKey) {}
    /** PUT body: exactly one of the two. */
    public record Change(NavidromeChange navidrome, JellyfinChange jellyfin) {}

    public record NavidromeView(String url, String username, boolean passwordSet, boolean fromInstallFile) {}
    public record JellyfinView(String url, boolean apiKeySet, boolean fromInstallFile) {}
    /**
     * {@code navidrome} / {@code jellyfin} null when neither the app nor the install file sets that server;
     * {@code status} the notifier's last outcome per server (as {@code GET /status} shows it); {@code message}
     * the "Save and test" sentence, null on GET.
     */
    public record View(NavidromeView navidrome, JellyfinView jellyfin, List<MediaServerStatus> status, String message) {}

    private final InstallSettingsRepository settings;
    private final NavidromeClient navidrome;
    private final JellyfinClient jellyfin;
    private final MediaServerNotifier notifier;

    public MediaServerSettingsController(InstallSettingsRepository settings, NavidromeClient navidrome,
                                         JellyfinClient jellyfin, MediaServerNotifier notifier) {
        this.settings = settings;
        this.navidrome = navidrome;
        this.jellyfin = jellyfin;
        this.notifier = notifier;
    }

    /** After Flyway: what the app saved applies from the first pass, over the install file's lines. */
    @EventListener(ApplicationReadyEvent.class)
    public void applySaved() {
        settings.find().subscribe(this::apply,
                error -> log.warn("Could not read the saved music-app settings; the install file's apply: {}", error.getMessage()));
    }

    /** The saved row wins; a column never saved leaves that client on the install file's lines. */
    void apply(InstallSettings s) {
        if (s.navidromeUrl() != null) {
            navidrome.reconfigure(s.navidromeUrl(), s.navidromeUser(), s.navidromePassword());
        } else {
            navidrome.reset();
        }
        if (s.jellyfinUrl() != null) {
            jellyfin.reconfigure(s.jellyfinUrl(), s.jellyfinApiKey());
        } else {
            jellyfin.reset();
        }
        notifier.reconfigure();
    }

    @GetMapping
    Mono<View> get() {
        return settings.find().map(s -> view(s, null));
    }

    /** "Save and test": 200 with the view and a sentence, or 400 {@code {message}} and nothing saved. */
    @PutMapping
    Mono<View> save(@RequestBody Change body) {
        if ((body.navidrome() == null) == (body.jellyfin() == null)) {
            return Mono.error(new SettingsProblem("Choose Navidrome or Jellyfin."));
        }
        return settings.find().flatMap(existing -> body.navidrome() != null
                ? saveNavidrome(body.navidrome(), existing) : saveJellyfin(body.jellyfin(), existing));
    }

    private Mono<View> saveNavidrome(NavidromeChange c, InstallSettings existing) {
        String typedUrl = clean(c.url());
        String username = clean(c.username());
        String password = c.password() == null || c.password().isEmpty()
                ? storedSecret(typedUrl, existing.navidromeUrl(), existing.navidromePassword()) : c.password();
        String problem = urlProblem(typedUrl, "Navidrome", "4533");
        if (problem == null && username.isEmpty()) problem = "Enter the Navidrome admin username.";
        if (problem == null && password.isEmpty()) problem = "Enter the Navidrome password.";
        if (problem != null) return Mono.error(new SettingsProblem(problem));
        String url = MediaServerConfig.baseUrl(typedUrl);
        // defer: the save must not even be prepared (a mock would record it) before the test passed.
        return navidrome.test(url, username, password)
                .onErrorMap(MediaServerException.class, e -> new SettingsProblem(e.getMessage()))
                .then(Mono.defer(() -> settings.saveNavidrome(url, username, password)))
                .then(Mono.defer(settings::find))
                .map(s -> { apply(s); return view(s, "Connected to Navidrome at " + url + ": it is rescanning now."); });
    }

    private Mono<View> saveJellyfin(JellyfinChange c, InstallSettings existing) {
        String typedUrl = clean(c.url());
        String apiKey = c.apiKey() == null || c.apiKey().isBlank()
                ? storedSecret(typedUrl, existing.jellyfinUrl(), existing.jellyfinApiKey()) : c.apiKey().strip();
        String problem = urlProblem(typedUrl, "Jellyfin", "8096");
        if (problem == null && apiKey.isEmpty()) problem = "Enter the Jellyfin API key.";
        if (problem != null) return Mono.error(new SettingsProblem(problem));
        String url = MediaServerConfig.baseUrl(typedUrl);
        return jellyfin.test(url, apiKey)
                .onErrorMap(MediaServerException.class, e -> new SettingsProblem(e.getMessage()))
                .then(Mono.defer(() -> settings.saveJellyfin(url, apiKey)))
                .then(Mono.defer(settings::find))
                .map(s -> { apply(s); return view(s, "Connected to Jellyfin at " + url + ": it is rescanning now."); });
    }

    /** Forget what the app saved for that server: the install file's lines, if any, apply again. */
    @DeleteMapping("/{server}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> forget(@PathVariable String server) {
        Mono<Void> cleared = switch (server) {
            case "navidrome" -> settings.clearNavidrome();
            case "jellyfin" -> settings.clearJellyfin();
            default -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        };
        return cleared.then(Mono.defer(settings::find)).doOnNext(this::apply).then();
    }

    private View view(InstallSettings s, String message) {
        NavidromeView nd = navidrome.isConfigured()
                ? new NavidromeView(navidrome.url(), navidrome.username(), navidrome.hasCredentials(), s.navidromeUrl() == null) : null;
        JellyfinView jf = jellyfin.isConfigured()
                ? new JellyfinView(jellyfin.url(), jellyfin.hasCredentials(), s.jellyfinUrl() == null) : null;
        return new View(nd, jf, notifier.status(), message);
    }

    static String urlProblem(String url, String server, String examplePort) {
        if (url.isEmpty()) return "Enter the address of " + server + ".";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "The address should start with http:// or https://, for example http://192.168.1.10:" + examplePort + ".";
        }
        return null;
    }

    /**
     * The stored secret stands in for an empty field only for the host it was saved for: in open mode anyone on
     * the network could otherwise point the test at a host of their own and have the server hand it the secret.
     */
    static String storedSecret(String typedUrl, String storedUrl, String secret) {
        try {
            boolean sameHost = storedUrl != null && Objects.equals(URI.create(typedUrl).getHost(), URI.create(storedUrl).getHost());
            return sameHost ? clean(secret) : "";
        } catch (IllegalArgumentException odd) {
            return "";
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
