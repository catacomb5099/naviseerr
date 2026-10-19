package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.services.slskd.DownloadQuality;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DownloadQualitySettingsControllerTest {

    private final InstallSettingsRepository repository = mock(InstallSettingsRepository.class);
    private final AtomicReference<DownloadQuality> stored = new AtomicReference<>();
    private final WebTestClient http = WebTestClient.bindToController(new DownloadQualitySettingsController(repository))
            .controllerAdvice(new SettingsProblemAdvice()).build();

    DownloadQualitySettingsControllerTest() {
        when(repository.find()).thenAnswer(i -> Mono.just(
                new InstallSettings(null, false, null, null, null, null, null, null, null, stored.get())));
        when(repository.setDownloadQuality(any())).thenAnswer(i -> { stored.set(i.getArgument(0)); return Mono.empty(); });
    }

    @Test
    void nothingChosen_isLossless_andSaysItIsTheDefault() {
        http.get().uri("/settings/download-quality").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.quality").isEqualTo("LOSSLESS")
                .jsonPath("$.default").isEqualTo(true);
    }

    @Test
    void choosingATier_storesItAtOnce_andTheAnswerIsNoLongerTheDefault() {
        http.put().uri("/settings/download-quality").bodyValue(Map.of("quality", "NORMAL")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.quality").isEqualTo("NORMAL")
                .jsonPath("$.default").isEqualTo(false);
        verify(repository).setDownloadQuality(DownloadQuality.NORMAL);

        http.get().uri("/settings/download-quality").exchange()
                .expectBody()
                .jsonPath("$.quality").isEqualTo("NORMAL")
                .jsonPath("$.default").isEqualTo(false);
    }

    @Test
    void aWordThatIsNotOneOfTheFive_orNoWord_is400_andNothingIsSaved() {
        http.put().uri("/settings/download-quality").bodyValue(Map.of("quality", "best")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo(DownloadQualitySettingsController.CHOOSE);
        http.put().uri("/settings/download-quality").bodyValue(Map.of()).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo(DownloadQualitySettingsController.CHOOSE);

        verify(repository, never()).setDownloadQuality(any());
    }
}
