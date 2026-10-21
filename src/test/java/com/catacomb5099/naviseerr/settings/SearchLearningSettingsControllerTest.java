package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.learning.SearchLearningRepository;
import com.catacomb5099.naviseerr.learning.SearchLearningSender;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class SearchLearningSettingsControllerTest {

    private final InstallSettingsRepository settings = mock(InstallSettingsRepository.class);
    private final SearchLearningRepository reports = mock(SearchLearningRepository.class);
    private final SearchLearningSender sender = mock(SearchLearningSender.class);
    private final WebTestClient http = WebTestClient.bindToController(new SearchLearningSettingsController(settings, reports, sender))
            .controllerAdvice(new SettingsProblemAdvice()).build();

    SearchLearningSettingsControllerTest() {
        when(settings.searchLearningSince()).thenReturn(Mono.empty());
        when(settings.setSearchLearning(anyBoolean())).thenReturn(Mono.empty());
        when(reports.waiting()).thenReturn(Mono.just(0L));
        when(reports.sent()).thenReturn(Mono.just(0L));
        when(sender.isConfigured()).thenReturn(true);
        when(sender.sendsTo()).thenReturn("croissant:8010");
    }

    @Test
    void aFreshInstall_isOff() {
        http.get().uri("/settings/search-learning").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.on").isEqualTo(false)
                .jsonPath("$.configured").isEqualTo(true)
                .jsonPath("$.sendsTo").isEqualTo("croissant:8010")
                .jsonPath("$.since").isEmpty()
                .jsonPath("$.problem").isEmpty();
    }

    @Test
    void switchingOn_storesIt_andAnswersSinceWhen() {
        Instant since = Instant.parse("2026-10-20T22:00:00Z");
        when(settings.searchLearningSince()).thenReturn(Mono.just(since));
        when(reports.sent()).thenReturn(Mono.just(4L));

        http.put().uri("/settings/search-learning").bodyValue(Map.of("on", true)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.on").isEqualTo(true)
                .jsonPath("$.since").isEqualTo("2026-10-20T22:00:00Z")
                .jsonPath("$.sent").isEqualTo(4);
        verify(settings).setSearchLearning(true);
    }

    @Test
    void switchingOn_withNoAddress_is400_andStoresNothing() {
        when(sender.isConfigured()).thenReturn(false);

        http.put().uri("/settings/search-learning").bodyValue(Map.of("on", true)).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo(SearchLearningSettingsController.NOT_CONFIGURED);
        verify(settings, never()).setSearchLearning(anyBoolean());
    }
}
