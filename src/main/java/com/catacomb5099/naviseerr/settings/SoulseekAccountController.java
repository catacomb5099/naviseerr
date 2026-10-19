package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The install's Soulseek account, seen and changed in the app instead of a container log
 * ({@code GET}/{@code PUT /settings/soulseek}). The account lives in two files {@code setup} writes
 * under the data folder ({@link SoulseekAccountFiles}); a change rewrites both and asks slskd to log
 * in again. Off (404) when {@code install.data-dir} is unset or the files are not there: a developer
 * run, or an install that mounts only {@code /config}. 19-10-2026, the owner's "the compose file is
 * not navigable by a non-technical person": the account was the one setting a person had to read a
 * log for. Shape follows Jellyfin's first-run wizard and Navidrome's single admin step: ask it on a
 * screen, never in the file.
 */
@RestController
public class SoulseekAccountController {

    private static final Logger log = LoggerFactory.getLogger(SoulseekAccountController.class);
    /** slskd reloads its yml by file watch; this is how long it gets before being asked to log in again. */
    static final Duration SLSKD_RELOAD_GRACE = Duration.ofSeconds(2);
    private static final Duration SLSKD_BUDGET = Duration.ofSeconds(3);
    static final String OFF_MESSAGE = "This install keeps its Soulseek account in its compose file: "
            + "there is no data folder for naviseerr to write to.";

    private final SoulseekAccountFiles files;
    private final SlskdService slskd;

    public SoulseekAccountController(@Value("${install.data-dir:}") String dataDir, SlskdService slskd) {
        this.files = dataDir.isBlank() ? null : new SoulseekAccountFiles(Path.of(dataDir));
        this.slskd = slskd;
    }

    /** {@code password} only for an account setup made up: one a person typed is theirs already and is never echoed. */
    @GetMapping("/settings/soulseek")
    Mono<ResponseEntity<?>> get() {
        if (files == null || !files.exist()) return Mono.just(off());
        return Mono.fromCallable(files::read).subscribeOn(Schedulers.boundedElastic())
                .zipWith(loggedIn())
                .map(t -> ResponseEntity.ok(new AccountView(t.getT1().username(), t.getT1().generated(),
                        t.getT1().generated() ? t.getT1().password() : null, t.getT2().orElse(null))));
    }

    /**
     * 202: the files are rewritten and slskd is being asked to log in again; whether Soulseek accepts
     * the account shows up on {@code GET /status} within about half a minute. 400 with one plain
     * sentence when the name breaks Soulseek's rules (setup.sh's three checks) or the password is empty.
     */
    @PutMapping("/settings/soulseek")
    Mono<ResponseEntity<?>> put(@RequestBody Change change) {
        if (files == null || !files.exist()) return Mono.just(off());
        String username = change.username() == null ? "" : change.username();
        String password = change.password() == null ? "" : change.password();
        String problem = problemWith(username, password);
        if (problem != null) return Mono.just(ResponseEntity.badRequest().body(Map.of("message", problem)));
        return Mono.fromCallable(() -> { files.write(username, password); return username; })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(written -> reconnect().subscribe())
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new AccountView(username, false, null, null)));
    }

    /** Soulseek's own rules on a name, as docker/setup.sh checks them; the password only has to be there. */
    static String problemWith(String username, String password) {
        if (username.isEmpty()) return "Enter a Soulseek username.";
        if (!username.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            return "Soulseek does not accept that character (accents, emoji, tabs...): plain letters, digits and punctuation only.";
        }
        if (username.length() > 30) return "The username is longer than 30 characters.";
        if (username.startsWith(" ") || username.endsWith(" ")) return "The username starts or ends with a space.";
        if (password.isEmpty()) return "Enter the password.";
        return null;
    }

    /** Disconnect, then connect: a failure on either is logged and the status poll tells the rest. */
    Mono<Void> reconnect() {
        return Mono.delay(SLSKD_RELOAD_GRACE)
                .then(slskd.disconnect("naviseerr: the Soulseek account was changed in the app")
                        .onErrorResume(e -> { log.info("slskd would not disconnect ({}); asking it to log in anyway", e.getMessage()); return Mono.empty(); }))
                .then(slskd.connect()
                        .onErrorResume(e -> { log.warn("slskd would not start a Soulseek login: {}", e.getMessage()); return Mono.empty(); }));
    }

    private Mono<Optional<Boolean>> loggedIn() {
        return slskd.getServerState().timeout(SLSKD_BUDGET)
                .map(s -> Optional.of(s.isLoggedIn()))
                .onErrorReturn(Optional.empty());
    }

    private static ResponseEntity<?> off() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", OFF_MESSAGE));
    }

    public record Change(String username, String password) {}

    /** {@code loggedIn} null when slskd cannot be reached (same meaning as on {@code GET /status}). */
    public record AccountView(String username, boolean generated, String password, Boolean loggedIn) {}
}
