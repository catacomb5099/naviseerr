package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.mediaserver.JellyfinClient;
import com.catacomb5099.naviseerr.mediaserver.MediaServerException;
import com.catacomb5099.naviseerr.mediaserver.MediaServerNotifier;
import com.catacomb5099.naviseerr.mediaserver.NavidromeClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The Settings page's "Music app" section over real clients (unset install file), an in-memory stand-in
 * for the one-row table and a mocked notifier. The rescan that "Save and test" runs is mocked at the
 * client level ({@code spy}), since the HTTP side is {@code NavidromeClientTest}'s job.
 */
class MediaServerSettingsControllerTest {

    private final InstallSettingsRepository repository = mock(InstallSettingsRepository.class);
    private final NavidromeClient navidrome = spy(new NavidromeClient("", "", "", Duration.ofSeconds(1)));
    private final JellyfinClient jellyfin = spy(new JellyfinClient("", "", Duration.ofSeconds(1)));
    private final MediaServerNotifier notifier = mock(MediaServerNotifier.class);
    private final AtomicReference<InstallSettings> row = new AtomicReference<>(InstallSettings.NONE);
    private final MediaServerSettingsController controller = new MediaServerSettingsController(repository, navidrome, jellyfin, notifier);
    private final WebTestClient http = WebTestClient.bindToController(controller).controllerAdvice(new SettingsProblemAdvice()).build();

    MediaServerSettingsControllerTest() {
        when(repository.find()).thenAnswer(i -> Mono.just(row.get()));
        when(repository.saveNavidrome(any(), any(), any())).thenAnswer(i -> {
            InstallSettings s = row.get();
            row.set(new InstallSettings(s.setupCompletedAt(), s.suggestedPlaylists(), i.getArgument(0), i.getArgument(1),
                    i.getArgument(2), s.jellyfinUrl(), s.jellyfinApiKey(), s.slskdUrl(), s.slskdApiKey()));
            return Mono.empty();
        });
        when(repository.saveJellyfin(any(), any())).thenAnswer(i -> {
            InstallSettings s = row.get();
            row.set(new InstallSettings(s.setupCompletedAt(), s.suggestedPlaylists(), s.navidromeUrl(), s.navidromeUser(),
                    s.navidromePassword(), i.getArgument(0), i.getArgument(1), s.slskdUrl(), s.slskdApiKey()));
            return Mono.empty();
        });
        when(repository.clearNavidrome()).thenAnswer(i -> {
            InstallSettings s = row.get();
            row.set(new InstallSettings(s.setupCompletedAt(), s.suggestedPlaylists(), null, null, null,
                    s.jellyfinUrl(), s.jellyfinApiKey(), s.slskdUrl(), s.slskdApiKey()));
            return Mono.empty();
        });
        when(notifier.status()).thenReturn(List.of());
    }

    @Test
    void nothingSet_getAnswersNullForBothServers() {
        http.get().uri("/settings/media-servers").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.navidrome").isEmpty()
                .jsonPath("$.jellyfin").isEmpty()
                .jsonPath("$.status").isArray()
                .jsonPath("$.message").isEmpty();
    }

    @Test
    void theInstallFilesLines_showAsFromInstallFile_withoutTheSecret() {
        JellyfinClient fromEnv = new JellyfinClient("http://nas:8096/", "k3y", Duration.ofSeconds(1));
        WebTestClient envHttp = WebTestClient.bindToController(
                new MediaServerSettingsController(repository, navidrome, fromEnv, notifier)).build();

        envHttp.get().uri("/settings/media-servers").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.jellyfin.url").isEqualTo("http://nas:8096")
                .jsonPath("$.jellyfin.apiKeySet").isEqualTo(true)
                .jsonPath("$.jellyfin.fromInstallFile").isEqualTo(true)
                .jsonPath("$.jellyfin.apiKey").doesNotExist();
    }

