package com.catacomb5099.naviseerr.auth;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;

/**
 * The door (19-10-2026). While Authentik is not connected, or the login was turned off, every request
 * passes as it always did. From the moment "Save and test" has connected Authentik
 * ({@code login_settings.enabled}, read afresh on every request, so the very next one is behind the door),
 * every request needs a live session cookie, except the handful of paths that exist to get one.
 * Default-deny, so a new controller is protected without anyone remembering to say so.
 *
 * <p>Runs right after the CORS filter, so a 401 still carries the CORS headers the cross-origin dev
 * setup (Vite :5173 -> :8080) needs to read it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthFilter implements WebFilter {

    public static final String COOKIE = "naviseerr_session";
    /** Exchange attribute: the Authentik subject of a request made with a live session. */
    public static final String SUBJECT = AuthFilter.class.getName() + ".subject";
    /** Exchange attribute: whether the login is on for this request, so {@code /auth/session} need not ask again. */
    public static final String ENABLED = AuthFilter.class.getName() + ".enabled";
    /** What a request without a session gets: the web app shows its login screen on any 401. */
    static final String UNAUTHORIZED_BODY = "{\"message\":\"Log in to Naviseerr\"}";

    /** Reachable without a session: to learn whether one is needed, to get one, and to end one (so a dead cookie is still cleared). */
    private static final Set<String> OPEN = Set.of("/auth/session", "/auth/authentik/start", "/auth/authentik/callback", "/auth/logout");

    private final LoginSettingsRepository settings;
    private final AuthRepository sessions;
    private final Clock clock;

    public AuthFilter(LoginSettingsRepository settings, AuthRepository sessions, Clock clock) {
        this.settings = settings;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // One SELECT on a one-row table per request, so turning the login off needs no restart and no cache.
        return settings.isEnabled().flatMap(enabled -> {
            exchange.getAttributes().put(ENABLED, enabled);
            if (!enabled) return chain.filter(exchange);
            HttpCookie cookie = exchange.getRequest().getCookies().getFirst(COOKIE);
            Mono<Void> resolved = cookie == null ? Mono.empty()
                    : sessions.findBySession(cookie.getValue(), clock.instant())
                            .doOnNext(subject -> exchange.getAttributes().put(SUBJECT, subject)).then();
            return resolved.then(Mono.defer(() -> {
                String path = exchange.getRequest().getPath().value();
                // A CORS preflight (OPTIONS with Origin and Access-Control-Request-Method) never carries the cookie and
                // must pass; a bare OPTIONS is a request like any other, or it would list every route's verbs for free.
                if (OPEN.contains(path) || CorsUtils.isPreFlightRequest(exchange.getRequest())
                        || exchange.getAttributes().containsKey(SUBJECT)) {
                    return chain.filter(exchange);
                }
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                response.getHeaders().setCacheControl("no-store");
                DataBuffer body = response.bufferFactory().wrap(UNAUTHORIZED_BODY.getBytes(StandardCharsets.UTF_8));
                return response.writeWith(Mono.just(body));
            }));
        });
    }
}
