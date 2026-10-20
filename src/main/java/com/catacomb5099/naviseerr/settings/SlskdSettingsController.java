package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.mediaserver.MediaServerException;
import com.catacomb5099.naviseerr.schema.slskd.ServerState;
import com.catacomb5099.naviseerr.services.slskd.SlskdConfig;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * {@code /settings/slskd}: use your own Soulseek client (slskd) instead of the one the install bundles,
 * chosen on the setup's first screen ("Use my own slskd") or in Settings. The owner's question: "do you
 * have an instance of slskd?"; Sonarr keeps "your own download client" the same way. "Save and test" (PUT)
 * asks that slskd for its Soulseek state ({@code GET /server}) with the typed key, and only then stores
 * the address and key and points every call (searches, downloads, {@code /status}) at it, live. What the
 * app saved wins; the install file's {@code SLSKD_URL} / {@code SLSKD_API_KEY} are the fallback
 * ({@code fromInstallFile}). The key is write-only (an empty field keeps the saved one, for its own host only).
 * Not checked here, and said on the screen: naviseerr must see that slskd's downloads folder, or nothing is
 * filed and no playlist file is written. Searches and downloads under way at the switch fail within about a
 * minute (the new slskd never had their ids) and can be retried.
 */
@Slf4j
@RestController
@RequestMapping("/settings/slskd")
public class SlskdSettingsController {

    /** The slskd API lives under this path; a person types the host and port, the tail is added when missing. */
    static final String API_PATH = "/api/v0/";
    private static final Duration TEST_BUDGET = Duration.ofSeconds(5);

    public record Change(String url, String apiKey) {}
    /** {@code url} the slskd every call goes to right now; {@code message} the "Save and test" sentence, null on GET. */
    public record View(String url, boolean apiKeySet, boolean fromInstallFile, String message) {}

    private final InstallSettingsRepository settings;
    private final SlskdService slskd;
    private final SlskdConfig config;
    private final WebClient installFile;

    public SlskdSettingsController(InstallSettingsRepository settings, SlskdService slskd, SlskdConfig config,
                                   @Qualifier("slskdWebClient") WebClient installFile) {
        this.settings = settings;
        this.slskd = slskd;
        this.config = config;
        this.installFile = installFile;
    }

    /** After Flyway: a saved slskd applies before the first search, over the install file's. */
    @EventListener(ApplicationReadyEvent.class)
    public void applySaved() {
        settings.find().subscribe(this::apply,
                error -> log.warn("Could not read the saved slskd settings; the install file's apply: {}", error.getMessage()));
    }

    void apply(InstallSettings s) {
        if (s.slskdUrl() != null) {
            slskd.use(config.build(s.slskdUrl(), s.slskdApiKey()), s.slskdUrl());
        } else {
            slskd.use(installFile, config.url());
        }
    }

    @GetMapping
    Mono<View> get() {
        return settings.find().map(s -> view(s, null));
    }

    /** "Save and test": 200 with the view and a sentence, or 400 {@code {message}} and nothing saved. */
    @PutMapping
    Mono<View> save(@RequestBody Change body) {
        String typed = body.url() == null ? "" : body.url().strip();
        if (typed.isEmpty()) return Mono.error(new SettingsProblem("Enter the address of slskd."));
        if (!typed.startsWith("http://") && !typed.startsWith("https://")) {
            return Mono.error(new SettingsProblem("The address should start with http:// or https://, for example http://192.168.1.20:5030."));
        }
        String url = normalise(typed);
        return settings.find().flatMap(existing -> {
            String apiKey = body.apiKey() == null || body.apiKey().isBlank()
                    ? MediaServerSettingsController.storedSecret(url, existing.slskdUrl(), existing.slskdApiKey()) : body.apiKey().strip();
            if (apiKey.isEmpty()) return Mono.error(new SettingsProblem("Enter the slskd API key."));
            return test(url, apiKey)
                    .flatMap(state -> settings.saveSlskd(url, apiKey).then(Mono.defer(settings::find))
                            .map(s -> { apply(s); return view(s, connected(url, state)); }));
        });
    }

    /** Back to the install file's slskd, live. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> forget() {
        return settings.clearSlskd().then(Mono.defer(settings::find)).doOnNext(this::apply).then();
    }

    /** {@code GET /server} on that slskd with that key: the one call that proves address and key, and says whether Soulseek is in. */
    private Mono<ServerState> test(String url, String apiKey) {
        return config.build(url, apiKey).get().uri("/server").retrieve().bodyToMono(ServerState.class)
                .timeout(TEST_BUDGET)
                .onErrorMap(error -> new SettingsProblem(problem(url, error)));
    }

    static String problem(String url, Throwable error) {
        if (error instanceof WebClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == 401 || status == 403) return "slskd refused the API key.";
            if (status == 404) return "slskd at " + url + " answered HTTP 404: check the address (it normally ends in " + API_PATH + ").";
        }
        return MediaServerException.transport("slskd", url, error).getMessage();
    }

    static String connected(String url, ServerState state) {
        return state.isLoggedIn()
                ? "Connected to your slskd at " + url + "; it is logged in to Soulseek."
                : "Connected to your slskd at " + url + ", but it is not logged in to Soulseek yet (" + state.getState() + ").";
    }

    /** {@code http://host:5030}, {@code http://host:5030/} and {@code http://host:5030/api/v0} all become {@code http://host:5030/api/v0/}. */
    static String normalise(String typed) {
        String url = typed;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.endsWith("/api/v0")) url = url + "/api/v0";
        return url + "/";
    }

    private View view(InstallSettings s, String message) {
        boolean fromInstallFile = s.slskdUrl() == null;
        // A key is in force either way (the install file's, or the saved one); the web app offers "leave empty to keep it" for a saved one only.
        return new View(slskd.url(), fromInstallFile || s.slskdApiKey() != null, fromInstallFile, message);
    }
}
