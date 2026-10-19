package com.catacomb5099.naviseerr.auth;

import com.catacomb5099.naviseerr.auth.AuthentikClient.OpenIdConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The HTTP boundary with Authentik over a MockWebServer: what is read, and the sentence each failure becomes. */
class AuthentikClientTest {

    private MockWebServer server;
    private String issuer;
    private final AuthentikClient client = new AuthentikClient(new ObjectMapper());

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        issuer = server.url("/application/o/naviseerr/").toString();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private String discovery() {
        return """
                {"issuer": "%s", "authorization_endpoint": "%sapplication/o/authorize/",
                 "token_endpoint": "%sapplication/o/token/", "end_session_endpoint": "%send-session/",
                 "code_challenge_methods_supported": ["plain", "S256"]}
                """.formatted(issuer, server.url("/"), server.url("/"), issuer);
    }

    @Test
    void discover_readsTheEndpoints_fromTheWellKnownDocument() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(discovery()));

        StepVerifier.create(client.discover(issuer))
                .assertNext(openId -> {
                    assertEquals(issuer, openId.issuer());
                    assertEquals(server.url("/application/o/authorize/").toString(), openId.authorizationEndpoint());
                    assertEquals(server.url("/application/o/token/").toString(), openId.tokenEndpoint());
                    assertEquals(issuer + "end-session/", openId.endSessionEndpoint());
                })
                .verifyComplete();
        assertEquals("/application/o/naviseerr/.well-known/openid-configuration", server.takeRequest().getPath());
    }

    @Test
    void theTypedAddress_mayLackTheSlash_orCarryTheWellKnownSuffix() {
        String bare = issuer.substring(0, issuer.length() - 1);
        assertEquals(issuer, AuthentikClient.normaliseIssuer(bare));
        assertEquals(issuer, AuthentikClient.normaliseIssuer(issuer + ".well-known/openid-configuration"));
        assertEquals(issuer, AuthentikClient.normaliseIssuer("  " + issuer + " "));
    }

    @Test
    void aWrongSlug_is404_andTheSentenceSaysSo() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));

        StepVerifier.create(client.discover(issuer))
                .expectErrorSatisfies(e -> {
                    assertInstanceOf(LoginProblem.class, e);
                    assertTrue(e.getMessage().startsWith(issuer + " answered 404."), e.getMessage());
                    assertTrue(e.getMessage().contains("OpenID Configuration Issuer"), e.getMessage());
                })
                .verify();
    }

    @Test
    void anAnswerThatIsNotOpenIdSettings_isNamed() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>a login page</html>"));
        StepVerifier.create(client.discover(issuer))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().contains("did not answer with OpenID settings"))
                .verify();

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"issuer\": \"x\"}"));
        StepVerifier.create(client.discover(issuer))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().contains("did not answer with OpenID settings"))
                .verify();

        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        StepVerifier.create(client.discover(issuer))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().contains("answered 500 instead"))
                .verify();
    }

    @Test
    void nothingListening_isCouldNotReach_withTheHost() throws IOException {
        String host = server.url("/").host() + ":" + server.getPort();
        server.shutdown();

        StepVerifier.create(client.discover(issuer))
                .expectErrorSatisfies(e -> {
                    assertInstanceOf(LoginProblem.class, e);
                    assertTrue(e.getMessage().startsWith("Could not reach " + host + ": connection refused."), e.getMessage());
                })
                .verify();
    }

    @Test
    void anAddressWithoutAScheme_isRefusedBeforeAnyCall() {
        StepVerifier.create(client.discover("auth.example.com/application/o/naviseerr/"))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().startsWith("The Authentik address should start with https://"))
                .verify();
        assertEquals(0, server.getRequestCount());
    }

    private OpenIdConfiguration openId() {
        return new OpenIdConfiguration(issuer, server.url("/application/o/authorize/").toString(),
                server.url("/application/o/token/").toString(), null);
    }

    private static Map<String, String> form(RecordedRequest request) {
        return Arrays.stream(request.getBody().readUtf8().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8)));
    }

    @Test
    void checkClient_postsACodeThatDoesNotExist_andPassesWhenAuthentikOnlyComplainsAboutTheCode() throws InterruptedException {
        // What Authentik 2026.8 answers a bogus code once the client and redirect URI have been accepted.
        server.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\",\"error_description\":\"The provided authorization grant ... is invalid\"}"));

        StepVerifier.create(client.checkClient(openId(), "client-1", "s3cret", "http://nas:5056/api/auth/authentik/callback"))
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/application/o/token/", request.getPath());
        assertEquals(Map.of("grant_type", "authorization_code", "code", "naviseerr-save-and-test",
                "redirect_uri", "http://nas:5056/api/auth/authentik/callback", "client_id", "client-1", "client_secret", "s3cret"),
                form(request));
    }

    @Test
    void checkClient_refusedClient_isTheOneSentence_whateverTheStatus() {
        // A wrong Client ID, a wrong secret and an unregistered redirect URI all answer invalid_client (checked on 2026.8.4).
        server.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed\"}"));
        StepVerifier.create(client.checkClient(openId(), "client-1", "wrong", "http://nas/cb"))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().equals(AuthentikClient.REFUSED))
                .verify();

        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid_client\"}"));
        StepVerifier.create(client.checkClient(openId(), "client-1", "wrong", "http://nas/cb"))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().equals(AuthentikClient.REFUSED))
                .verify();

        // Anything else the token endpoint says about a bogus code is not about the client.
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        StepVerifier.create(client.checkClient(openId(), "client-1", "s3cret", "http://nas/cb")).verifyComplete();
    }

    @Test
    void checkClient_nothingListening_isCouldNotReach() throws IOException {
        String host = server.url("/").host() + ":" + server.getPort();
        OpenIdConfiguration openId = openId();
        server.shutdown();
        StepVerifier.create(client.checkClient(openId, "client-1", "s3cret", "http://nas/cb"))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().startsWith("Could not reach " + host + ": connection refused."))
                .verify();
    }

    @Test
    void exchangeCode_postsTheSixFormFields_andAnswersTheIdToken() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"ignored\",\"token_type\":\"Bearer\",\"id_token\":\"h.p.s\"}"));

        StepVerifier.create(client.exchangeCode(openId(), "client-1", "s3cret", "the-code",
                        "http://nas:5056/api/auth/authentik/callback", "the-verifier"))
                .expectNext("h.p.s")
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/application/o/token/", request.getPath());
        assertTrue(request.getHeader("Content-Type").startsWith("application/x-www-form-urlencoded"), request.getHeader("Content-Type"));
        assertEquals(Map.of("grant_type", "authorization_code", "code", "the-code",
                "redirect_uri", "http://nas:5056/api/auth/authentik/callback", "client_id", "client-1",
                "client_secret", "s3cret", "code_verifier", "the-verifier"), form(request));
    }

    @Test
    void exchangeCode_refused_namesAuthentiksErrorWord_andPointsAtTheSettings() {
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_client\",\"error_description\":\"...\"}"));

        StepVerifier.create(client.exchangeCode(openId(), "client-1", "wrong", "c", "http://nas/cb", "v"))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().equals(
                        "Authentik refused the sign-in (invalid_client). Check the Client ID and Client secret in Settings."))
                .verify();

        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        StepVerifier.create(client.exchangeCode(openId(), "client-1", "wrong", "c", "http://nas/cb", "v"))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().startsWith("Authentik refused the sign-in (500)."))
                .verify();
    }

    @Test
    void exchangeCode_withoutAnIdToken_saysTheOpenidScopeIsMissing() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"only\"}"));

        StepVerifier.create(client.exchangeCode(openId(), "client-1", "s", "c", "http://nas/cb", "v"))
                .expectErrorMatches(e -> e instanceof LoginProblem && e.getMessage().startsWith("Authentik's answer had no ID token."))
                .verify();
    }
}
