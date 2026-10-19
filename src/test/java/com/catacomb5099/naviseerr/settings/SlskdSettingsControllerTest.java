package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.services.slskd.SlskdConfig;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** "Use my own slskd" over a MockWebServer standing in for that slskd, an in-memory row and a real SlskdService. */
class SlskdSettingsControllerTest {

    private static final String LOGGED_IN = """
            {"address":"vps.slsknet.org","ipEndPoint":"1.2.3.4:2271","state":"Connected, LoggedIn",
             "isConnected":true,"isConnecting":false,"isLoggedIn":true,"isLoggingIn":false,"isTransitioning":false}
            """;
    private static final String DISCONNECTED = """
            {"address":"vps.slsknet.org","ipEndPoint":"1.2.3.4:2271","state":"Disconnected",
             "isConnected":false,"isConnecting":false,"isLoggedIn":false,"isLoggingIn":false,"isTransitioning":false}
            """;

    private MockWebServer own;
    private SlskdConfig config;
    private WebClient installFile;
    private SlskdService slskd;
    private WebTestClient http;
    private SlskdSettingsController controller;
    private final InstallSettingsRepository repository = mock(InstallSettingsRepository.class);
    private final AtomicReference<InstallSettings> row = new AtomicReference<>(InstallSettings.NONE);

    @BeforeEach
    void setUp() throws IOException {
        own = new MockWebServer();
        own.start();
        config = new SlskdConfig();
        ReflectionTestUtils.setField(config, "url", "http://slskd:5030/api/v0/");
        ReflectionTestUtils.setField(config, "apiKey", "install-key");
        installFile = config.slskdWebClient();
        slskd = new SlskdService(installFile);
        slskd.use(installFile, config.url());
        when(repository.find()).thenAnswer(i -> Mono.just(row.get()));
        when(repository.saveSlskd(any(), any())).thenAnswer(i -> {
            InstallSettings s = row.get();
            row.set(new InstallSettings(s.setupCompletedAt(), s.suggestedPlaylists(), s.navidromeUrl(), s.navidromeUser(),
                    s.navidromePassword(), s.jellyfinUrl(), s.jellyfinApiKey(), i.getArgument(0), i.getArgument(1)));
            return Mono.empty();
        });
        when(repository.clearSlskd()).thenAnswer(i -> {
            InstallSettings s = row.get();
            row.set(new InstallSettings(s.setupCompletedAt(), s.suggestedPlaylists(), s.navidromeUrl(), s.navidromeUser(),
                    s.navidromePassword(), s.jellyfinUrl(), s.jellyfinApiKey(), null, null));
            return Mono.empty();
        });
        controller = new SlskdSettingsController(repository, slskd, config, installFile);
        http = WebTestClient.bindToController(controller).controllerAdvice(new SettingsProblemAdvice()).build();
    }

    @AfterEach
    void tearDown() throws IOException {
        own.shutdown();
    }

    private String ownBase() {
        // host:port only, the way a person types it: the controller adds /api/v0/.
        return own.url("/").toString().replaceAll("/$", "");
    }

    @Test
    void nothingSaved_showsTheInstallFilesSlskd_withoutItsKey() {
        // apiKeySet: a key is in force (here the install file's); the saved-key case is asserted in saveAndTest below.
        http.get().uri("/settings/slskd").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.url").isEqualTo("http://slskd:5030/api/v0/")
                .jsonPath("$.apiKeySet").isEqualTo(true)
                .jsonPath("$.fromInstallFile").isEqualTo(true)
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.message").isEmpty();
    }

