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
    private final SetupCode setupCode = new SetupCode(repository, true, "", "");
    private final WebTestClient http = WebTestClient.bindToController(new AuthController(repository, filter, new LoginThrottle(CLOCK), setupCode, CLOCK, 30))
            .webFilter(filter).build();
    /** The code the server "printed in its log" at start. */
    private final String code;

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
        setupCode.onReady();
        code = setupCode.current();
    }

    private String setup(String username, String password) {
        return http.post().uri("/auth/setup").bodyValue(Map.of("username", username, "password", password, "setupCode", code)).exchange()
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

        // Once the account exists the code is spent: a second attempt is a 409 with the right code, the old one, or any other.
        for (String tried : java.util.List.of(code, "made-up")) {
            http.post().uri("/auth/setup").bodyValue(Map.of("username", "bob", "password", "another one", "setupCode", tried)).exchange()
                    .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                    .expectBody().jsonPath("$.message").isEqualTo(AuthController.ACCOUNT_EXISTS);
        }
        assertNull(setupCode.current(), "void after use");
        assertEquals("Alex", account.get().username());
    }

    @Test
    void setup_withTheWrongCode_is401_fiveOfThemIs429_andNoAccountIsMade() {
        for (int i = 0; i < 5; i++) {
            http.post().uri("/auth/setup").bodyValue(Map.of("username", "alex", "password", "correct horse", "setupCode", "guess-" + i)).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().doesNotExist("Set-Cookie")
                    .expectBody().jsonPath("$.message").isEqualTo(AuthController.WRONG_CODE);
        }
        // The same throttle as the login: the sixth try is refused before the code is even looked at, the right one included.
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "alex", "password", "correct horse", "setupCode", code)).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody().jsonPath("$.message").isEqualTo(LoginThrottle.TOO_MANY);
        assertNull(account.get());
        assertNotNull(setupCode.current(), "still live: nobody used it");
    }

    @Test
    void setup_theCodeIsForgivingAboutCaseAndDashes_andAnOldWebAppWithoutOneIs400() {
        // A web app from before the code sends none: told to update, nothing is made, nothing is counted.
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "alex", "password", "correct horse")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo(AuthController.OLD_CLIENT);
        assertNull(account.get());

        http.post().uri("/auth/setup").bodyValue(Map.of("username", "alex", "password", "correct horse", "setupCode", " " + code.toUpperCase().replace("-", ""))).exchange()
                .expectStatus().isCreated();
    }

    @Test
    void setup_refusesABlankOrLongName_andAShortOrOver72BytePassword() {
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "  ", "password", "long enough", "setupCode", code)).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.message").isEqualTo("Username: 1 to 30 characters.");
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "x".repeat(31), "password", "long enough", "setupCode", code)).exchange()
                .expectStatus().isBadRequest();
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "alex", "password", "short", "setupCode", code)).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.message").isEqualTo("Password: at least 8 characters.");
        // bcrypt stops at 72 bytes and spring-security-crypto throws past it: a 400 with words, not a 500.
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "alex", "password", "x".repeat(73), "setupCode", code)).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.message").isEqualTo("Password: at most 72 characters.");
        assertNull(account.get());
        assertNotNull(setupCode.current(), "a refused name or password does not spend the code");
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
                Map.of("username", "nobody", "password", "correct horse"),
                Map.of("username", "alex", "password", "x".repeat(80)))) {   // over 72 bytes: still a 401, never a 500
            http.post().uri("/auth/login").bodyValue(wrong).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().doesNotExist("Set-Cookie")
                    .expectBody().jsonPath("$.message").isEqualTo(AuthController.WRONG_LOGIN);
        }
    }

    @Test
    void sixWrongPasswords_theSixthIs429_andSoIsTheRightOneUntilTheWindowPasses() {
        setup("alex", "correct horse");

        for (int i = 0; i < 5; i++) {
            http.post().uri("/auth/login").bodyValue(Map.of("username", "alex", "password", "guess " + i)).exchange()
                    .expectStatus().isUnauthorized();
        }
        http.post().uri("/auth/login").bodyValue(Map.of("username", "Alex", "password", "guess 6")).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody().jsonPath("$.message").isEqualTo(LoginThrottle.TOO_MANY);
        // The fixed clock never lets the window pass, so even the right password is refused here.
        http.post().uri("/auth/login").bodyValue(Map.of("username", "alex", "password", "correct horse")).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Another name is untouched.
        http.post().uri("/auth/login").bodyValue(Map.of("username", "bob", "password", "whatever")).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void thirtyWrongLoginsUnderMadeUpNames_thenEveryoneWaitsAMinute_theRightPasswordAndSetupIncluded() {
        setup("alex", "correct horse");
        for (int i = 0; i < 30; i++) {
            http.post().uri("/auth/login").bodyValue(Map.of("username", "bot-" + i, "password", "guess")).exchange()
                    .expectStatus().isUnauthorized();
        }
        http.post().uri("/auth/login").bodyValue(Map.of("username", "alex", "password", "correct horse")).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().jsonPath("$.message").isEqualTo(LoginThrottle.TOO_MANY_FOR_EVERYONE);
        http.post().uri("/auth/setup").bodyValue(Map.of("username", "bob", "password", "another one", "setupCode", "x")).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody().jsonPath("$.message").isEqualTo(LoginThrottle.TOO_MANY_FOR_EVERYONE);
    }

    @Test
    void theLoggedAddress_isTheForwardedClientBehindAPrivateProxy_andThePeerItselfOtherwise() throws Exception {
        // The install: Caddy on the Docker network passes the real client on in X-Forwarded-For.
        assertEquals("203.0.113.9", AuthController.clientAddress(request("172.18.0.3", "203.0.113.9, 172.18.0.1")));
        assertEquals("203.0.113.9", AuthController.clientAddress(request("127.0.0.1", "203.0.113.9")));
        assertEquals("203.0.113.9", AuthController.clientAddress(request("fd12:3456::1", "203.0.113.9")));
        // The server reached from the internet directly: the header is the caller's word, never believed.
        assertEquals("198.51.100.7", AuthController.clientAddress(request("198.51.100.7", "10.0.0.1")));
        assertEquals("2001:db8:0:0:0:0:0:7", AuthController.clientAddress(request("2001:db8::7", "10.0.0.1")), "Java prints the long form");
        // No header: the peer.
        assertEquals("192.168.1.20", AuthController.clientAddress(request("192.168.1.20", null)));
        assertEquals("172.18.0.3", AuthController.clientAddress(request("172.18.0.3", "  ")));
        assertEquals("unknown", AuthController.clientAddress(org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/auth/login").build()));

        for (String p : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.1.1", "::1", "fc00::1", "fdff::1", "fe80::1"}) {
            assertTrue(AuthController.isPrivate(java.net.InetAddress.getByName(p)), p);
        }
        for (String p : new String[] {"8.8.8.8", "172.32.0.1", "203.0.113.9", "2001:db8::1", "100.64.0.1"}) {
            assertFalse(AuthController.isPrivate(java.net.InetAddress.getByName(p)), p);
        }
    }

    private static org.springframework.http.server.reactive.ServerHttpRequest request(String peer, String forwardedFor) throws Exception {
        var b = org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/auth/login")
                .remoteAddress(new java.net.InetSocketAddress(java.net.InetAddress.getByName(peer), 40000));
        if (forwardedFor != null) b = b.header("X-Forwarded-For", forwardedFor);
        return b.build();
    }

    @Test
    void logout_dropsTheSession_andExpiresTheCookie() {
        String id = sessionId(setup("alex", "correct horse"));

        String cleared = http.post().uri("/auth/logout").cookie(AuthFilter.COOKIE, id).exchange()
                .expectStatus().isNoContent()
                .returnResult(Void.class).getResponseHeaders().getFirst("Set-Cookie");

        assertTrue(cleared.contains("Max-Age=0"), cleared);
        http.get().uri("/downloads/active").cookie(AuthFilter.COOKIE, id).exchange().expectStatus().isUnauthorized();
        // Logout is an open path: with no cookie, or a dead one, the browser still gets told to drop it.
        http.post().uri("/auth/logout").cookie(AuthFilter.COOKIE, id).exchange()
                .expectStatus().isNoContent().expectHeader().valueMatches("Set-Cookie", ".*Max-Age=0.*");
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
        WebTestClient open = WebTestClient.bindToController(new AuthController(repository, off, new LoginThrottle(CLOCK), setupCode, CLOCK, 30)).webFilter(off).build();

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
