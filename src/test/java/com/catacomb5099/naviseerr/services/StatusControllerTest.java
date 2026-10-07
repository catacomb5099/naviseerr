package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.slskd.ServerState;
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

    @Test
    void loggedIn_isConnected_withSlskdsOwnStateWord() {
        when(slskdService.getServerState()).thenReturn(Mono.just(state("Connected, LoggedIn", true, true)));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.connected").isEqualTo(true)
                .jsonPath("$.soulseek.loggedIn").isEqualTo(true)
                .jsonPath("$.soulseek.state").isEqualTo("Connected, LoggedIn")
                .jsonPath("$.soulseek.detail").isEmpty();
    }

    @Test
    void slskdUpButNotOnSoulseek_isNotConnected() {
        when(slskdService.getServerState()).thenReturn(Mono.just(state("Disconnected", false, false)));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.connected").isEqualTo(false)
                .jsonPath("$.soulseek.loggedIn").isEqualTo(false)
                .jsonPath("$.soulseek.state").isEqualTo("Disconnected")
                .jsonPath("$.soulseek.detail").isEmpty();
    }

    @Test
    void slskdDown_isStill200_asUnreachableWithTheReason() {
        when(slskdService.getServerState())
                .thenReturn(Mono.error(new RuntimeException("Connection refused: slskd/172.18.0.5:5030")));

        http.get().uri("/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.soulseek.connected").isEqualTo(false)
                .jsonPath("$.soulseek.loggedIn").isEqualTo(false)
                .jsonPath("$.soulseek.state").isEqualTo(StatusController.UNREACHABLE)
                .jsonPath("$.soulseek.detail").isEqualTo("Connection refused: slskd/172.18.0.5:5030");
    }
}
