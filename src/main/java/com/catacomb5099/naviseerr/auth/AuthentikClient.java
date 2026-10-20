package com.catacomb5099.naviseerr.auth;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
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
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * The calls naviseerr makes to Authentik (any OpenID Connect provider, in fact: nothing here is
 * Authentik-specific beyond the wording): reading the provider's OpenID configuration ("Save and test"
 * on the Settings page, and before each sign-in), trying the Client ID, secret and Redirect URL at its
 * token endpoint (Save and test), and swapping an authorization code for an ID token (the sign-in).
 * Every failure becomes a {@link LoginProblem} with one sentence saying what to check. Token answers
 * are never logged.
 */
@Component
public class AuthentikClient {

    static final String DISCOVERY_SUFFIX = ".well-known/openid-configuration";
    static final String EXAMPLE = "https://auth.example.com/application/o/naviseerr/";
    /** Authentik answers in milliseconds; anything slower than this is a network problem to name. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final String REFUSED = "Authentik does not accept this Client ID and secret, or the Redirect URL shown here is not in "
            + "the provider's Redirect URIs yet. Check all three in Authentik; nothing was changed.";

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

    /**
     * Does Authentik know this Client ID and secret, and is {@code redirectUri} one of the provider's
     * Redirect URIs? Asked by POSTing the token endpoint a code that does not exist: Authentik checks the
     * client and the redirect URI first ({@code invalid_client}, RFC 6749 5.2) and only then looks the
     * code up ({@code invalid_grant}), so the error word says which, and nothing is issued either way.
     * A wrong secret saved while the login is on would otherwise show only at the next sign-in, with
     * Settings already behind the door. Only a 4xx carrying a token error word counts as a pass: a 5xx,
     * an HTML page from a proxy, or a token issued for the bogus code fail the test too, since a pass is
     * the switch and such a provider could not complete the sign-in that follows.
     */
    // ponytail: Authentik's error words; Keycloak says unauthorized_client for a wrong secret and would pass here.
    public Mono<Void> checkClient(OpenIdConfiguration openId, String clientId, String clientSecret, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", "naviseerr-save-and-test");
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return webClient.post().uri(openId.tokenEndpoint()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> Map.entry(response.statusCode(), body)))
                .timeout(TIMEOUT)
                .onErrorMap(error -> unreachable(openId.issuer(), error))
                .flatMap(answer -> {
                    String error = read(answer.getValue()).path("error").asString(null);
                    if ("invalid_client".equals(error)) return Mono.<Void>error(new LoginProblem(REFUSED));
                    if (answer.getKey().is4xxClientError() && error != null) return Mono.<Void>empty();
                    return Mono.<Void>error(new LoginProblem("Authentik's token endpoint at " + URI.create(openId.issuer()).getAuthority()
                            + " answered " + answer.getKey().value() + " instead of refusing the test code. Check that Authentik is "
                            + "healthy; nothing was changed."));
                });
    }

    /**
     * POST the token endpoint as a form ({@code client_secret_post}, which Authentik lists) and answer the
     * raw ID token. {@code redirectUri} must be the exact string the authorize request carried.
     */
    public Mono<String> exchangeCode(OpenIdConfiguration openId, String clientId, String clientSecret, String code,
                                     String redirectUri, String verifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code_verifier", verifier);
        return webClient.post().uri(openId.tokenEndpoint()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form)).retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> new LoginProblem("Authentik refused the sign-in (" + tokenError(body, response.statusCode().value())
                                + "). Check the Client ID and Client secret in Settings.")))
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .onErrorMap(error -> !(error instanceof LoginProblem), error -> unreachable(openId.issuer(), error))
                .map(body -> {
                    String idToken = read(body).path("id_token").asString(null);
                    if (idToken == null) {
                        throw new LoginProblem("Authentik's answer had no ID token. Make sure the provider's scope mappings "
                                + "include 'openid'.");
                    }
                    return idToken;
                });
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException notJson) {
            return objectMapper.createObjectNode();
        }
    }

    /** The token endpoint's own {@code error} word (RFC 6749 5.2) when it sent one, else the status. */
    private String tokenError(String body, int status) {
        String error = read(body).path("error").asString(null);
        return error == null ? String.valueOf(status) : error;
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
