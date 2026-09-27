package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SongInfoControllerTest {

    private final YtMusicService ytMusicService = mock(YtMusicService.class);
    private final SongInfoController controller = new SongInfoController(ytMusicService);

    private static YtMusicDetailResponse.SongDetails albumTrack() {
        return YtMusicDetailResponse.SongDetails.builder()
                .videoId("DntZ3-yCaFs").title("Manchild")
                .artists(List.of(new YtMusicSearchResponse.ArtistRef("Sabrina Carpenter", "UCz51")))
                .album(new YtMusicSearchResponse.AlbumRef("Man's Best Friend", "MPREb_msf"))
                .durationSeconds(214).year(2025).viewCount(86_376_709L).explicit(true)
                .thumbnailUrl("https://img/m.jpg")
                .credits(List.of(
                        new YtMusicDetailResponse.Credit("Performed by", List.of("Sabrina Carpenter")),
                        new YtMusicDetailResponse.Credit("Written by", List.of("Sabrina Carpenter", "Jack Antonoff", "Amy Allen"))))
                .build();
    }

    private static YtMusicDetailResponse.SongDetails officialVideo() {
        return YtMusicDetailResponse.SongDetails.builder()
                .videoId("tM1RS_5IAiE").title("Little By Little (Official Video)")
                .artists(List.of(new YtMusicSearchResponse.ArtistRef("Oasis", "UCmMU")))
                .durationSeconds(239).viewCount(41_779_269L).thumbnailUrl("https://img/o.jpg")
                .credits(List.of())
                .build();
    }

    @Test
    void albumTrack_mapsEveryFieldOfTheContract() {
        when(ytMusicService.getSongDetails("DntZ3-yCaFs")).thenReturn(Mono.just(albumTrack()));

        StepVerifier.create(controller.song("DntZ3-yCaFs"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SongInfoView view = response.getBody();
                    assertNotNull(view);
                    assertEquals("DntZ3-yCaFs", view.id());
                    assertEquals("Manchild", view.name());
                    assertEquals(List.of(new SongInfoView.Ref("UCz51", "Sabrina Carpenter")), view.artists());
                    assertEquals(new SongInfoView.Ref("MPREb_msf", "Man's Best Friend"), view.album());
                    assertEquals(214, view.durationSeconds());
                    assertEquals(2025, view.year());
                    assertEquals(86_376_709L, view.viewCount());
                    assertEquals("https://img/m.jpg", view.iconURL());
                    assertEquals(Boolean.TRUE, view.explicit());
                    assertEquals(2, view.credits().size());
                    assertEquals("Written by", view.credits().get(1).role());
                    assertEquals(List.of("Sabrina Carpenter", "Jack Antonoff", "Amy Allen"), view.credits().get(1).names());
                })
                .verifyComplete();
    }

    @Test
    void officialVideo_hasNullAlbumYearExplicit_andEmptyCredits_notAnError() {
        when(ytMusicService.getSongDetails("tM1RS_5IAiE")).thenReturn(Mono.just(officialVideo()));

        StepVerifier.create(controller.song("tM1RS_5IAiE"))
                .assertNext(response -> {
                    SongInfoView view = response.getBody();
                    assertNotNull(view);
                    assertNull(view.album());
                    assertNull(view.year());
                    assertNull(view.explicit(), "unknown, not false");
                    assertTrue(view.credits().isEmpty());
                    assertEquals(239, view.durationSeconds());
                })
                .verifyComplete();
    }

    @Test
    void id_isTheRequestedId_andMissingPiecesDegradeToEmptyNotNull() {
        YtMusicDetailResponse.SongDetails bare = YtMusicDetailResponse.SongDetails.builder()
                .videoId("other").title("Untitled")
                .artists(List.of(new YtMusicSearchResponse.ArtistRef(null, "UCx"), new YtMusicSearchResponse.ArtistRef("Blur", null)))
                .album(new YtMusicSearchResponse.AlbumRef(null, "MPREb_x"))
                .credits(Arrays.asList(new YtMusicDetailResponse.Credit("Mixed by", Arrays.asList("A", null)),
                        new YtMusicDetailResponse.Credit(null, List.of("ignored"))))
                .build();
        when(ytMusicService.getSongDetails("vid1")).thenReturn(Mono.just(bare));

        StepVerifier.create(controller.song("vid1"))
                .assertNext(response -> {
                    SongInfoView view = response.getBody();
                    assertNotNull(view);
                    assertEquals("vid1", view.id(), "the requested id, not the adapter's");
                    assertEquals(List.of(new SongInfoView.Ref(null, "Blur")), view.artists(), "nameless artist dropped");
                    assertNull(view.album(), "an album without a name is no album");
                    assertEquals("", view.iconURL(), "empty string, never null");
                    assertEquals(List.of(new SongInfoView.Credit("Mixed by", List.of("A"))), view.credits());
                })
                .verifyComplete();
    }

    @Test
    void blankId_isRejectedWith400_withoutCallingTheAdapter() {
        StepVerifier.create(controller.song(" "))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();

        verifyNoInteractions(ytMusicService);
    }

    @Test
    void adapterBadRequest_propagates_andHandlerMapsTo404() {
        when(ytMusicService.getSongDetails("nope")).thenReturn(Mono.error(new YtMusicBadRequestException("404")));

        StepVerifier.create(controller.song("nope")).verifyError(YtMusicBadRequestException.class);

        ResponseEntity<Void> response = controller.handleNotFound(new YtMusicBadRequestException("404"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void adapterUnavailable_propagates_andHandlerMapsTo502() {
        when(ytMusicService.getSongDetails("vid1")).thenReturn(Mono.error(new YtMusicUnavailableException("down")));

        StepVerifier.create(controller.song("vid1")).verifyError(YtMusicUnavailableException.class);

        ResponseEntity<Void> response = controller.handleUnavailable(new YtMusicUnavailableException("down"));
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }
}
