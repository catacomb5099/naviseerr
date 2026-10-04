package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RadioControllerTest {

    private final YtMusicService ytMusicService = mock(YtMusicService.class);
    private final RadioRepository repository = mock(RadioRepository.class);
    private final WebTestClient client = WebTestClient
            .bindToController(new RadioController(ytMusicService, repository)).build();

    private static YoutubeCollectionInfo radio(String title) {
        return new YoutubeCollectionInfo("7CTJcHjkq0E", List.of(
                new YoutubeSongInfo("q1", List.of("Earth, Wind & Fire"), "September",
                        "https://i.ytimg.com/vi/q1/hqdefault.jpg", 216),
                new YoutubeSongInfo(null, List.of("Nobody"), "No Id", null, null),
                new YoutubeSongInfo("q2", List.of("Queen"), "Another One Bites The Dust", null, 215)),
                null, title, List.of("Michael Jackson"), "https://img/seed.jpg");
    }

    @Test
    void start_savesTheRadioItShows_andAnswers201WithItsOwnId() {
        when(ytMusicService.getRadioInfo("7CTJcHjkq0E")).thenReturn(Mono.just(radio("Billie Jean")));
        when(repository.save(any(), anyString(), any())).thenReturn(Mono.empty());

        RadioView view = client.post().uri("/radios?seed=7CTJcHjkq0E").exchange()
                .expectStatus().isCreated()
                .expectHeader().value("Location", location -> assertTrue(location.startsWith("/radios/")))
                .expectBody(RadioView.class).returnResult().getResponseBody();

        assertNotNull(view);
        assertEquals("Billie Jean radio", view.name());
        assertEquals(List.of("Michael Jackson"), view.artists());
        assertEquals("https://img/seed.jpg", view.iconURL());
        assertEquals(List.of("q1", "q2"), view.tracks().stream().map(CollectionView.CollectionTrackView::id).toList());
        assertEquals(2, view.trackCount());
        assertEquals(1, view.tracks().getFirst().position());

        ArgumentCaptor<YoutubeCollectionInfo> saved = ArgumentCaptor.forClass(YoutubeCollectionInfo.class);
        verify(repository).save(eq(view.id()), eq("7CTJcHjkq0E"), saved.capture());
        assertEquals("Billie Jean radio", saved.getValue().name());
        assertEquals(2, saved.getValue().songs().size(), "a song with no id is never saved, as a download would skip it");
    }

    @Test
    void start_withAnUntitledSeed_isCalledJustRadio() {
        when(ytMusicService.getRadioInfo("PLx")).thenReturn(Mono.just(radio(null)));
        when(repository.save(any(), anyString(), any())).thenReturn(Mono.empty());

        client.post().uri("/radios?seed=PLx").exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.name").isEqualTo("Radio");
    }

    @Test
    void start_whenYouTubeHasNoRadio_is404_andSavesNothing() {
        when(ytMusicService.getRadioInfo("zzzzzzzzzzz"))
                .thenReturn(Mono.error(new YtMusicBadRequestException("404 not_found")));

        client.post().uri("/radios?seed=zzzzzzzzzzz").exchange().expectStatus().isNotFound();
        verifyNoInteractions(repository);
    }

    @Test
    void start_whenTheAdapterIsDown_is502() {
        when(ytMusicService.getRadioInfo("7CTJcHjkq0E"))
                .thenReturn(Mono.error(new YtMusicUnavailableException("timeout")));

        client.post().uri("/radios?seed=7CTJcHjkq0E").exchange().expectStatus().isEqualTo(502);
    }

    @Test
    void start_withoutASeed_is400() {
        client.post().uri("/radios").exchange().expectStatus().isBadRequest();
        client.post().uri("/radios?seed=").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(ytMusicService);
    }

    @Test
    void one_readsTheSavedRadio_neverYouTube() {
        UUID id = UUID.randomUUID();
        when(repository.find(id)).thenReturn(Mono.just(RadioController.named(radio("Billie Jean"))));

        client.get().uri("/radios/" + id).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.name").isEqualTo("Billie Jean radio")
                .jsonPath("$.tracks[1].name").isEqualTo("Another One Bites The Dust");
        verifyNoInteractions(ytMusicService);
    }

    @Test
    void one_forAnIdNeverStarted_is404() {
        UUID id = UUID.randomUUID();
        when(repository.find(id)).thenReturn(Mono.empty());

        client.get().uri("/radios/" + id).exchange().expectStatus().isNotFound();
    }
}
