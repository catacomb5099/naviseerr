package com.catacomb5099.naviseerr.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
    private static final UserRow ALEX = new UserRow(UUID.randomUUID(), "alex", "$2a$10$hash", "ADMIN");

    private final AuthRepository repository = mock(AuthRepository.class);
    private final AuthFilter filter = new AuthFilter(repository, CLOCK, true);
    private final AtomicBoolean reachedHandler = new AtomicBoolean();
    private final WebFilterChain chain = exchange -> { reachedHandler.set(true); return Mono.empty(); };

    @Test
    void aDataPathWithoutACookie_is401Json_andNeverReachesTheHandler() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/downloads/active"));

        filter.filter(exchange, chain).block();

        assertFalse(reachedHandler.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
        assertEquals(AuthFilter.UNAUTHORIZED_BODY, exchange.getResponse().getBodyAsString().block());
        verifyNoInteractions(repository);
    }

    @Test
    void theOpenPaths_andPreflights_passWithoutACookie() {
        for (String path : new String[] {"/auth/session", "/auth/login", "/auth/setup", "/auth/logout"}) {
            reachedHandler.set(false);
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post(path)), chain).block();
            assertTrue(reachedHandler.get(), path);
        }
        reachedHandler.set(false);
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.options("/downloads/active")), chain).block();
        assertTrue(reachedHandler.get(), "CORS preflight");
    }

    @Test
    void aLiveCookie_passes_andPutsTheUserOnTheExchange() {
        when(repository.findBySession(eq("s1"), eq(CLOCK.instant()))).thenReturn(Mono.just(ALEX));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/downloads/active").cookie(new org.springframework.http.HttpCookie(AuthFilter.COOKIE, "s1")));

        filter.filter(exchange, chain).block();

        assertTrue(reachedHandler.get());
        assertEquals(ALEX, exchange.getAttribute(AuthFilter.USER));
    }

    @Test
    void anExpiredOrUnknownCookie_is401() {
        when(repository.findBySession(any(), any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/status").cookie(new org.springframework.http.HttpCookie(AuthFilter.COOKIE, "old")));

        filter.filter(exchange, chain).block();

        assertFalse(reachedHandler.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(exchange.getAttribute(AuthFilter.USER));
    }

    @Test
    void authOff_passesEverything_andAsksTheDatabaseNothing() {
        AuthFilter off = new AuthFilter(repository, CLOCK, false);

        off.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/download/song/x")), chain).block();

        assertTrue(reachedHandler.get());
        verifyNoInteractions(repository);
    }
}
