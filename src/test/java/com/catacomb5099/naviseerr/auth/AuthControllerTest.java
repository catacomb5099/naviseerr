package com.catacomb5099.naviseerr.auth;

import com.catacomb5099.naviseerr.auth.AuthentikClient.OpenIdConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Both legs of the sign-in and the three other routes, behind the real {@link AuthFilter}, over in-memory
 * stand-ins for the two tables and a mocked Authentik (the HTTP boundary is {@link AuthentikClientTest}'s).
 * The door is on from "Save and test" (19-10-2026 22:40); the sign-in only opens it for one browser.
 */
class AuthControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
    private static final String ISSUER = "https://auth.example.com/application/o/naviseerr/";
    private static final OpenIdConfiguration OPEN_ID = new OpenIdConfiguration(ISSUER,
            "https://auth.example.com/application/o/authorize/", "https://auth.example.com/application/o/token/",
            ISSUER + "end-session/");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LoginSettingsRepository settings = mock(LoginSettingsRepository.class);
    private final AuthRepository sessions = mock(AuthRepository.class);
    private final AuthentikClient authentik = mock(AuthentikClient.class);
    private final AtomicReference<LoginSettings> row = new AtomicReference<>();
    private final Map<String, String> sessionRows = new HashMap<>();
    private final AuthFilter filter = new AuthFilter(settings, sessions, CLOCK);
    private final AuthController controller = new AuthController(settings, sessions, authentik, JSON, CLOCK, 30,
            List.of("http://localhost:5173"));
    private final WebTestClient http = WebTestClient
            .bindToController(controller, new LoginSettingsController(settings, sessions, authentik)).webFilter(filter).build();

    AuthControllerTest() {
        when(settings.find()).thenAnswer(i -> Mono.justOrEmpty(row.get()));
        when(settings.isEnabled()).thenAnswer(i -> Mono.just(row.get() != null && row.get().enabled()));
        when(settings.setEnabled(anyBoolean())).thenAnswer(i -> {
            LoginSettings s = row.get();
            if (s != null) row.set(new LoginSettings(s.issuer(), s.clientId(), s.clientSecret(), i.getArgument(0)));
            return Mono.empty();
        });
        when(settings.save(any(), any(), any())).thenAnswer(i -> {
            row.set(new LoginSettings(i.getArgument(0), i.getArgument(1), i.getArgument(2), true));   // save = on, as the SQL does
            return Mono.empty();
        });
        when(authentik.checkClient(any(), any(), any(), any())).thenReturn(Mono.empty());
        when(sessions.createSession(any(), any(), any())).thenAnswer(i -> { sessionRows.put(i.getArgument(0), i.getArgument(1)); return Mono.empty(); });
        when(sessions.findBySession(any(), any())).thenAnswer(i -> Mono.justOrEmpty(sessionRows.get(i.<String>getArgument(0))));
        when(sessions.deleteSession(any())).thenAnswer(i -> { sessionRows.remove(i.<String>getArgument(0)); return Mono.empty(); });
        when(sessions.deleteExpired(any())).thenReturn(Mono.empty());
        when(sessions.deleteAll()).thenAnswer(i -> { sessionRows.clear(); return Mono.empty(); });
        when(authentik.discover(any())).thenReturn(Mono.just(OPEN_ID));
    }

    private void connected(boolean enabled) {
        row.set(new LoginSettings(ISSUER, "client-1", "s3cret", enabled));
    }

    // --- helpers around the two cookies ---------------------------------------------------------

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private static AuthController.LoginAttempt attemptIn(String loginCookie) {
        return JSON.readValue(Base64.getUrlDecoder().decode(cookieValue(loginCookie)), AuthController.LoginAttempt.class);
    }

    private static String jwtFor(String subject, String nonce, String issuer, String aud) {
        return IdTokenTest.jwt("{\"iss\":\"" + issuer + "\",\"sub\":\"" + subject + "\",\"aud\":\"" + aud + "\",\"exp\":"
                + CLOCK.instant().plusSeconds(300).getEpochSecond() + ",\"nonce\":\"" + nonce + "\"}");
    }

    /** Leg one through the API; answers the login cookie header. */
    private String start(String to, String... headers) {
        WebTestClient.RequestHeadersSpec<?> spec = http.get().uri("/auth/authentik/start" + (to == null ? "" : "?to=" + to)).header("Host", "nas:5056");
        for (int i = 0; i < headers.length; i += 2) spec = spec.header(headers[i], headers[i + 1]);
        return spec.exchange().expectStatus().isFound().returnResult(Void.class).getResponseHeaders().getFirst("Set-Cookie");
    }

    /** Leg two with a token Authentik "issued" for this attempt; answers the whole result for its headers. */
    private WebTestClient.ResponseSpec callback(String loginCookie, String state, String... headers) {
        AuthController.LoginAttempt attempt = attemptIn(loginCookie);
        when(authentik.exchangeCode(eq(OPEN_ID), eq("client-1"), eq("s3cret"), eq("code-1"), eq(attempt.redirectUri()), eq(attempt.verifier())))
                .thenReturn(Mono.just(jwtFor("subject-42", attempt.nonce(), ISSUER, "client-1")));
        WebTestClient.RequestHeadersSpec<?> spec = http.get().uri("/auth/authentik/callback?code=code-1&state=" + state)
                .header("Host", "nas:5056").cookie(AuthController.LOGIN_COOKIE, cookieValue(loginCookie));
        for (int i = 0; i < headers.length; i += 2) spec = spec.header(headers[i], headers[i + 1]);
        return spec.exchange();
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test
    void nothingConnected_sessionSaysOpen_andEverythingPassesTheFilter() {
        http.get().uri("/auth/session").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.mode").isEqualTo("open").jsonPath("$.authenticated").doesNotExist();
        // Past the filter; only this test client has no handler for it, hence 404 rather than 401.
        http.get().uri("/downloads/active").exchange().expectStatus().isNotFound();
        // The one button leads here when nothing is connected: a page with the sentence and a way back, not a blank tab.
        http.get().uri("/auth/authentik/start").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectBody(String.class).value(body -> {
                    assertTrue(body.contains(AuthController.NOT_CONNECTED), body);
                    assertTrue(body.contains("href=\"/\""), body);
                });
    }

    @Test
    void start_whileAuthentikDoesNotAnswer_isA502PageWithTheSentence_andAWayBackToWhereYouWere() {
        connected(true);
        when(authentik.discover(any())).thenReturn(Mono.error(new LoginProblem(
                "Could not reach auth.example.com: connection refused. Is Authentik running, and can this server reach it?")));
        http.get().uri("/auth/authentik/start?to=http://nas:5056/downloads").header("Host", "nas:5056").exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectBody(String.class).value(body -> {
                    assertTrue(body.contains("Could not reach auth.example.com: connection refused."), body);
                    assertTrue(body.contains("href=\"http://nas:5056/downloads\""), body);
                });
    }

    @Test
    void start_sendsTheBrowserToAuthentik_withPkce_andRemembersTheAttemptInAnHttpOnlyCookie() {
        connected(false);

        String location = http.get().uri("/auth/authentik/start?to=http://nas:5056/downloads").header("Host", "nas:5056").exchange()
                .expectStatus().isFound()
                .returnResult(Void.class).getResponseHeaders().getLocation().toString();
        String loginCookie = start("http://nas:5056/downloads");
        AuthController.LoginAttempt attempt = attemptIn(loginCookie);

        assertTrue(location.startsWith(OPEN_ID.authorizationEndpoint() + "?"), location);
        Map<String, String> query = new HashMap<>();
        UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams().forEach((k, v) -> query.put(k, v.getFirst()));
        assertEquals("code", query.get("response_type"));
        assertEquals("client-1", query.get("client_id"));
        assertEquals("openid", query.get("scope"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals("http://nas:5056/auth/authentik/callback", query.get("redirect_uri"), "from the Host header, no prefix without a proxy");
        assertEquals(43, query.get("state").length());

        assertTrue(loginCookie.startsWith(AuthController.LOGIN_COOKIE + "="), loginCookie);
        assertTrue(loginCookie.contains("HttpOnly") && loginCookie.contains("SameSite=Lax") && loginCookie.contains("Max-Age=600"), loginCookie);
        assertEquals("http://nas:5056/auth/authentik/callback", attempt.redirectUri());
        assertEquals("http://nas:5056/downloads", attempt.to());
        assertEquals(43, attempt.verifier().length());
        assertEquals(Pkce.challenge(attempt.verifier()).length(), 43);
        assertFalse(row.get().enabled(), "starting a sign-in turns nothing on");
    }

    @Test
    void start_behindTheWebAppsProxy_usesTheForwardedSchemeAndPrefix() {
        connected(false);
        String loginCookie = start(null, "X-Forwarded-Proto", "https", "X-Forwarded-Prefix", "/api");
        assertEquals("https://nas:5056/api/auth/authentik/callback", attemptIn(loginCookie).redirectUri());
        assertTrue(loginCookie.contains("Secure"), loginCookie);
    }

    @Test
    void theReturnAddress_isKeptForOurOwnAddressOrAListedOrigin_elseTheRoot() {
        connected(false);
        assertEquals("http://nas:5056/downloads", attemptIn(start("http://nas:5056/downloads")).to());
        assertEquals("http://NAS:5056/settings", attemptIn(start("http://NAS:5056/settings")).to(), "case does not matter");
        assertEquals("http://localhost:5173/downloads", attemptIn(start("http://localhost:5173/downloads")).to(), "the listed dev origin");
        assertEquals("/downloads", attemptIn(start("/downloads")).to(), "a plain path");
        assertEquals("/", attemptIn(start("https://evil.example/steal")).to());
        assertEquals("/", attemptIn(start("http://nas:5057/other-port")).to());
        assertEquals("/", attemptIn(start("//evil.example/x")).to());
        assertEquals("/", attemptIn(start("javascript:alert(1)")).to());
        assertEquals("/", attemptIn(start(null)).to());
    }

    @Test
    void anAddressJavaCannotParse_landsOnTheRoot_insteadOfA500AfterTheSignIn() {
        connected(false);
        // A backslash, which browsers read as a slash ("/\\evil.example" leaves the site); a character URI rejects.
        for (String odd : List.of("/\\evil.example", "/plain?x=a|b", "http://nas:5056/a b")) {
            String loginCookie = http.get().uri(b -> b.path("/auth/authentik/start").queryParam("to", odd).build())
                    .header("Host", "nas:5056").exchange()
                    .expectStatus().isFound().returnResult(Void.class).getResponseHeaders().getFirst("Set-Cookie");
            assertEquals("/", attemptIn(loginCookie).to(), odd);
        }
    }

    /** Open until Save and test: a data route passes; from the PUT on, the very next request without a session is 401. */
    @Test
    void saveAndTest_shutsTheDoorAtOnce_theNextRequestWithoutASessionIs401_andTheSignInOpensItForThisBrowser() {
        http.get().uri("/downloads/active").exchange().expectStatus().isNotFound();   // past the filter; no handler bound here

        http.put().uri("/settings/login").header("Host", "nas:5056")
                .bodyValue(Map.of("issuer", ISSUER, "clientId", "client-1", "clientSecret", "s3cret")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.message").value(m -> assertTrue(m.toString().endsWith("The login is on. Sign in to carry on."), m.toString()));

        http.get().uri("/downloads/active").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.message").isEqualTo("Log in to Naviseerr");
        http.get().uri("/auth/session").exchange().expectBody()
                .jsonPath("$.mode").isEqualTo("authentik").jsonPath("$.authenticated").isEqualTo(false);
        http.put().uri("/settings/login").bodyValue(Map.of("issuer", ISSUER, "clientId", "client-1")).exchange()
                .expectStatus().isUnauthorized();
        http.delete().uri("/settings/login").exchange().expectStatus().isUnauthorized();

        String loginCookie = start("http://nas:5056/settings");
        String session = cookieValue(callback(loginCookie, attemptIn(loginCookie).state()).expectStatus().isFound()
                .expectHeader().location("http://nas:5056/settings")
                .returnResult(Void.class).getResponseHeaders().get("Set-Cookie").getFirst());
        http.get().uri("/downloads/active").cookie(AuthFilter.COOKIE, session).exchange().expectStatus().isNotFound();
        // From inside, a Save and test says the login is on and does not ask to sign in again.
        http.put().uri("/settings/login").header("Host", "nas:5056").cookie(AuthFilter.COOKIE, session)
                .bodyValue(Map.of("issuer", ISSUER, "clientId", "client-1")).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.message").value(m -> assertTrue(m.toString().endsWith("Redirect URL. The login is on."), m.toString()));
        verify(settings, never()).setEnabled(anyBoolean());
    }

    @Test
    void callback_withTheRightState_opensASession_setsTheCookie_andLandsOnTo() {
        connected(true);   // on since Save and test
        String loginCookie = start("http://nas:5056/downloads");
        String state = attemptIn(loginCookie).state();

        List<String> setCookies = callback(loginCookie, state)
                .expectStatus().isFound()
                .expectHeader().location("http://nas:5056/downloads")
                .returnResult(Void.class).getResponseHeaders().get("Set-Cookie");

        assertEquals(2, setCookies.size(), setCookies.toString());
        String session = setCookies.stream().filter(c -> c.startsWith(AuthFilter.COOKIE + "=")).findFirst().orElseThrow();
        String cleared = setCookies.stream().filter(c -> c.startsWith(AuthController.LOGIN_COOKIE + "=")).findFirst().orElseThrow();
        assertTrue(session.contains("HttpOnly") && session.contains("SameSite=Lax") && session.contains("Path=/"), session);
        assertTrue(session.contains("Max-Age=2592000"), "30 days: " + session);
        assertFalse(session.contains("Secure"), "plain HTTP: " + session);
        assertEquals(43, cookieValue(session).length());
        assertTrue(cleared.contains("Max-Age=0"), cleared);
        assertEquals("subject-42", sessionRows.get(cookieValue(session)), "the row holds Authentik's subject, nothing else");
        verify(settings, never()).setEnabled(anyBoolean());   // the switch is Save and test's; the sign-in leaves it alone
        verify(sessions).deleteExpired(CLOCK.instant());

        // The door, shut since Save and test, stays shut for everyone else and opens for this cookie.
        http.get().uri("/auth/session").exchange().expectBody()
                .jsonPath("$.mode").isEqualTo("authentik").jsonPath("$.authenticated").isEqualTo(false);
        http.get().uri("/auth/session").cookie(AuthFilter.COOKIE, cookieValue(session)).exchange().expectBody()
                .jsonPath("$.mode").isEqualTo("authentik").jsonPath("$.authenticated").isEqualTo(true);
        http.get().uri("/downloads/active").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody().jsonPath("$.message").isEqualTo("Log in to Naviseerr");
        http.get().uri("/downloads/active").cookie(AuthFilter.COOKIE, cookieValue(session)).exchange().expectStatus().isNotFound();
    }

    @Test
    void callback_behindHttps_marksBothCookiesSecure() {
        connected(false);
        String loginCookie = start(null, "X-Forwarded-Proto", "https");
        List<String> setCookies = callback(loginCookie, attemptIn(loginCookie).state(), "X-Forwarded-Proto", "https")
                .expectStatus().isFound().returnResult(Void.class).getResponseHeaders().get("Set-Cookie");
        setCookies.forEach(c -> assertTrue(c.contains("Secure"), c));
    }

    @Test
    void callback_withAWrongOrMissingState_isRefused_noSession_nothingChanged() {
        connected(true);
        String loginCookie = start("http://nas:5056/downloads");

        http.get().uri("/auth/authentik/callback?code=c&state=forged").header("Host", "nas:5056")
                .cookie(AuthController.LOGIN_COOKIE, cookieValue(loginCookie)).exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectHeader().valueMatches("Set-Cookie", AuthController.LOGIN_COOKIE + "=;.*Max-Age=0.*")
                .expectBody(String.class).value(body -> {
                    assertTrue(body.contains(AuthController.STALE), body);
                    assertTrue(body.contains("href=\"http://nas:5056/downloads\""), "a way back: " + body);
                });
        http.get().uri("/auth/authentik/callback?code=c&state=" + attemptIn(loginCookie).state()).header("Host", "nas:5056").exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> assertTrue(body.contains("href=\"/\""), "no cookie: back to the root"));

        assertTrue(sessionRows.isEmpty());
        verify(settings, never()).setEnabled(anyBoolean());
        verify(authentik, never()).exchangeCode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void callback_authentikSaidNo_orTheTokenIsWrong_isAPlainPage_notASession() {
        connected(true);
        String loginCookie = start(null);
        String state = attemptIn(loginCookie).state();

        http.get().uri("/auth/authentik/callback?error=access_denied&state=" + state).header("Host", "nas:5056")
                .cookie(AuthController.LOGIN_COOKIE, cookieValue(loginCookie)).exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> assertTrue(body.contains(AuthController.DENIED), body));

        // Authentik's own words are shown, escaped.
        http.get().uri("/auth/authentik/callback?error=server_error&error_description=<b>x</b>&state=" + state).header("Host", "nas:5056")
                .cookie(AuthController.LOGIN_COOKIE, cookieValue(loginCookie)).exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> {
                    assertTrue(body.contains("server_error: &lt;b&gt;x&lt;/b&gt;"), body);
                    assertFalse(body.contains("<b>x</b>"), body);
                });

        // The token endpoint refuses (wrong secret, say).
        when(authentik.exchangeCode(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new LoginProblem("Authentik refused the sign-in (invalid_client). Check the Client ID and Client secret in Settings.")));
        http.get().uri("/auth/authentik/callback?code=c&state=" + state).header("Host", "nas:5056")
                .cookie(AuthController.LOGIN_COOKIE, cookieValue(loginCookie)).exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> assertTrue(body.contains("Authentik refused the sign-in (invalid_client)"), body));

        // A token for another sign-in (wrong nonce) or another provider (wrong issuer).
        when(authentik.exchangeCode(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(jwtFor("subject-42", "other-nonce", ISSUER, "client-1")));
        http.get().uri("/auth/authentik/callback?code=c&state=" + state).header("Host", "nas:5056")
                .cookie(AuthController.LOGIN_COOKIE, cookieValue(loginCookie)).exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> assertTrue(body.contains("did not match this sign-in (the nonce)"), body));

        assertTrue(sessionRows.isEmpty());
        verify(settings, never()).setEnabled(anyBoolean());
    }

    @Test
    void logout_dropsTheSession_andExpiresTheCookie_evenADeadOne() {
        connected(true);
        String loginCookie = start(null);
        String session = cookieValue(callback(loginCookie, attemptIn(loginCookie).state())
                .returnResult(Void.class).getResponseHeaders().get("Set-Cookie").getFirst());

        http.post().uri("/auth/logout").cookie(AuthFilter.COOKIE, session).exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueMatches("Set-Cookie", AuthFilter.COOKIE + "=;.*Max-Age=0.*");

        assertTrue(sessionRows.isEmpty());
        assertTrue(row.get().enabled(), "logging out leaves the door on");
        http.get().uri("/downloads/active").cookie(AuthFilter.COOKIE, session).exchange().expectStatus().isUnauthorized();
        // Logout is an open path: with a dead cookie the browser still gets told to drop it.
        http.post().uri("/auth/logout").cookie(AuthFilter.COOKIE, session).exchange()
                .expectStatus().isNoContent().expectHeader().valueMatches("Set-Cookie", ".*Max-Age=0.*");
        // No cookie at all (a cross-site form POST, which Lax keeps the cookie from): nothing to clear, so nothing is sent.
        http.post().uri("/auth/logout").exchange()
                .expectStatus().isNoContent().expectHeader().doesNotExist("Set-Cookie");
    }

    @Test
    void turnOff_needsASession_thenOpensTheAppAgain_andLogsEveryoneOut() {
        connected(true);
        String loginCookie = start(null);
        String session = cookieValue(callback(loginCookie, attemptIn(loginCookie).state())
                .returnResult(Void.class).getResponseHeaders().get("Set-Cookie").getFirst());

        http.delete().uri("/settings/login").exchange().expectStatus().isUnauthorized();
        assertTrue(row.get().enabled());

        http.delete().uri("/settings/login").cookie(AuthFilter.COOKIE, session).exchange().expectStatus().isNoContent();

        assertFalse(row.get().enabled());
        assertTrue(sessionRows.isEmpty());
        http.get().uri("/auth/session").exchange().expectBody().jsonPath("$.mode").isEqualTo("open");
        http.get().uri("/downloads/active").exchange().expectStatus().isNotFound();
        assertEquals(ISSUER, row.get().issuer(), "the settings stay for the next Save and test, which turns it back on");
    }

    @Test
    void aUnicodeOrOddReturnAddress_neverBreaksTheCookie() {
        connected(false);
        String loginCookie = start("http://nas:5056/search?q=caf%C3%A9%20%22quoted%22");
        assertEquals("http://nas:5056/search?q=caf%C3%A9%20%22quoted%22", attemptIn(loginCookie).to());
        assertFalse(cookieValue(loginCookie).contains("\""), "base64url: cookie-safe whatever the address holds");
        assertEquals(HttpStatus.FOUND, callback(loginCookie, attemptIn(loginCookie).state())
                .returnResult(Void.class).getStatus());
    }
}
