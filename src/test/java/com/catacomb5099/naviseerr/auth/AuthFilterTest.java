package com.catacomb5099.naviseerr.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    private final LoginSettingsRepository settings = mock(LoginSettingsRepository.class);
    private final AuthRepository sessions = mock(AuthRepository.class);
    private final AuthFilter filter = new AuthFilter(settings, sessions, CLOCK);
    private final AtomicBoolean reachedHandler = new AtomicBoolean();
    private final WebFilterChain chain = exchange -> { reachedHandler.set(true); return Mono.empty(); };

    private void loginOn(boolean on) {
        when(settings.isEnabled()).thenReturn(Mono.just(on));
    }

    @Test
    void nothingConnected_passesEverything_andAsksTheSessionsTableNothing() {
        loginOn(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/download/song/x"));

        filter.filter(exchange, chain).block();

        assertTrue(reachedHandler.get());
        assertEquals(Boolean.FALSE, exchange.getAttribute(AuthFilter.ENABLED));
        verifyNoInteractions(sessions);
    }

    @Test
    void loginOn_aDataPathWithoutACookie_is401Json_andNeverReachesTheHandler() {
        loginOn(true);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/downloads/active"));

        filter.filter(exchange, chain).block();

        assertFalse(reachedHandler.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
        assertEquals(AuthFilter.UNAUTHORIZED_BODY, exchange.getResponse().getBodyAsString().block());
        assertEquals(Boolean.TRUE, exchange.getAttribute(AuthFilter.ENABLED));
        verifyNoInteractions(sessions);
    }

    @Test
    void loginOn_theOpenPaths_andPreflights_passWithoutACookie() {
        loginOn(true);
        for (String path : new String[] {"/auth/session", "/auth/authentik/start", "/auth/authentik/callback", "/auth/logout"}) {
            reachedHandler.set(false);
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get(path)), chain).block();
            assertTrue(reachedHandler.get(), path);
        }
        reachedHandler.set(false);
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.options("/downloads/active")
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")), chain).block();
        assertTrue(reachedHandler.get(), "CORS preflight");
    }

    @Test
    void loginOn_aBareOptions_isNotAPreflight_andIs401LikeAnyOtherRequest() {
        loginOn(true);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/downloads/active"));

        filter.filter(exchange, chain).block();

        assertFalse(reachedHandler.get(), "no Allow header listing the route's verbs for a caller without a cookie");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void loginOn_aLiveCookie_passes_andPutsTheSubjectOnTheExchange() {
        loginOn(true);
        when(sessions.findBySession(eq("s1"), eq(CLOCK.instant()))).thenReturn(Mono.just("subject-42"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/downloads/active").cookie(new HttpCookie(AuthFilter.COOKIE, "s1")));

        filter.filter(exchange, chain).block();

        assertTrue(reachedHandler.get());
        assertEquals("subject-42", exchange.getAttribute(AuthFilter.SUBJECT));
    }

    @Test
    void loginOn_anExpiredOrUnknownCookie_is401() {
        loginOn(true);
        when(sessions.findBySession(any(), any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/status").cookie(new HttpCookie(AuthFilter.COOKIE, "old")));

        filter.filter(exchange, chain).block();

        assertFalse(reachedHandler.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(exchange.getAttribute(AuthFilter.SUBJECT));
    }

    /** The setup and settings routes are not listed anywhere: default-deny covers them, and this pins that down. */
    @Test
    void loginOn_theSetupAndSettingsRoutes_needASession() {
        loginOn(true);
        for (MockServerHttpRequest.BaseBuilder<?> request : new MockServerHttpRequest.BaseBuilder<?>[] {
                MockServerHttpRequest.get("/settings/setup"), MockServerHttpRequest.post("/settings/setup/done"),
                MockServerHttpRequest.get("/settings/media-servers"), MockServerHttpRequest.put("/settings/suggested-playlists"),
                MockServerHttpRequest.get("/settings/soulseek"), MockServerHttpRequest.put("/settings/slskd")}) {
            reachedHandler.set(false);
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            filter.filter(exchange, chain).block();
            assertFalse(reachedHandler.get(), exchange.getRequest().getPath().value());
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    void pathTricks_doNotReachAnOpenPath() {
        loginOn(true);
        for (String path : new String[] {"/auth/session/", "/AUTH/session", "/auth/session/../../downloads/active", "/auth/logout/x"}) {
            reachedHandler.set(false);
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            filter.filter(exchange, chain).block();
            assertFalse(reachedHandler.get(), path);
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(), path);
        }
    }
}
