package com.catacomb5099.naviseerr.auth;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * The calls naviseerr makes to Authentik (any OpenID Connect provider, in fact: nothing here is
 * Authentik-specific beyond the wording). Today: reading the provider's OpenID configuration, which
 * is what "Save and test" on the Settings page does. Every failure becomes a {@link LoginProblem}
 * with one sentence saying what to check.
 */
@Component
public class AuthentikClient {

    static final String DISCOVERY_SUFFIX = ".well-known/openid-configuration";
    static final String EXAMPLE = "https://auth.example.com/application/o/naviseerr/";
    /** Authentik answers in milliseconds; anything slower than this is a network problem to name. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** What the provider says about itself; the endpoints the sign-in uses. */
    public record OpenIdConfiguration(String issuer, String authorizationEndpoint, String tokenEndpoint,
                                      String endSessionEndpoint) {}

    private final WebClient webClient = WebClient.create();
    private final ObjectMapper objectMapper;

    public AuthentikClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * The issuer as it is stored and compared: whatever the person pasted (Authentik's "OpenID
     * Configuration Issuer", or its "OpenID Configuration URL" ending in the well-known path, as Immich
     * also accepts) becomes {@code https://host/application/o/slug/}, with exactly one trailing slash.
     */
    static String normaliseIssuer(String typed) {
        String issuer = typed.trim();
        if (issuer.endsWith(DISCOVERY_SUFFIX)) issuer = issuer.substring(0, issuer.length() - DISCOVERY_SUFFIX.length());
        return issuer.endsWith("/") ? issuer : issuer + "/";
    }

    /** GET {issuer}.well-known/openid-configuration, read into the few fields the sign-in needs. */
    public Mono<OpenIdConfiguration> discover(String typedIssuer) {
        String issuer = normaliseIssuer(typedIssuer);
        if (!issuer.startsWith("https://") && !issuer.startsWith("http://")) {
            return Mono.error(new LoginProblem("The Authentik address should start with https:// (or http:// on your own network), "
                    + "for example " + EXAMPLE + "."));
        }
        String url = issuer + DISCOVERY_SUFFIX;
        return webClient.get().uri(url).retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.just(response.statusCode().value() == 404
                        ? new LoginProblem(issuer + " answered 404. Check the slug at the end: the address looks like "
                                + "https://auth.example.com/application/o/<slug>/ and is shown on the provider's page in Authentik "
                                + "as 'OpenID Configuration Issuer'.")
                        : new LoginProblem(issuer + " answered " + response.statusCode().value() + " instead of its OpenID settings.")))
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .onErrorMap(error -> !(error instanceof LoginProblem), error -> unreachable(issuer, error))
                .map(body -> parse(issuer, body));
    }

    private OpenIdConfiguration parse(String issuer, String body) {
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (JacksonException notJson) {
            throw notOpenId(issuer);
        }
        String authorize = json.path("authorization_endpoint").asString(null);
        String token = json.path("token_endpoint").asString(null);
        if (authorize == null || token == null) throw notOpenId(issuer);
        return new OpenIdConfiguration(json.path("issuer").asString(issuer), authorize, token,
                json.path("end_session_endpoint").asString(null));
    }

    private static LoginProblem notOpenId(String issuer) {
        return new LoginProblem(issuer + " did not answer with OpenID settings. Paste the 'OpenID Configuration Issuer' "
                + "address from the provider's page in Authentik.");
    }

    /** No answer at all: say which kind, in words, with the host that was tried. */
    private static LoginProblem unreachable(String issuer, Throwable error) {
        String host = URI.create(issuer).getAuthority();
        Throwable cause = error instanceof WebClientRequestException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof SSLException) {
            return new LoginProblem("This server does not trust Authentik's certificate at " + host + ". Use a certificate "
                    + "from a public authority, or add yours to the server's trust store.");
        }
        String why = cause instanceof UnknownHostException ? "the name is not known"
                : cause instanceof ConnectException ? "connection refused"
                : cause instanceof TimeoutException ? "no answer in " + TIMEOUT.toSeconds() + " seconds"
                : cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new LoginProblem("Could not reach " + host + ": " + why + ". Is Authentik running, and can this server reach it?");
    }
}
