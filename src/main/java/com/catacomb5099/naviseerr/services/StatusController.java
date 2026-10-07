package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * {@code GET /status}: is this install's Soulseek client logged in? The one newcomer question the app
 * could not answer before -- a wrong or taken username left the web app silent until a download
 * failed two minutes later. The web app polls it with the downloads feed and shows a strip while
 * {@code connected} is false.
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
        return slskdService.getServerState()
                .timeout(SLSKD_BUDGET)
                .map(s -> new SoulseekStatus(s.isConnected(), s.isLoggedIn(), s.getState(), null))
                .onErrorResume(e -> Mono.just(new SoulseekStatus(false, false, UNREACHABLE,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())))
                .map(StatusView::new);
    }

    public record StatusView(SoulseekStatus soulseek) {}

    /** {@code state} is slskd's own word ("Connected, LoggedIn", "Disconnected"); {@code detail} only with UNREACHABLE. */
    public record SoulseekStatus(boolean connected, boolean loggedIn, String state, String detail) {}
}
