package com.catacomb5099.naviseerr.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code /settings/login}: connect Authentik from the web app's Settings page (the fields Immich's
 * OAuth settings have: issuer, client id, client secret). "Save and test" (PUT) reads the provider's
 * OpenID configuration and tries the Client ID, secret and Redirect URL at its token endpoint before
 * anything is stored, so a typo is named at once (and a wrong secret saved while the login is on cannot
 * lock anyone out at the next sign-in); it never turns the login on by itself, the first successful
 * sign-in does.
 * The client secret is write-only: GET says whether one is set, never what it is.
 */
@RestController
@RequestMapping("/settings/login")
public class LoginSettingsController {

    /** The public path the browser is sent back to after Authentik; the server's own route behind any proxy prefix. */
    public static final String CALLBACK_PATH = "/auth/authentik/callback";

    /** PUT body. An empty {@code clientSecret} keeps the one already stored. */
    public record Change(String issuer, String clientId, String clientSecret) {}

    /** GET and PUT answer. {@code message} is the "Save and test" result sentence, null on GET. */
    public record View(String issuer, String clientId, boolean clientSecretSet, boolean enabled, String redirectUrl,
                       String message) {}

    private final LoginSettingsRepository settings;
    private final AuthentikClient authentik;

    public LoginSettingsController(LoginSettingsRepository settings, AuthentikClient authentik) {
        this.settings = settings;
        this.authentik = authentik;
    }

    @GetMapping
    Mono<View> get(ServerHttpRequest request) {
        return settings.find().map(s -> view(s, request, null))
                .defaultIfEmpty(new View(null, null, false, false, redirectUrl(request), null));
    }

    /** "Save and test": 200 with the saved view and a sentence, or 400 {@code {message}} naming what to fix. */
    @PutMapping
    Mono<View> save(@RequestBody Change body, ServerHttpRequest request) {
        String issuer = body.issuer() == null ? "" : body.issuer().trim();
        String clientId = body.clientId() == null ? "" : body.clientId().trim();
        String typedSecret = body.clientSecret() == null ? "" : body.clientSecret().trim();
        if (issuer.isEmpty()) return Mono.error(new LoginProblem("Enter the Authentik address."));
        if (clientId.isEmpty()) return Mono.error(new LoginProblem("Enter the Client ID."));
        return settings.find().map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(existing -> {
            String secret = typedSecret.isEmpty() ? existing.map(LoginSettings::clientSecret).orElse("") : typedSecret;
            if (secret.isEmpty()) return Mono.error(new LoginProblem("Enter the Client secret."));
            return authentik.discover(issuer).flatMap(openId -> {
                String stored = AuthentikClient.normaliseIssuer(issuer);
                boolean enabled = existing.map(LoginSettings::enabled).orElse(false);
                return authentik.checkClient(openId, clientId, secret, redirectUrl(request))
                        .then(Mono.defer(() -> settings.save(stored, clientId, secret))).thenReturn(view(
                        new LoginSettings(stored, clientId, secret, enabled), request,
                        "Authentik answered at " + openId.issuer() + " and accepted the Client ID, secret and Redirect URL. "
                                + (enabled ? "The login is on." : "Sign in once with 'Continue with Authentik' to turn the login on.")));
            });
        });
    }

    /** "Turn off Authentik login": the app is open again on the next request; the settings stay for later. */
    @DeleteMapping
    Mono<ResponseEntity<Void>> turnOff() {
        return settings.setEnabled(false).thenReturn(ResponseEntity.noContent().build());
    }

    @ExceptionHandler(LoginProblem.class)
    ResponseEntity<Map<String, String>> problem(LoginProblem e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    private static View view(LoginSettings s, ServerHttpRequest request, String message) {
        return new View(s.issuer(), s.clientId(), !s.clientSecret().isEmpty(), s.enabled(), redirectUrl(request), message);
    }

    /**
     * The address to paste into Authentik's "Redirect URIs": where this request came from, as the
     * browser sees it. Scheme from the proxy's {@code X-Forwarded-Proto} (read by hand: {@code @EnableWebFlux}
     * keeps Boot from wiring the forwarded-header support), host from the raw {@code Host} header, and
     * the path prefix the web app's Caddy strips before forwarding ({@code X-Forwarded-Prefix: /api}, one
     * line in its Caddyfile). A jar reached directly has no prefix. Must give the exact string the
     * sign-in later sends to Authentik, which matches it strictly.
     */
    static String redirectUrl(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String scheme = first(headers, "X-Forwarded-Proto", Objects.requireNonNullElse(request.getURI().getScheme(), "http"));
        String host = first(headers, HttpHeaders.HOST, request.getURI().getAuthority());
        String prefix = first(headers, "X-Forwarded-Prefix", "");
        while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return scheme + "://" + host + prefix + CALLBACK_PATH;
    }

    /** The first value of a header that proxies may chain with commas ({@code https,http}); the fallback when absent. */
    private static String first(HttpHeaders headers, String name, String fallback) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) return fallback == null ? "" : fallback;
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }
}
