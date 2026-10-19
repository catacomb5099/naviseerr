package com.catacomb5099.naviseerr.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * {@code /auth/*}: "who am I" for the web app, the two legs of the sign-in through Authentik, and log out.
 * OpenID Connect authorization code with PKCE and the client secret, hand-rolled on the WebClient the
 * project already has (no Spring Security; see docs/decisions/login-19-10-2026.md). Immich's "Login
 * with OAuth" and Grafana's "Sign in with ..." button do the same dance; naviseerr's button says
 * "Continue with Authentik". Nothing here ever returns or logs a token, a secret or a session id.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /** The sign-in in progress: state, nonce and PKCE verifier live here, HttpOnly, for ten minutes. */
    public static final String LOGIN_COOKIE = "naviseerr_login";
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(10);
    static final String NOT_CONNECTED = "Authentik is not connected. Connect it in Settings first.";
    static final String DENIED = "Authentik did not let you in. Only the users the naviseerr application is bound to in Authentik may log in.";
    static final String STALE = "This sign-in did not start here, or took more than ten minutes. Go back to naviseerr and press Continue with Authentik again.";

    /** What {@code start} remembers across the trip to Authentik, as JSON in the login cookie. */
    public record LoginAttempt(String state, String nonce, String verifier, String redirectUri, String to) {}

    private final LoginSettingsRepository settings;
    private final AuthRepository sessions;
    private final AuthentikClient authentik;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration sessionLength;
    private final List<String> allowedOrigins;

    public AuthController(LoginSettingsRepository settings, AuthRepository sessions, AuthentikClient authentik,
                          ObjectMapper objectMapper, Clock clock,
                          @Value("${auth.session-days:30}") int sessionDays,
                          @Value("${auth.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        this.settings = settings;
        this.sessions = sessions;
        this.authentik = authentik;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.sessionLength = Duration.ofDays(sessionDays);
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * What the web app asks first. {@code {"mode":"open"}} while Authentik is not connected (every route
     * open), {@code {"mode":"authentik","authenticated":true|false}} once it is. Always 200: this is also
     * the Docker health check.
     */
    @GetMapping("/session")
    Map<String, Object> session(ServerWebExchange exchange) {
        boolean enabled = Boolean.TRUE.equals(exchange.getAttribute(AuthFilter.ENABLED));
        return enabled ? Map.of("mode", "authentik", "authenticated", exchange.getAttributes().containsKey(AuthFilter.SUBJECT))
                : Map.of("mode", "open");
    }

    /**
     * Leg one: remember the sign-in in the login cookie and send the browser to Authentik. {@code to} is
     * where to land afterwards (the page the person was on); only the app's own address, a listed
     * origin or a plain path is believed, anything else lands on {@code /}.
     */
    @GetMapping("/authentik/start")
    Mono<ResponseEntity<Void>> start(@RequestParam(required = false) String to, ServerHttpRequest request) {
        return settings.find()
                .switchIfEmpty(Mono.error(new LoginProblem(NOT_CONNECTED)))
                .flatMap(s -> authentik.discover(s.issuer()).map(openId -> {
                    LoginAttempt attempt = new LoginAttempt(Pkce.random(), Pkce.random(), Pkce.random(),
                            LoginSettingsController.redirectUrl(request), returnAddress(to, request));
                    URI authorize = UriComponentsBuilder.fromUriString(openId.authorizationEndpoint())
                            .queryParam("response_type", "code")
                            .queryParam("client_id", s.clientId())
                            .queryParam("redirect_uri", attempt.redirectUri())
                            .queryParam("scope", "openid")
                            .queryParam("state", attempt.state())
                            .queryParam("nonce", attempt.nonce())
                            .queryParam("code_challenge", Pkce.challenge(attempt.verifier()))
                            .queryParam("code_challenge_method", "S256")
                            .build().encode().toUri();
                    return ResponseEntity.status(HttpStatus.FOUND).location(authorize)
                            .header(HttpHeaders.SET_COOKIE, cookie(request, LOGIN_COOKIE, encode(attempt), LOGIN_WINDOW).toString())
                            .<Void>build();
                }))
                .onErrorResume(LoginProblem.class, e -> Mono.just(ResponseEntity.status(
                        NOT_CONNECTED.equals(e.getMessage()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY)
                        .header("X-Message", e.getMessage()).build()));
    }

    /**
     * Leg two: Authentik sent the browser back with a code. Check the state against the login cookie,
     * swap the code (plus the PKCE verifier and the client secret) for an ID token, check the token's
     * claims, open OUR session, set OUR cookie, turn the login on if it was not yet, and land on {@code to}.
     * A failure is a plain page saying what happened, with a link back; never a session.
     */
    @GetMapping("/authentik/callback")
    Mono<ResponseEntity<String>> callback(@RequestParam(required = false) String code,
                                          @RequestParam(required = false) String state,
                                          @RequestParam(required = false) String error,
                                          @RequestParam(name = "error_description", required = false) String errorDescription,
                                          ServerHttpRequest request) {
        LoginAttempt attempt = decode(request.getCookies().getFirst(LOGIN_COOKIE));
        if (attempt == null || state == null || !state.equals(attempt.state())) return Mono.just(failed(request, attempt, STALE));
        if (error != null) {
            return Mono.just(failed(request, attempt, "access_denied".equals(error) ? DENIED
                    : "Authentik answered with an error (" + error + (errorDescription == null ? "" : ": " + errorDescription) + ")."));
        }
        if (code == null) return Mono.just(failed(request, attempt, STALE));
        return settings.find()
                .switchIfEmpty(Mono.error(new LoginProblem(NOT_CONNECTED)))
                .flatMap(s -> authentik.discover(s.issuer())
                        .flatMap(openId -> authentik.exchangeCode(openId, s.clientId(), s.clientSecret(), code, attempt.redirectUri(), attempt.verifier())
                                .map(raw -> IdToken.parse(raw, objectMapper).checked(openId.issuer(), s.clientId(), attempt.nonce(), clock.instant()))))
                .flatMap(idToken -> startSession(idToken.subject(), request, attempt))
                .onErrorResume(LoginProblem.class, e -> Mono.just(failed(request, attempt, e.getMessage())));
    }

    /** Ends this browser's session: the row goes, the cookie is told to expire. 204 either way. Authentik's own session stays. */
    @PostMapping("/logout")
    Mono<ResponseEntity<Void>> logout(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(AuthFilter.COOKIE);
        Mono<Void> gone = cookie == null ? Mono.empty() : sessions.deleteSession(cookie.getValue());
        return gone.thenReturn(ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie(request, AuthFilter.COOKIE, "", Duration.ZERO).toString()).build());
    }

    private Mono<ResponseEntity<String>> startSession(String subject, ServerHttpRequest request, LoginAttempt attempt) {
        String id = Pkce.random();
        Instant now = clock.instant();
        return sessions.deleteExpired(now)
                .then(sessions.createSession(id, subject, now.plus(sessionLength)))
                .then(settings.setEnabled(true))   // the first successful sign-in is what turns the door on
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND).location(URI.create(attempt.to()))
                        .header(HttpHeaders.SET_COOKIE, cookie(request, AuthFilter.COOKIE, id, sessionLength).toString())
                        .header(HttpHeaders.SET_COOKIE, cookie(request, LOGIN_COOKIE, "", Duration.ZERO).toString())
                        .<String>build());
    }

    /** 400 with the sentence and a way back, the login cookie cleared. Authentik's own words are escaped before they reach a page. */
    private static ResponseEntity<String> failed(ServerHttpRequest request, LoginAttempt attempt, String message) {
        String back = attempt == null ? "/" : attempt.to();
        String html = "<!doctype html><meta charset=\"utf-8\"><title>naviseerr</title>"
                + "<p>" + HtmlUtils.htmlEscape(message) + "</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(back) + "\">Back to naviseerr</a></p>";
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.SET_COOKIE, cookie(request, LOGIN_COOKIE, "", Duration.ZERO).toString())
                .body(html);
    }

    /** {@code to} as given when it is a plain path or an address we trust (the request's own host, or a listed origin); else {@code /}. */
    String returnAddress(String to, ServerHttpRequest request) {
        if (to == null || to.isBlank()) return "/";
        if (to.startsWith("/") && !to.startsWith("//")) return to;
        try {
            URI uri = URI.create(to);
            if (uri.getScheme() == null || uri.getAuthority() == null) return "/";
            String host = request.getHeaders().getFirst(HttpHeaders.HOST);
            if (uri.getAuthority().equalsIgnoreCase(host)) return to;
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            return allowedOrigins.stream().anyMatch(origin::equalsIgnoreCase) ? to : "/";
        } catch (IllegalArgumentException malformed) {
            return "/";
        }
    }

    /**
     * HttpOnly (scripts never read it), Lax (sent on the app's own calls and on arriving by link or by
     * Authentik's redirect back, withheld on cross-site POSTs, so no CSRF filter is needed for JSON
     * bodies), Secure when the request came over HTTPS: the proxy's X-Forwarded-Proto (Caddy sets it) or
     * the scheme itself. A plain-HTTP LAN install cannot have Secure, the browser would drop the cookie;
     * the same is true of Navidrome and Jellyfin.
     */
    static ResponseCookie cookie(ServerHttpRequest request, String name, String value, Duration maxAge) {
        boolean https = "https".equalsIgnoreCase(request.getHeaders().getFirst("X-Forwarded-Proto"))
                || "https".equalsIgnoreCase(request.getURI().getScheme());
        return ResponseCookie.from(name, value).httpOnly(true).sameSite("Lax").path("/").maxAge(maxAge).secure(https).build();
    }

    private String encode(LoginAttempt attempt) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsString(attempt).getBytes(StandardCharsets.UTF_8));
    }

    private LoginAttempt decode(HttpCookie cookie) {
        if (cookie == null) return null;
        try {
            return objectMapper.readValue(Base64.getUrlDecoder().decode(cookie.getValue()), LoginAttempt.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
