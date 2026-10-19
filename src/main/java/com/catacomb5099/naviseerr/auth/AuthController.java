package com.catacomb5099.naviseerr.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /auth/*}: the first account (once), login, logout, and "who am I". Plain JSON in, a cookie out,
 * as Navidrome, Jellyfin and slskd do it: no redirecting login page, the web app owns its screens.
 * Nothing here ever returns or logs a password hash or a session id.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    static final String WRONG_LOGIN = "Wrong username or password";
    static final String ACCOUNT_EXISTS = "An account already exists. Log in instead.";
    static final int MIN_PASSWORD = 8;
    /** bcrypt hashes the first 72 bytes only, and spring-security-crypto refuses a longer one outright. */
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_USERNAME = 30;

    private static final SecureRandom RANDOM = new SecureRandom();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    /** Checked against when the username is unknown, so a wrong name costs the same time as a wrong password. */
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    private final AuthRepository repository;
    private final AuthFilter filter;
    private final Clock clock;
    private final Duration sessionLength;

    public AuthController(AuthRepository repository, AuthFilter filter, Clock clock,
                          @Value("${auth.session-days:30}") int sessionDays) {
        this.repository = repository;
        this.filter = filter;
        this.clock = clock;
        this.sessionLength = Duration.ofDays(sessionDays);
    }

    /** {@code {username, password}} for setup and login. */
    public record Credentials(String username, String password) {}

    /** What the web app asks first. Always 200 (it is also the Docker health check). */
    public record SessionView(boolean authRequired, boolean setupRequired, boolean authenticated, String username) {}

    @GetMapping("/session")
    Mono<SessionView> session(ServerWebExchange exchange) {
        if (!filter.isRequired()) return Mono.just(new SessionView(false, false, true, null));
        UserRow user = exchange.getAttribute(AuthFilter.USER);
        if (user != null) return Mono.just(new SessionView(true, false, true, user.username()));
        return repository.anyUser().map(any -> new SessionView(true, !any, false, null));
    }

    /** The first account, once: 201 with the session started; 409 once one exists; 400 for a name or password that will not do. */
    @PostMapping("/setup")
    Mono<ResponseEntity<?>> setup(@RequestBody Credentials body, ServerWebExchange exchange) {
        String problem = validate(body);
        if (problem != null) return Mono.just(ResponseEntity.badRequest().body(Map.of("message", problem)));
        String username = body.username().trim();
        return hash(body.password())
                .flatMap(hash -> repository.createFirstUser(UUID.randomUUID(), username, hash))
                .flatMap(created -> created
                        ? repository.findByUsername(username).flatMap(user -> startSession(user, exchange, HttpStatus.CREATED))
                        : Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ACCOUNT_EXISTS))));
    }

    /** 200 with the session started, 401 {@link #WRONG_LOGIN} otherwise: never says which half was wrong. */
    @PostMapping("/login")
    Mono<ResponseEntity<?>> login(@RequestBody Credentials body, ServerWebExchange exchange) {
        if (body == null || body.username() == null || body.password() == null) return Mono.just(wrongLogin());
        // Exactly one bcrypt check per attempt, against the dummy hash for an unknown name, so the time
        // taken does not say whether the name exists.
        return repository.findByUsername(body.username().trim())
                .map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMap(found -> matches(body.password(), found.map(UserRow::passwordHash).orElse(dummyHash))
                        .filter(ok -> ok && found.isPresent())
                        .map(ok -> found.get()))
                .flatMap(user -> repository.deleteExpired(clock.instant()).then(startSession(user, exchange, HttpStatus.OK)))
                .defaultIfEmpty(wrongLogin());
    }

    /** Ends this browser's session: the row goes, the cookie is told to expire. 204 either way. */
    @PostMapping("/logout")
    Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(AuthFilter.COOKIE);
        Mono<Void> gone = cookie == null ? Mono.empty() : repository.deleteSession(cookie.getValue());
        return gone.thenReturn(ResponseEntity.noContent()
                .header("Set-Cookie", cookie(exchange.getRequest(), "", Duration.ZERO).toString()).build());
    }

    private Mono<ResponseEntity<?>> startSession(UserRow user, ServerWebExchange exchange, HttpStatus status) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return repository.createSession(id, user.userId(), clock.instant().plus(sessionLength))
                .thenReturn(ResponseEntity.status(status)
                        .header("Set-Cookie", cookie(exchange.getRequest(), id, sessionLength).toString())
                        .body(Map.of("username", user.username())));
    }

    /**
     * HttpOnly (scripts never read it), Lax (sent on the app's own calls and on arriving by link, withheld
     * on cross-site POSTs, so no CSRF filter is needed for JSON bodies), Secure when the request came over
     * HTTPS: the proxy's X-Forwarded-Proto (Caddy sets it) or the scheme itself. A plain-HTTP LAN install
     * cannot have Secure, the browser would drop the cookie; the same is true of Navidrome and Jellyfin.
     */
    private static ResponseCookie cookie(ServerHttpRequest request, String value, Duration maxAge) {
        boolean https = "https".equalsIgnoreCase(request.getHeaders().getFirst("X-Forwarded-Proto"))
                || "https".equalsIgnoreCase(request.getURI().getScheme());
        return ResponseCookie.from(AuthFilter.COOKIE, value)
                .httpOnly(true).sameSite("Lax").path("/").maxAge(maxAge).secure(https).build();
    }

    private static ResponseEntity<?> wrongLogin() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", WRONG_LOGIN));
    }

    /** One sentence saying what will not do, or null when the credentials are fine. */
    static String validate(Credentials body) {
        String username = body == null || body.username() == null ? "" : body.username().trim();
        if (username.isEmpty() || username.length() > MAX_USERNAME) return "Username: 1 to " + MAX_USERNAME + " characters.";
        if (body.password() == null || body.password().length() < MIN_PASSWORD) return "Password: at least " + MIN_PASSWORD + " characters.";
        if (body.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) return "Password: at most " + MAX_PASSWORD_BYTES + " characters.";
        return null;
    }

    // bcrypt takes about 100 ms by design; off the event loop so it never holds up other requests.
    private Mono<String> hash(String password) {
        return Mono.fromCallable(() -> encoder.encode(password)).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> matches(String password, String hash) {
        return Mono.fromCallable(() -> hash != null && encoder.matches(password, hash)).subscribeOn(Schedulers.boundedElastic());
    }
}
