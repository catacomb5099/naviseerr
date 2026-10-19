package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.curator.CuratorScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class SuggestedPlaylistsSettingsControllerTest {

    private final InstallSettingsRepository repository = mock(InstallSettingsRepository.class);
    private final CuratorScheduler scheduler = mock(CuratorScheduler.class);
    private final AtomicBoolean on = new AtomicBoolean();
    private final WebTestClient http = WebTestClient.bindToController(new SuggestedPlaylistsSettingsController(repository, scheduler))
            .controllerAdvice(new SettingsProblemAdvice()).build();

    SuggestedPlaylistsSettingsControllerTest() {
        when(repository.suggestedPlaylists()).thenAnswer(i -> Mono.just(on.get()));
        when(repository.setSuggestedPlaylists(anyBoolean())).thenAnswer(i -> { on.set(i.getArgument(0)); return Mono.empty(); });
        when(scheduler.isConfigured()).thenReturn(true);
        when(scheduler.getRefreshDay()).thenReturn(DayOfWeek.MONDAY);
        when(scheduler.firstRun(any())).thenReturn(Mono.empty());
    }

    @Test
    void aFreshInstall_isOff_andSaysWhenTheWeeklyRunIs() {
        http.get().uri("/settings/suggested-playlists").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.on").isEqualTo(false)
                .jsonPath("$.configured").isEqualTo(true)
                .jsonPath("$.refreshDay").isEqualTo("MONDAY")
                .jsonPath("$.message").isEmpty();
    }

    @Test
    void switchingOn_storesIt_andAsksForTheFirstSetAtOnce() {
        http.put().uri("/settings/suggested-playlists").bodyValue(Map.of("on", true)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.on").isEqualTo(true)
                .jsonPath("$.message").isEqualTo("On. If there are no playlists yet the first set is being made now; "
                        + "the Home page shows it when it is ready (about 90 minutes).");

        verify(repository).setSuggestedPlaylists(true);
        verify(scheduler).firstRun(Duration.ZERO);
    }

    @Test
    void switchingOff_storesIt_andStartsNothing() {
        on.set(true);

        http.put().uri("/settings/suggested-playlists").bodyValue(Map.of("on", false)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.on").isEqualTo(false)
                .jsonPath("$.message").isEqualTo("Off. No playlists are made until you switch this on.");

        verify(repository).setSuggestedPlaylists(false);
        verify(scheduler, never()).firstRun(any());
    }

    @Test
    void anInstallWithoutAPlaylistMaker_saysSo_andRefusesToSwitchOn_butMayStillSwitchOff() {
        when(scheduler.isConfigured()).thenReturn(false);

        http.get().uri("/settings/suggested-playlists").exchange()
                .expectBody().jsonPath("$.configured").isEqualTo(false);
        http.put().uri("/settings/suggested-playlists").bodyValue(Map.of("on", true)).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo(SuggestedPlaylistsSettingsController.NOT_CONFIGURED);
        verify(repository, never()).setSuggestedPlaylists(anyBoolean());

        http.put().uri("/settings/suggested-playlists").bodyValue(Map.of("on", false)).exchange().expectStatus().isOk();
        verify(repository).setSuggestedPlaylists(false);
    }
}