    @Test
    void saveAndTest_asksThatSlskdForItsServerState_withTheTypedKey_thenStoresAndSwitchesEveryCallToIt() throws InterruptedException {
        own.enqueue(json(LOGGED_IN));
        own.enqueue(json(LOGGED_IN));   // the SlskdService call after the switch

        http.put().uri("/settings/slskd").bodyValue(Map.of("url", ownBase() + "/", "apiKey", "own-key")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.url").isEqualTo(ownBase() + "/api/v0/")
                .jsonPath("$.apiKeySet").isEqualTo(true)
                .jsonPath("$.fromInstallFile").isEqualTo(false)
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.message").isEqualTo("Connected to your slskd at " + ownBase() + "/api/v0/; it is logged in to Soulseek.");

        RecordedRequest test = own.takeRequest();
        assertEquals("/api/v0/server", test.getPath());
        assertEquals("own-key", test.getHeader("X-API-Key"));
        verify(repository).saveSlskd(ownBase() + "/api/v0/", "own-key");

        assertTrue(slskd.getServerState().block().isLoggedIn(), "SlskdService now talks to the saved slskd");
        assertEquals("own-key", own.takeRequest().getHeader("X-API-Key"));
        assertEquals(ownBase() + "/api/v0/", slskd.url());
    }

    @Test
    void aSlskdNotLoggedIn_isStillSaved_andTheSentenceSaysSo() {
        own.enqueue(json(DISCONNECTED));

        http.put().uri("/settings/slskd").bodyValue(Map.of("url", ownBase() + "/api/v0", "apiKey", "k")).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.message")
                .isEqualTo("Connected to your slskd at " + ownBase() + "/api/v0/, but it is not logged in to Soulseek yet (Disconnected).");
        verify(repository).saveSlskd(ownBase() + "/api/v0/", "k");
    }

    @Test
    void aRefusedKey_aWrongPath_andADeadHost_eachGetTheirSentence_andNothingIsStored() throws IOException {
        own.enqueue(new MockResponse().setResponseCode(401));
        assertEquals("slskd refused the API key.", put(Map.of("url", ownBase(), "apiKey", "bad")));

        own.enqueue(new MockResponse().setResponseCode(404));
        assertEquals("slskd at " + ownBase() + "/api/v0/ answered HTTP 404: check the address (it normally ends in /api/v0/).",
                put(Map.of("url", ownBase(), "apiKey", "k")));

        own.shutdown();
        String dead = put(Map.of("url", ownBase(), "apiKey", "k"));
        assertTrue(dead.startsWith("Could not reach "), dead);
        assertTrue(dead.endsWith(". Is slskd running, and can this server reach it?"), dead);

        verify(repository, never()).saveSlskd(any(), any());
        assertEquals("http://slskd:5030/api/v0/", slskd.url(), "still the install file's");
    }

    @Test
    void theFieldsAreCheckedBeforeAnythingIsCalled() {
        assertEquals("Enter the address of slskd.", put(Map.of("apiKey", "k")));
        assertEquals("The address should start with http:// or https://, for example http://192.168.1.20:5030.",
                put(Map.of("url", "nas:5030", "apiKey", "k")));
        assertEquals("Enter the slskd API key.", put(Map.of("url", "http://nas:5030")));
        assertEquals(0, own.getRequestCount());
    }

    @Test
    void anEmptyKey_keepsTheStoredOne() throws InterruptedException {
        row.set(new InstallSettings(null, false, null, null, null, null, null, ownBase() + "/api/v0/", "kept"));
        own.enqueue(json(LOGGED_IN));

        http.put().uri("/settings/slskd").bodyValue(Map.of("url", ownBase(), "apiKey", "")).exchange().expectStatus().isOk();

        assertEquals("kept", own.takeRequest().getHeader("X-API-Key"));
    }

    @Test
    void anEmptyKey_forAnotherHost_isNotFilledFromTheStoredOne() {
        row.set(new InstallSettings(null, false, null, null, null, null, null, "http://nas:5030/api/v0/", "kept"));

        assertEquals("Enter the slskd API key.", put(Map.of("url", ownBase(), "apiKey", "")));
        assertEquals(0, own.getRequestCount());
    }

    @Test
    void forget_goesBackToTheInstallFilesSlskd_live() {
        row.set(new InstallSettings(null, false, null, null, null, null, null, ownBase() + "/api/v0/", "own-key"));
        controller.applySaved();
        assertEquals(ownBase() + "/api/v0/", slskd.url(), "the saved slskd applies at boot");

        http.delete().uri("/settings/slskd").exchange().expectStatus().isNoContent();

        assertEquals("http://slskd:5030/api/v0/", slskd.url());
        http.get().uri("/settings/slskd").exchange().expectBody().jsonPath("$.fromInstallFile").isEqualTo(true);
    }

    @Test
    void normalise_addsTheApiPathOnce() {
        assertEquals("http://nas:5030/api/v0/", SlskdSettingsController.normalise("http://nas:5030"));
        assertEquals("http://nas:5030/api/v0/", SlskdSettingsController.normalise("http://nas:5030/"));
        assertEquals("http://nas:5030/api/v0/", SlskdSettingsController.normalise("http://nas:5030/api/v0"));
        assertEquals("http://nas:5030/api/v0/", SlskdSettingsController.normalise("http://nas:5030/api/v0/"));
        assertEquals("https://slskd.example.com/api/v0/", SlskdSettingsController.normalise("https://slskd.example.com"));
    }

    private String put(Map<String, Object> body) {
        byte[] answer = http.put().uri("/settings/slskd").bodyValue(body).exchange()
                .expectStatus().isBadRequest().expectBody().returnResult().getResponseBody();
        String json = new String(answer);
        int start = json.indexOf("\"message\":\"") + 11;
        return json.substring(start, json.indexOf('"', start));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(body);
    }
}
