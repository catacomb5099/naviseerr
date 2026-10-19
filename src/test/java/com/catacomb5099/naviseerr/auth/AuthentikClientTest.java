package com.catacomb5099.naviseerr.auth;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

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
}
