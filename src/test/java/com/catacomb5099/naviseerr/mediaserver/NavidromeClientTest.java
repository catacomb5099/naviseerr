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
import java.util.List;

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
        client = new NavidromeClient(url, "admin", "s3cret", Duration.ofSeconds(2));
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
                    assertEquals("Navidrome refused the username or password.", error.getMessage());
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
                    assertEquals("Navidrome only lets an admin start a scan: use an admin account.", error.getMessage());
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
                    assertTrue(error.getMessage().endsWith(" answered HTTP 502."), error.getMessage());
                    assertFalse(error.getMessage().contains("startScan"), "no URL (it carries the auth token)");
                })
                .verify();

        server.shutdown();
        StepVerifier.create(client.rescan())
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(MediaServerException.class, error);
                    assertTrue(error.getMessage().startsWith("Could not reach "), error.getMessage());
                    assertTrue(error.getMessage().endsWith(". Is Navidrome running, and can this server reach it?"), error.getMessage());
                    assertFalse(error.getMessage().contains("startScan"), error.getMessage());
                })
                .verify();
    }

    @Test
    void configuredAndCredentials_followTheSettings() {
        assertTrue(client.isConfigured());
        assertTrue(client.hasCredentials());
        NavidromeClient unset = new NavidromeClient("", "", "", Duration.ofSeconds(1));
        assertFalse(unset.isConfigured());
        NavidromeClient noLogin = new NavidromeClient("http://nas:4533/", "admin", "", Duration.ofSeconds(1));
        assertTrue(noLogin.isConfigured());
        assertFalse(noLogin.hasCredentials());
        assertEquals("http://nas:4533", noLogin.url(), "trailing slash dropped");
    }

    @Test
    void reconfigure_pointsRescanAtTheNewServer_test_keepsNothing_andReset_goesBackToTheInstallFile() throws InterruptedException {
        NavidromeClient fromInstallFile = new NavidromeClient("", "", "", Duration.ofSeconds(2));
        assertFalse(fromInstallFile.isConfigured());

        server.enqueue(json(200, OK));
        StepVerifier.create(fromInstallFile.test(server.url("/").toString(), "typed", "pw")).verifyComplete();
        assertTrue(server.takeRequest().getPath().contains("u=typed"));
        assertFalse(fromInstallFile.isConfigured(), "a test stores nothing");

        fromInstallFile.reconfigure(server.url("/").toString(), "saved", "pw");
        assertTrue(fromInstallFile.isConfigured());
        assertEquals("saved", fromInstallFile.username());
        server.enqueue(json(200, OK));
        StepVerifier.create(fromInstallFile.rescan()).verifyComplete();
        assertTrue(server.takeRequest().getPath().contains("u=saved"));

        fromInstallFile.reset();
        assertFalse(fromInstallFile.isConfigured());
    }

    @Test
    void findSong_searchesTheTitle_thenTheFileName_andPicksByArtistAndLength() throws InterruptedException {
        server.enqueue(json(200, """
                {"subsonic-response":{"status":"ok","searchResult3":{}}}
                """));
        server.enqueue(json(200, """
                {"subsonic-response":{"status":"ok","searchResult3":{"song":[
                  {"id":"cover","albumId":"a1","title":"Wonderwall","artist":"Ryan Adams","duration":259,"path":"Ryan Adams/x.mp3","created":"2026-10-21T10:00:00Z"},
                  {"id":"nd1","albumId":"a2","title":"Wonderwall","artist":"Various","albumArtists":[{"id":"x","name":"Oasis"}],"duration":258,"path":"Oasis/y.mp3","created":"2026-10-21T09:00:00Z"}
                ]}}}
                """));

        StepVerifier.create(client.findSong(new SongFinder.Wanted("Wonderwall (Remastered)", List.of("Oasis"), 259,
                        "Oasis/Morning Glory/03 Wonderwall.flac")))
                .expectNext(new SongFinder.Found("nd1", "a2"))
                .verifyComplete();

        RecordedRequest first = server.takeRequest();
        assertTrue(first.getPath().startsWith("/rest/search3?"), first.getPath());
        assertEquals("Wonderwall (Remastered)", first.getRequestUrl().queryParameter("query"));
        assertEquals("0", first.getRequestUrl().queryParameter("albumCount"));
        assertFalse(first.getPath().contains("s3cret"));
        assertEquals("Wonderwall", server.takeRequest().getRequestUrl().queryParameter("query"));
    }

    @Test
    void findSong_nothingMatches_isEmpty_andASubsonicErrorIsASentence() {
        server.enqueue(json(200, """
                {"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"x","artist":"Other","duration":259}]}}}
                """));
        StepVerifier.create(client.findSong(new SongFinder.Wanted("Wonderwall", List.of("Oasis"), 259, "Oasis/A/Wonderwall.mp3")))
                .verifyComplete();

        server.enqueue(json(200, """
                {"subsonic-response":{"status":"failed","error":{"code":0,"message":"boom"}}}
                """));
        StepVerifier.create(client.findSong(new SongFinder.Wanted("Wonderwall", List.of("Oasis"), 259, "Oasis/A/Wonderwall.mp3")))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(MediaServerException.class, error);
                    assertEquals("Navidrome refused the search: boom (Subsonic error 0)", error.getMessage());
                })
                .verify();
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
