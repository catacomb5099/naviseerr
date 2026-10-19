package com.catacomb5099.naviseerr.mediaserver;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class NavidromeClientTest {

    private static final String OK = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2",
             "scanStatus":{"scanning":true,"count":0,"folderCount":0,"lastScan":"2026-10-19T12:00:00Z"}}}
            """;

    private MockWebServer server;
    private NavidromeClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String url = server.url("/").toString();
        client = new NavidromeClient(MediaServerConfig.http(url, Duration.ofSeconds(2)).build(),
                url, "admin", "s3cret", Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void rescan_asksForAnIncrementalScan_withTheTokenForm_neverThePassword() throws InterruptedException {
        server.enqueue(json(200, OK));

        StepVerifier.create(client.rescan()).verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        String path = request.getPath();
        assertTrue(path.startsWith("/rest/startScan?"), path);
        assertTrue(path.contains("u=admin"), path);
        assertTrue(path.contains("v=1.16.1") && path.contains("c=naviseerr") && path.contains("f=json"), path);
        assertFalse(path.contains("s3cret"), "the password never travels: " + path);
        assertFalse(path.contains("fullScan"), "incremental: " + path);
        String salt = request.getRequestUrl().queryParameter("s");
        assertNotNull(salt);
        assertEquals(NavidromeClient.token("s3cret", salt), request.getRequestUrl().queryParameter("t"));
    }

    @Test
    void token_isTheSubsonicMd5OfPasswordPlusSalt() {
        // The example from subsonic.org/pages/api.jsp: password "sesame", salt "c19b2d".
        assertEquals("26719a1196d2a940705a59634eb18eab", NavidromeClient.token("sesame", "c19b2d"));
    }

    @Test
    void wrongPassword_isSaidPlainly() {
        server.enqueue(json(200, """
                {"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome",
                 "error":{"code":40,"message":"Wrong username or password"}}}
                """));

        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(MediaServerException.class, error);
                    assertTrue(error.getMessage().contains("rejected the username or password"), error.getMessage());
                    assertFalse(error.getMessage().contains("s3cret"));
                })
                .verify();
    }

    @Test
    void nonAdminUser_isToldToMakeItAnAdmin() {
        server.enqueue(json(200, """
                {"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome",
                 "error":{"code":50,"message":"User is not authorized for the given operation"}}}
                """));

        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> {
                    assertTrue(error.getMessage().contains("only lets an admin start a scan"), error.getMessage());
                    assertTrue(error.getMessage().contains("ND_SCANNER_ADMINONLY"), error.getMessage());
                })
                .verify();
    }

    @Test
    void anotherSubsonicError_keepsNavidromesOwnMessage() {
        server.enqueue(json(200, """
                {"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome",
                 "error":{"code":0,"message":"something odd"}}}
                """));

        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> assertTrue(error.getMessage().contains("something odd (Subsonic error 0)"),
                        error.getMessage()))
                .verify();
    }

    @Test
    void anHttpErrorOrADeadHost_namesTheServer_withoutTheUrlQuery() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("bad gateway"));
        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> {
                    assertTrue(error.getMessage().startsWith("Navidrome at http://"), error.getMessage());
                    assertTrue(error.getMessage().endsWith("did not answer: HTTP 502"), error.getMessage());
                    assertFalse(error.getMessage().contains("startScan"), "no URL (it carries the auth token)");
                })
                .verify();

        server.shutdown();
        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(MediaServerException.class, error);
                    assertTrue(error.getMessage().contains("did not answer"), error.getMessage());
                    assertFalse(error.getMessage().contains("startScan"), error.getMessage());
                })
                .verify();
    }

    @Test
    void configuredAndCredentials_followTheSettings() {
        assertTrue(client.isConfigured());
        assertTrue(client.hasCredentials());
        NavidromeClient unset = new NavidromeClient(MediaServerConfig.http("", Duration.ofSeconds(1)).build(),
                "", "", "", Duration.ofSeconds(1));
        assertFalse(unset.isConfigured());
        NavidromeClient noLogin = new NavidromeClient(MediaServerConfig.http("http://nas:4533/", Duration.ofSeconds(1)).build(),
                "http://nas:4533/", "admin", "", Duration.ofSeconds(1));
        assertTrue(noLogin.isConfigured());
        assertFalse(noLogin.hasCredentials());
        assertEquals("http://nas:4533", noLogin.url(), "trailing slash dropped");
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
