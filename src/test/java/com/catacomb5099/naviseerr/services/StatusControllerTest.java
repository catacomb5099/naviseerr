package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.slskd.ServerState;
import com.catacomb5099.naviseerr.schema.slskd.SlskdOptions;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusControllerTest {

    private final SlskdService slskdService = mock(SlskdService.class);
    private final WebTestClient http = WebTestClient.bindToController(new StatusController(slskdService)).build();

    private static ServerState state(String state, boolean connected, boolean loggedIn) {
        return new ServerState("vps.slsknet.org:2271", "1.2.3.4:2271", state, connected, false, loggedIn, false, false);
    }

    private static SlskdOptions options(String username) {
        return new SlskdOptions(new SlskdOptions.Soulseek(username));
    }

    @Test
    void loggedIn_isConnected_withSlskdsOwnStateWord_andTheAccountName() {
        when(slskdService.getServerState()).thenReturn(Mono.just(state("Connected, LoggedIn", true, true)));
        when(slskdService.getOptions()).thenReturn(Mono.just(options("naviseerr-ab12cd")));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.connected").isEqualTo(true)
                .jsonPath("$.soulseek.loggedIn").isEqualTo(true)
                .jsonPath("$.soulseek.state").isEqualTo("Connected, LoggedIn")
                .jsonPath("$.soulseek.detail").isEmpty()
                .jsonPath("$.soulseek.username").isEqualTo("naviseerr-ab12cd");
    }

    @Test
    void slskdUpButNotOnSoulseek_isNotConnected_andStillNamesTheAccount() {
        when(slskdService.getServerState()).thenReturn(Mono.just(state("Disconnected", false, false)));
        when(slskdService.getOptions()).thenReturn(Mono.just(options("naviseerr-ab12cd")));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.connected").isEqualTo(false)
                .jsonPath("$.soulseek.loggedIn").isEqualTo(false)
                .jsonPath("$.soulseek.state").isEqualTo("Disconnected")
                .jsonPath("$.soulseek.detail").isEmpty()
                .jsonPath("$.soulseek.username").isEqualTo("naviseerr-ab12cd");
    }

    @Test
    void slskdDown_isStill200_asUnreachableWithTheReason_andNoName() {
        when(slskdService.getServerState())
                .thenReturn(Mono.error(new RuntimeException("Connection refused: slskd/172.18.0.5:5030")));
        when(slskdService.getOptions())
                .thenReturn(Mono.error(new RuntimeException("Connection refused: slskd/172.18.0.5:5030")));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.connected").isEqualTo(false)
                .jsonPath("$.soulseek.loggedIn").isEqualTo(false)
                .jsonPath("$.soulseek.state").isEqualTo(StatusController.UNREACHABLE)
                .jsonPath("$.soulseek.detail").isEqualTo("Connection refused: slskd/172.18.0.5:5030")
                .jsonPath("$.soulseek.username").isEmpty();
    }

    @Test
    void optionsCallFailingAlone_keepsTheStatus_withoutAName() {
        when(slskdService.getServerState()).thenReturn(Mono.just(state("Connected, LoggedIn", true, true)));
        when(slskdService.getOptions()).thenReturn(Mono.error(new RuntimeException("403 Forbidden")));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.loggedIn").isEqualTo(true)
                .jsonPath("$.soulseek.state").isEqualTo("Connected, LoggedIn")
                .jsonPath("$.soulseek.detail").isEmpty()
                .jsonPath("$.soulseek.username").isEmpty();
    }

    /** Found in review: a stalled (not failing) /options used to time the whole answer out into UNREACHABLE. */
    @Test
    void optionsCallStallingAlone_keepsTheStatus_withoutAName() {
        when(slskdService.getServerState()).thenReturn(Mono.just(state("Connected, LoggedIn", true, true)));
        when(slskdService.getOptions()).thenReturn(Mono.never());

        http.mutate().responseTimeout(java.time.Duration.ofSeconds(10)).build()
                .get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.loggedIn").isEqualTo(true)
                .jsonPath("$.soulseek.state").isEqualTo("Connected, LoggedIn")
                .jsonPath("$.soulseek.detail").isEmpty()
                .jsonPath("$.soulseek.username").isEmpty();
    }

    @Test
    void blankOrMissingUsername_isNull() {
        when(slskdService.getServerState()).thenReturn(Mono.just(state("Disconnected", false, false)));
        when(slskdService.getOptions()).thenReturn(Mono.just(options("  ")));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.username").isEmpty();

        when(slskdService.getOptions()).thenReturn(Mono.just(new SlskdOptions(null)));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.username").isEmpty();
    }
}
