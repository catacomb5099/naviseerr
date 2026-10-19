package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.schema.slskd.ServerState;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoulseekAccountControllerTest {

    private final SlskdService slskd = mock(SlskdService.class);

    private WebTestClient http(String dataDir) {
        return WebTestClient.bindToController(new SoulseekAccountController(dataDir, slskd)).build();
    }

    private static ServerState state(boolean loggedIn) {
        return new ServerState("vps.slsknet.org:2271", "1.2.3.4:2271", loggedIn ? "Connected, LoggedIn" : "Disconnected",
                loggedIn, false, loggedIn, false, false);
    }

    @Test
    void madeUpAccount_showsTheNameAndThePassword_andWhetherSoulseekAcceptedIt(@TempDir Path data) throws IOException {
        SoulseekAccountFilesTest.setupShaped(data);
        when(slskd.getServerState()).thenReturn(Mono.just(state(true)));

        http(data.toString()).get().uri("/settings/soulseek").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("naviseerr-ab12cd")
                .jsonPath("$.generated").isEqualTo(true)
                .jsonPath("$.password").isEqualTo("Pw0Generated")
                .jsonPath("$.loggedIn").isEqualTo(true);
    }

    @Test
    void ownAccount_showsTheNameOnly_neverThePassword(@TempDir Path data) throws IOException {
        SoulseekAccountFilesTest.setupShaped(data);
        new SoulseekAccountFiles(data).write("my-own-name", "mine");
        when(slskd.getServerState()).thenReturn(Mono.just(state(false)));

        http(data.toString()).get().uri("/settings/soulseek").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("my-own-name")
                .jsonPath("$.generated").isEqualTo(false)
                .jsonPath("$.password").isEmpty()
                .jsonPath("$.loggedIn").isEqualTo(false);
    }

    @Test
    void slskdUnreachable_leavesLoggedInUnknown_andStillShowsTheAccount(@TempDir Path data) throws IOException {
        SoulseekAccountFilesTest.setupShaped(data);
        when(slskd.getServerState()).thenReturn(Mono.error(new RuntimeException("Connection refused")));

        http(data.toString()).get().uri("/settings/soulseek").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("naviseerr-ab12cd")
                .jsonPath("$.loggedIn").isEmpty();
    }

    @Test
    void noDataFolder_isOff_onBothRoutes(@TempDir Path empty) {
        http("").get().uri("/settings/soulseek").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.message").isEqualTo(SoulseekAccountController.OFF_MESSAGE);
        http(empty.toString()).put().uri("/settings/soulseek").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "x", "password", "y")).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void aNameBreakingSoulseeksRules_is400WithOneSentence_andChangesNothing(@TempDir Path data) throws IOException {
        SoulseekAccountFilesTest.setupShaped(data);
        var http = http(data.toString());

        for (var bad : Map.of(
                "abcdefghijklmnopqrstuvwxyz12345", "longer than 30",
                "café", "does not accept that character",
                "trailing ", "starts or ends with a space",
                "", "Enter a Soulseek username")
                .entrySet()) {
            http.put().uri("/settings/soulseek").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("username", bad.getKey(), "password", "pw")).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.message").value(m -> assertTrue(m.toString().contains(bad.getValue()), m.toString()));
        }
        http.put().uri("/settings/soulseek").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "fine", "password", "")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo("Enter the password.");

        assertEquals("naviseerr-ab12cd", new SoulseekAccountFiles(data).read().username());
        verify(slskd, never()).connect();
    }

    @Test
    void aGoodChange_rewritesBothFiles_answers202_thenAsksSlskdToLogOutAndInAgain(@TempDir Path data) throws IOException {
        SoulseekAccountFilesTest.setupShaped(data);
        when(slskd.disconnect(anyString())).thenReturn(Mono.empty());
        when(slskd.connect()).thenReturn(Mono.empty());

        http(data.toString()).put().uri("/settings/soulseek").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "alex", "password", "s3cret")).exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.username").isEqualTo("alex")
                .jsonPath("$.generated").isEqualTo(false)
                .jsonPath("$.password").isEmpty();

        var account = new SoulseekAccountFiles(data).read();
        assertEquals("alex", account.username());
        assertEquals("s3cret", account.password());
        assertTrue(Files.readString(data.resolve("config/secrets.env")).contains("SOULSEEK_USERNAME=alex\nSOULSEEK_PASSWORD=s3cret\n"));
        var order = inOrder(slskd);
        order.verify(slskd, timeout(5000)).disconnect(anyString());
        order.verify(slskd, timeout(5000)).connect();
    }

    @Test
    void slskdRefusingTheReconnect_doesNotFailTheChange(@TempDir Path data) throws IOException {
        SoulseekAccountFilesTest.setupShaped(data);
        when(slskd.disconnect(anyString())).thenReturn(Mono.error(new RuntimeException("500")));
        when(slskd.connect()).thenReturn(Mono.error(new RuntimeException("Connection refused")));

        http(data.toString()).put().uri("/settings/soulseek").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "alex", "password", "s3cret")).exchange()
                .expectStatus().isAccepted();

        verify(slskd, timeout(5000)).connect();
        assertEquals("alex", new SoulseekAccountFiles(data).read().username());
    }
}
