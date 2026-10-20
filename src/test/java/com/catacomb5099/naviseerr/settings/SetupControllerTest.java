package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.mockito.Mockito.*;

class SetupControllerTest {

    private final InstallSettingsRepository repository = mock(InstallSettingsRepository.class);
    private final LibraryOrganiser organiser = mock(LibraryOrganiser.class);
    private final WebTestClient http = WebTestClient.bindToController(new SetupController(repository, organiser)).build();

    @Test
    void aFreshInstall_isNotCompleted_andSaysWhereTheMusicLands() {
        when(repository.find()).thenReturn(Mono.just(InstallSettings.NONE));
        when(organiser.libraryRoot()).thenReturn("/music");

        http.get().uri("/settings/setup").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.completed").isEqualTo(false)
                .jsonPath("$.libraryRoot").isEqualTo("/music");
    }

    @Test
    void aFinishedSetup_isCompleted_andADeveloperRunHasNoLibraryRoot() {
        when(repository.find()).thenReturn(Mono.just(new InstallSettings(Instant.parse("2026-10-19T19:00:00Z"), false,
                null, null, null, null, null, null, null, null)));
        when(organiser.libraryRoot()).thenReturn(null);

        http.get().uri("/settings/setup").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.completed").isEqualTo(true)
                .jsonPath("$.libraryRoot").isEmpty();
    }

    @Test
    void finish_stampsTheSetupDone_andAnswers204() {
        when(repository.markSetupDone()).thenReturn(Mono.empty());

        http.post().uri("/settings/setup/done").exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        verify(repository).markSetupDone();
    }
}
