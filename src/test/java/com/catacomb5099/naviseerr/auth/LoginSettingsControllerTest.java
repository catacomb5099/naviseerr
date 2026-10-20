package com.catacomb5099.naviseerr.auth;

import com.catacomb5099.naviseerr.auth.AuthentikClient.OpenIdConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The Settings page's three calls, over an in-memory stand-in for the one-row table and a mocked Authentik.
 * Since 19-10-2026 22:40 a successful "Save and test" turns the login on by itself (the row is written with
 * {@code enabled = true}); a refused one changes nothing, so the door stays as it was.
 */
class LoginSettingsControllerTest {

    private static final String ISSUER = "https://auth.example.com/application/o/naviseerr/";
    private static final OpenIdConfiguration OPEN_ID = new OpenIdConfiguration(ISSUER,
            "https://auth.example.com/application/o/authorize/", "https://auth.example.com/application/o/token/", null);

    private final LoginSettingsRepository repository = mock(LoginSettingsRepository.class);
    private final AuthRepository sessions = mock(AuthRepository.class);
    private final AuthentikClient authentik = mock(AuthentikClient.class);
    private final AtomicReference<LoginSettings> row = new AtomicReference<>();
    private final WebTestClient http = WebTestClient.bindToController(new LoginSettingsController(repository, sessions, authentik)).build();

    LoginSettingsControllerTest() {
        when(repository.find()).thenAnswer(i -> Mono.justOrEmpty(row.get()));
        when(repository.save(any(), any(), any())).thenAnswer(i -> {
            row.set(new LoginSettings(i.getArgument(0), i.getArgument(1), i.getArgument(2), true));   // save = on, as the SQL does
            return Mono.empty();
        });
        when(repository.setEnabled(anyBoolean())).thenAnswer(i -> {
            LoginSettings s = row.get();
            if (s != null) row.set(new LoginSettings(s.issuer(), s.clientId(), s.clientSecret(), i.getArgument(0)));
            return Mono.empty();
        });
        when(authentik.discover(any())).thenReturn(Mono.just(OPEN_ID));
        when(authentik.checkClient(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(sessions.deleteAll()).thenReturn(Mono.empty());
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
    void saveAndTest_asksAuthentik_storesTheNormalisedAddress_turnsTheLoginOn_andNeverEchoesTheSecret() {
        http.put().uri("/settings/login").header("Host", "nas:5056")
                .bodyValue(Map.of("issuer", "https://auth.example.com/application/o/naviseerr", "clientId", " abc ", "clientSecret", "s3cret"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.issuer").isEqualTo(ISSUER)
                .jsonPath("$.clientId").isEqualTo("abc")
                .jsonPath("$.clientSecretSet").isEqualTo(true)
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.clientSecret").doesNotExist()
                .jsonPath("$.message").isEqualTo("Authentik answered at " + ISSUER
                        + " and accepted the Client ID, secret and Redirect URL. The login is on. Sign in to carry on.");

        verify(authentik).discover("https://auth.example.com/application/o/naviseerr");
        verify(authentik).checkClient(OPEN_ID, "abc", "s3cret", "http://nas:5056/auth/authentik/callback");
        verify(repository).save(ISSUER, "abc", "s3cret");
        assertTrue(row.get().enabled(), "Save and test is what turns the door on (19-10-2026 22:40)");
        verify(repository, never()).setEnabled(anyBoolean());
    }

    /** Behind the door already (the request carries a session): the sentence does not ask to sign in. */
    @Test
    void saveAndTest_whileSignedIn_saysTheLoginIsOn_withoutAskingToSignIn() {
        row.set(new LoginSettings(ISSUER, "abc", "old-secret", true));
        WebFilter signedIn = (exchange, chain) -> { exchange.getAttributes().put(AuthFilter.SUBJECT, "subject-42"); return chain.filter(exchange); };
        WebTestClient inside = WebTestClient.bindToController(new LoginSettingsController(repository, sessions, authentik)).webFilter(signedIn).build();

        inside.put().uri("/settings/login").header("Host", "nas:5056").bodyValue(Map.of("issuer", ISSUER, "clientId", "abc")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.message").value(m -> assertTrue(m.toString().endsWith("The login is on."), m.toString()));
    }

    /** A refused Save and test (here: Authentik unreachable or wrong) leaves the door as it was: off stays off. */
    @Test
    void aRefusedSaveAndTest_leavesTheLoginOff_andTheOldValuesStay() {
        row.set(new LoginSettings(ISSUER, "abc", "old-secret", false));
        when(authentik.checkClient(any(), any(), any(), any())).thenReturn(Mono.error(new LoginProblem(AuthentikClient.REFUSED)));

        http.put().uri("/settings/login").header("Host", "nas:5056")
                .bodyValue(Map.of("issuer", ISSUER, "clientId", "abc", "clientSecret", "typo")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo(AuthentikClient.REFUSED);

        verify(repository, never()).save(any(), any(), any());
        verify(repository, never()).setEnabled(anyBoolean());
        assertFalse(row.get().enabled(), "nothing was accepted, so nothing is enforced");
        assertEquals("old-secret", row.get().clientSecret());
        http.get().uri("/settings/login").exchange().expectBody().jsonPath("$.enabled").isEqualTo(false);
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
                .expectBody().jsonPath("$.clientId").isEqualTo("new-id").jsonPath("$.clientSecretSet").isEqualTo(true)
                .jsonPath("$.enabled").isEqualTo(true);
        assertEquals("old-secret", row.get().clientSecret());
        assertTrue(row.get().enabled(), "after Turn off, a Save and test with the secret left empty turns it back on");
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
    void aClientIdOrSecretAuthentikRefuses_is400WithTheSentence_andTheOldValuesStay() {
        row.set(new LoginSettings(ISSUER, "abc", "old-secret", true));
        when(authentik.checkClient(any(), any(), any(), any())).thenReturn(Mono.error(new LoginProblem(AuthentikClient.REFUSED)));

        http.put().uri("/settings/login").header("Host", "nas:5056")
                .bodyValue(Map.of("issuer", ISSUER, "clientId", "abc", "clientSecret", "typo")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo(AuthentikClient.REFUSED);

        verify(repository, never()).save(any(), any(), any());
        assertEquals("old-secret", row.get().clientSecret(), "the secret that works is still the one stored");
        assertTrue(row.get().enabled(), "and the login stays on");
    }

    @Test
    void turnOff_setsEnabledFalse_dropsEverySession_clearsTheCookie_andGetSaysSo() {
        row.set(new LoginSettings(ISSUER, "abc", "s", true));
        http.get().uri("/settings/login").exchange().expectBody().jsonPath("$.enabled").isEqualTo(true);

        http.delete().uri("/settings/login").exchange().expectStatus().isNoContent()
                .expectHeader().valueMatches("Set-Cookie", AuthFilter.COOKIE + "=;.*Max-Age=0.*");

        verify(repository).setEnabled(false);
        verify(sessions).deleteAll();
        http.get().uri("/settings/login").exchange().expectBody().jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.clientSecretSet").isEqualTo(true);
    }
}
