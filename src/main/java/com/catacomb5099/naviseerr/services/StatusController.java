package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.slskd.SlskdOptions;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code GET /status}: is this install's Soulseek client logged in? The one newcomer question the app
 * could not answer before -- a wrong or taken username left the web app silent until a download
 * failed two minutes later. The web app polls it with the downloads feed and shows a strip while
 * {@code loggedIn} is false.
 */
@RestController
public class StatusController {

    /** {@code state} when slskd itself does not answer: not a Soulseek state, so it cannot be mistaken for one. */
    static final String UNREACHABLE = "UNREACHABLE";
    private static final Duration SLSKD_BUDGET = Duration.ofSeconds(3);

    private final SlskdService slskdService;

    public StatusController(SlskdService slskdService) {
        this.slskdService = slskdService;
    }

    /** Always 200: a down slskd is an answer, not an error, so the strip never has to guess. */
    @GetMapping("/status")
    Mono<StatusView> status() {
        // The configured name from slskd's options: known before any login, which is when the strip
        // shows it. Not knowing it (an error on that call alone) is null, never a failed status.
        Mono<Optional<String>> username = slskdService.getOptions()
                .map(o -> Optional.ofNullable(o.soulseek()).map(SlskdOptions.Soulseek::username).filter(u -> !u.isBlank()))
                .onErrorReturn(Optional.empty());
        return Mono.zip(slskdService.getServerState(), username)
                .timeout(SLSKD_BUDGET)
                .map(t -> new SoulseekStatus(t.getT1().isConnected(), t.getT1().isLoggedIn(), t.getT1().getState(), null,
                        t.getT2().orElse(null)))
                .onErrorResume(e -> Mono.just(new SoulseekStatus(false, false, UNREACHABLE,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), null)))
                .map(StatusView::new);
    }

    public record StatusView(SoulseekStatus soulseek) {}

    /**
     * {@code state} is slskd's own word: "None" before it has ever tried to connect, "Disconnected",
     * "Connected, LoggedIn"; {@code detail} only with UNREACHABLE; {@code username} the Soulseek
     * account this install logs in with, null when slskd cannot be reached or does not say.
     */
    public record SoulseekStatus(boolean connected, boolean loggedIn, String state, String detail, String username) {}
}