    @Test
    void saveAndTest_runsOneRescanWithTheTypedValues_thenStores_appliesLive_andNeverEchoesThePassword() {
        doReturn(Mono.empty()).when(navidrome).test("http://nas:4533", "admin", "s3cret");

        http.put().uri("/settings/media-servers")
                .bodyValue(Map.of("navidrome", Map.of("url", " http://nas:4533/ ", "username", "admin", "password", "s3cret")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.navidrome.url").isEqualTo("http://nas:4533")
                .jsonPath("$.navidrome.username").isEqualTo("admin")
                .jsonPath("$.navidrome.passwordSet").isEqualTo(true)
                .jsonPath("$.navidrome.fromInstallFile").isEqualTo(false)
                .jsonPath("$.navidrome.password").doesNotExist()
                .jsonPath("$.jellyfin").isEmpty()
                .jsonPath("$.message").isEqualTo("Connected to Navidrome at http://nas:4533: it is rescanning now.");

        verify(repository).saveNavidrome("http://nas:4533", "admin", "s3cret");
        assertEquals("http://nas:4533", navidrome.url(), "the client talks to the saved server from now on");
        verify(notifier).reconfigure();
    }

    @Test
    void aFailedTest_isTheServersSentence_andNothingIsStored() {
        doReturn(Mono.error(new MediaServerException("Navidrome refused the username or password.")))
                .when(navidrome).test(any(), any(), any());

        http.put().uri("/settings/media-servers")
                .bodyValue(Map.of("navidrome", Map.of("url", "http://nas:4533", "username", "admin", "password", "wrong")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo("Navidrome refused the username or password.");

        verify(repository, never()).saveNavidrome(any(), any(), any());
        verify(notifier, never()).reconfigure();
        assertFalse(navidrome.isConfigured());
    }

    @Test
    void anEmptyPassword_keepsTheStoredOne_andTestsWithIt() {
        row.set(new InstallSettings(null, false, "http://nas:4533", "admin", "kept", null, null, null, null));
        doReturn(Mono.empty()).when(navidrome).test(any(), any(), any());

        http.put().uri("/settings/media-servers")
                .bodyValue(Map.of("navidrome", Map.of("url", "http://nas:4534", "username", "admin", "password", "")))
                .exchange()
                .expectStatus().isOk();

        verify(navidrome).test("http://nas:4534", "admin", "kept");
        verify(repository).saveNavidrome("http://nas:4534", "admin", "kept");
    }

    @Test
    void theFieldsAreCheckedBeforeAnythingIsCalled() {
        assertEquals("Choose Navidrome or Jellyfin.", put(Map.of()));
        assertEquals("Choose Navidrome or Jellyfin.", put(Map.of("navidrome", Map.of("url", "http://a"), "jellyfin", Map.of("url", "http://b"))));
        assertEquals("Enter the address of Navidrome.", put(Map.of("navidrome", Map.of("username", "admin"))));
        assertEquals("The address should start with http:// or https://, for example http://192.168.1.10:4533.",
                put(Map.of("navidrome", Map.of("url", "nas:4533", "username", "admin", "password", "x"))));
        assertEquals("Enter the Navidrome admin username.", put(Map.of("navidrome", Map.of("url", "http://nas:4533", "password", "x"))));
        assertEquals("Enter the Navidrome password.", put(Map.of("navidrome", Map.of("url", "http://nas:4533", "username", "admin"))));
        assertEquals("Enter the address of Jellyfin.", put(Map.of("jellyfin", Map.of("apiKey", "k"))));
        assertEquals("The address should start with http:// or https://, for example http://192.168.1.10:8096.",
                put(Map.of("jellyfin", Map.of("url", "nas", "apiKey", "k"))));
        assertEquals("Enter the Jellyfin API key.", put(Map.of("jellyfin", Map.of("url", "http://nas:8096"))));
        verify(navidrome, never()).test(any(), any(), any());
        verify(jellyfin, never()).test(any(), any());
        verifyNoInteractions(notifier);
    }

    @Test
    void saveJellyfin_thenForgetIt_goesBackToTheInstallFile() {
        doReturn(Mono.empty()).when(jellyfin).test("http://nas:8096", "k3y");

        http.put().uri("/settings/media-servers")
                .bodyValue(Map.of("jellyfin", Map.of("url", "http://nas:8096", "apiKey", "k3y")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.jellyfin.url").isEqualTo("http://nas:8096")
                .jsonPath("$.jellyfin.apiKeySet").isEqualTo(true)
                .jsonPath("$.jellyfin.fromInstallFile").isEqualTo(false)
                .jsonPath("$.message").isEqualTo("Connected to Jellyfin at http://nas:8096: it is rescanning now.");
        assertTrue(jellyfin.isConfigured());

        when(repository.clearJellyfin()).thenAnswer(i -> {
            InstallSettings s = row.get();
            row.set(new InstallSettings(s.setupCompletedAt(), s.suggestedPlaylists(), s.navidromeUrl(), s.navidromeUser(),
                    s.navidromePassword(), null, null, s.slskdUrl(), s.slskdApiKey()));
            return Mono.empty();
        });
        http.delete().uri("/settings/media-servers/jellyfin").exchange().expectStatus().isNoContent();

        assertFalse(jellyfin.isConfigured(), "the install file sets no Jellyfin: none");
        verify(notifier, times(2)).reconfigure();
        http.delete().uri("/settings/media-servers/plex").exchange().expectStatus().isNotFound();
    }

    @Test
    void atBoot_theSavedRowIsApplied_overTheInstallFile() {
        row.set(new InstallSettings(null, false, "http://nas:4533", "admin", "pw", null, null, null, null));

        controller.applySaved();

        assertEquals("http://nas:4533", navidrome.url());
        assertTrue(navidrome.hasCredentials());
        assertFalse(jellyfin.isConfigured());
        verify(notifier).reconfigure();
    }

    private String put(Map<String, Object> body) {
        byte[] answer = http.put().uri("/settings/media-servers").bodyValue(body).exchange()
                .expectStatus().isBadRequest().expectBody().returnResult().getResponseBody();
        String json = new String(answer);
        int start = json.indexOf("\"message\":\"") + 11;
        return json.substring(start, json.indexOf('"', start));
    }
}
