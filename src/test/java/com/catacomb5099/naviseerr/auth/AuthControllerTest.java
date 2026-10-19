package com.catacomb5099.naviseerr.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The four endpoints behind the real {@link AuthFilter}, over an in-memory stand-in for the repository. */
class AuthControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    private final AuthRepository repository = mock(AuthRepository.class);
    private final AtomicReference<UserRow> account = new AtomicReference<>();
    private final Map<String, UserRow> sessions = new HashMap<>();
    private final AuthFilter filter = new AuthFilter(repository, CLOCK, true);
    private final WebTestClient http = WebTestClient.bindToController(new AuthController(repository, filter, CLOCK, 30))
            .webFilter(filter).build();

    AuthControllerTest() {
        when(repository.anyUser()).thenAnswer(i -> Mono.just(account.get() != null));
        when(repository.createFirstUser(any(), any(), any())).thenAnswer(i -> {
            if (account.get() != null) return Mono.just(false);
            account.set(new UserRow(i.getArgument(0), i.getArgument(1), i.getArgument(2), "ADMIN"));
            return Mono.just(true);
        });
        when(repository.findByUsername(any())).thenAnswer(i -> {
            UserRow u = account.get();
            return u != null && u.username().equalsIgnoreCase(i.getArgument(0)) ? Mono.just(u) : Mono.empty();
        });
        when(repository.createSession(any(), any(), any())).thenAnswer(i -> { sessions.put(i.getArgument(0), account.get()); return Mono.empty(); });
        when(repository.findBySession(any(), any())).thenAnswer(i -> Mono.justOrEmpty(sessions.get(i.<String>getArgument(0))));
        when(repository.deleteSession(any())).thenAnswer(i -> { sessions.remove(i.<String>getArgument(0)); return Mono.empty(); });
        when(repository.deleteExpired(any())).thenReturn(Mono.empty());
    }

    private String setup(String username, String password) {
        return http.post().uri("/auth/setup").bodyValue(Map.of("username", username, "password", password)).exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.username").isEqualTo(username)
                .returnResult().getResponseHeaders().getFirst("Set-Cookie");
    }

    private static String sessionId(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    @Test
    void beforeAnyAccount_sessionSaysSetupRequired_andDataPathsAre401() {
        http.get().uri("/auth/session").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authRequired").isEqualTo(true)
                .jsonPath("$.setupRequired").isEqualTo(true)
                .jsonPath("$.authenticated").isEqualTo(false)
                .jsonPath("$.username").isEmpty();

        http.get().uri("/downloads/active").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody().jsonPath("$.message").isEqualTo("Log in to Naviseerr");
    }

    @Test
    void setup_makesTheAccountOnce_withTheCookieFlags_then409() {
        String cookie = setup("Alex", "correct horse");

        assertTrue(cookie.startsWith(AuthFilter.COOKIE + "="), cookie);
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax") && cookie.contains("Path=/"), cookie);
        assertTrue(cookie.contains("Max-Age=2592000"), "30 days: " + cookie);
        assertFalse(cookie.contains("Secure"), "plain HTTP: " + cookie);
        assertEquals(43, sessionId(cookie).length(), "32 random bytes, base64url, no padding");

        http.post().uri("/auth/setup").bodyValue(Map.of("username", "bob", "password", "another one")).exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.message").isEqualTo(AuthController.ACCOUNT_EXISTS);
        assertEquals("Alex", account.get().username());
    }

    @Test
    void setup_refusesABlankOrLongName_andAShortPassword() {
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "  ", "password", "long enough")).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.message").isEqualTo("Username: 1 to 30 characters.");
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "x".repeat(31), "password", "long enough")).exchange()
                .expectStatus().isBadRequest();
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "alex", "password", "short")).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.message").isEqualTo("Password: at least 8 characters.");
        assertNull(account.get());
    }

    @Test
    void login_withTheRightPassword_startsASession_theCookieSaysWho_anyCaseOfTheName() {
        setup("Alex", "correct horse");

        String cookie = http.post().uri("/auth/login").bodyValue(Map.of("username", "ALEX", "password", "correct horse")).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.username").isEqualTo("Alex")
                .returnResult().getResponseHeaders().getFirst("Set-Cookie");

        http.get().uri("/auth/session").cookie(AuthFilter.COOKIE, sessionId(cookie)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authenticated").isEqualTo(true)
                .jsonPath("$.setupRequired").isEqualTo(false)
                .jsonPath("$.username").isEqualTo("Alex");
        http.get().uri("/auth/session").cookie(AuthFilter.COOKIE, "made-up").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.authenticated").isEqualTo(false).jsonPath("$.setupRequired").isEqualTo(false);
    }

    @Test
    void login_wrongPasswordOrUnknownName_is401_withTheSameWords() {
        setup("alex", "correct horse");

        for (Map<String, String> wrong : java.util.List.of(
                Map.of("username", "alex", "password", "wrong horse"),
                Map.of("username", "nobody", "password", "correct horse"))) {
            http.post().uri("/auth/login").bodyValue(wrong).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().doesNotExist("Set-Cookie")
                    .expectBody().jsonPath("$.message").isEqualTo(AuthController.WRONG_LOGIN);
        }
    }

    @Test
    void logout_dropsTheSession_andExpiresTheCookie() {
        String id = sessionId(setup("alex", "correct horse"));

        String cleared = http.post().uri("/auth/logout").cookie(AuthFilter.COOKIE, id).exchange()
                .expectStatus().isNoContent()
                .returnResult(Void.class).getResponseHeaders().getFirst("Set-Cookie");

        assertTrue(cleared.contains("Max-Age=0"), cleared);
        http.get().uri("/downloads/active").cookie(AuthFilter.COOKIE, id).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void behindHttps_theCookieIsSecure() {
        setup("alex", "correct horse");

        String cookie = http.post().uri("/auth/login").header("X-Forwarded-Proto", "https")
                .bodyValue(Map.of("username", "alex", "password", "correct horse")).exchange()
                .expectStatus().isOk()
                .returnResult(Void.class).getResponseHeaders().getFirst("Set-Cookie");

        assertTrue(cookie.contains("Secure"), cookie);
    }

    @Test
    void authOff_sessionSaysSo_andNothingIs401() {
        AuthFilter off = new AuthFilter(repository, CLOCK, false);
        WebTestClient open = WebTestClient.bindToController(new AuthController(repository, off, CLOCK, 30)).webFilter(off).build();

        open.get().uri("/auth/session").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authRequired").isEqualTo(false)
                .jsonPath("$.setupRequired").isEqualTo(false)
                .jsonPath("$.authenticated").isEqualTo(true)
                .jsonPath("$.username").isEmpty();
        // Past the filter; only this test client has no handler for it, hence 404 rather than 401.
        open.get().uri("/downloads/active").exchange().expectStatus().isNotFound();
    }
}
