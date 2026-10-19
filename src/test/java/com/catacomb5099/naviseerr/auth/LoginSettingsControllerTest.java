package com.catacomb5099.naviseerr.auth;

import com.catacomb5099.naviseerr.auth.AuthentikClient.OpenIdConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The Settings page's three calls, over an in-memory stand-in for the one-row table and a mocked Authentik. */
class LoginSettingsControllerTest {

    private static final String ISSUER = "https://auth.example.com/application/o/naviseerr/";
    private static final OpenIdConfiguration OPEN_ID = new OpenIdConfiguration(ISSUER,
            "https://auth.example.com/application/o/authorize/", "https://auth.example.com/application/o/token/", null);

    private final LoginSettingsRepository repository = mock(LoginSettingsRepository.class);
    private final AuthentikClient authentik = mock(AuthentikClient.class);
    private final AtomicReference<LoginSettings> row = new AtomicReference<>();
    private final WebTestClient http = WebTestClient.bindToController(new LoginSettingsController(repository, authentik)).build();

    LoginSettingsControllerTest() {
        when(repository.find()).thenAnswer(i -> Mono.justOrEmpty(row.get()));
        when(repository.save(any(), any(), any())).thenAnswer(i -> {
            boolean enabled = row.get() != null && row.get().enabled();
            row.set(new LoginSettings(i.getArgument(0), i.getArgument(1), i.getArgument(2), enabled));
            return Mono.empty();
        });
        when(repository.setEnabled(anyBoolean())).thenAnswer(i -> {
            LoginSettings s = row.get();
            if (s != null) row.set(new LoginSettings(s.issuer(), s.clientId(), s.clientSecret(), i.getArgument(0)));
            return Mono.empty();
        });
        when(authentik.discover(any())).thenReturn(Mono.just(OPEN_ID));
    }

    @Test
    void nothingConnected_getIsEmpty_butAlreadyShowsTheRedirectUrl() {
        http.get().uri("/settings/login").header("Host", "nas:5056").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.issuer").isEmpty()
                .jsonPath("$.clientId").isEmpty()
                .jsonPath("$.clientSecretSet").isEqualTo(false)
                .jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.redirectUrl").isEqualTo("http://nas:5056/auth/authentik/callback")
                .jsonPath("$.clientSecret").doesNotExist();
    }

    @Test
    void theRedirectUrl_followsTheProxy_schemeHostAndTheStrippedPrefix() {
        http.get().uri("/settings/login")
                .header("Host", "music.example").header("X-Forwarded-Proto", "https").header("X-Forwarded-Prefix", "/api/")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.redirectUrl").isEqualTo("https://music.example/api/auth/authentik/callback");
        // Chained proxies list their schemes; the first one is the browser's.
        http.get().uri("/settings/login").header("Host", "music.example").header("X-Forwarded-Proto", "https, http").exchange()
                .expectBody().jsonPath("$.redirectUrl").isEqualTo("https://music.example/auth/authentik/callback");
    }

    @Test
    void saveAndTest_asksAuthentik_storesTheNormalisedAddress_andNeverEchoesTheSecret() {
        http.put().uri("/settings/login").header("Host", "nas:5056")
                .bodyValue(Map.of("issuer", "https://auth.example.com/application/o/naviseerr", "clientId", " abc ", "clientSecret", "s3cret"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.issuer").isEqualTo(ISSUER)
                .jsonPath("$.clientId").isEqualTo("abc")
                .jsonPath("$.clientSecretSet").isEqualTo(true)
                .jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.clientSecret").doesNotExist()
                .jsonPath("$.message").value(m -> assertTrue(m.toString().startsWith("Authentik answered at " + ISSUER + ". Sign in once"), m.toString()));

        verify(authentik).discover("https://auth.example.com/application/o/naviseerr");
        verify(repository).save(ISSUER, "abc", "s3cret");
        assertFalse(row.get().enabled(), "saving never turns the door on");
    }

    @Test
    void anEmptySecret_keepsTheStoredOne_andIsRefusedWhenNoneIsStored() {
        http.put().uri("/settings/login").bodyValue(Map.of("issuer", ISSUER, "clientId", "abc", "clientSecret", "")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo("Enter the Client secret.");
        verify(repository, never()).save(any(), any(), any());

        row.set(new LoginSettings(ISSUER, "abc", "old-secret", false));
        http.put().uri("/settings/login").bodyValue(Map.of("issuer", ISSUER, "clientId", "new-id")).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.clientId").isEqualTo("new-id").jsonPath("$.clientSecretSet").isEqualTo(true);
        assertEquals("old-secret", row.get().clientSecret());
    }

    @Test
    void blankFields_andAFailedTest_are400WithTheSentence_andNothingIsStored() {
        http.put().uri("/settings/login").bodyValue(Map.of("issuer", " ", "clientId", "abc", "clientSecret", "s")).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.message").isEqualTo("Enter the Authentik address.");
        http.put().uri("/settings/login").bodyValue(Map.of("issuer", ISSUER, "clientId", "", "clientSecret", "s")).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.message").isEqualTo("Enter the Client ID.");

        when(authentik.discover(any())).thenReturn(Mono.error(new LoginProblem(ISSUER + " answered 404. Check the slug at the end.")));
        http.put().uri("/settings/login").bodyValue(Map.of("issuer", ISSUER, "clientId", "abc", "clientSecret", "s")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo(ISSUER + " answered 404. Check the slug at the end.");
        verify(repository, never()).save(any(), any(), any());
        assertNull(row.get());
    }

    @Test
    void turnOff_setsEnabledFalse_andGetSaysSo() {
        row.set(new LoginSettings(ISSUER, "abc", "s", true));
        http.get().uri("/settings/login").exchange().expectBody().jsonPath("$.enabled").isEqualTo(true);

        http.delete().uri("/settings/login").exchange().expectStatus().isNoContent();

        verify(repository).setEnabled(false);
        http.get().uri("/settings/login").exchange().expectBody().jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.clientSecretSet").isEqualTo(true);
    }
}
