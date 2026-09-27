package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.download.DownloadType;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CollectionControllerTest {

    private final YtMusicService ytMusicService = mock(YtMusicService.class);
    private final CollectionController controller = new CollectionController(ytMusicService);

    private static YoutubeCollectionInfo album() {
        return new YoutubeCollectionInfo("MPREb_1", List.of(
                new YoutubeSongInfo("vid1", List.of("Oasis"), "Rock 'n' Roll Star", "https://img/a.jpg", 322),
                new YoutubeSongInfo("vid2", List.of("Oasis"), "Shakermaker", "https://img/a.jpg", null)),
                "1994", "Definitely Maybe", List.of("Oasis"), "https://img/a.jpg");
    }

    private static YoutubeCollectionInfo playlist() {
        return new YoutubeCollectionInfo("PL1", List.of(
                new YoutubeSongInfo("vid9", List.of("Blur"), "Parklife", "https://i.ytimg.com/vi/vid9/hqdefault.jpg", 185)),
                null, "Britpop Essentials", List.of("YouTube Music"), "https://img/p.jpg");
    }

    @Test
    void album_callsGetAlbumInfo_andMapsHeaderAndTracksInOrder() {
        when(ytMusicService.getAlbumInfo("MPREb_1")).thenReturn(Mono.just(album()));

        StepVerifier.create(controller.collection("MPREb_1", DownloadType.ALBUM))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    CollectionView view = response.getBody();
                    assertNotNull(view);
                    assertEquals("MPREb_1", view.id());
                    assertEquals(DownloadType.ALBUM, view.type());
                    assertEquals("Definitely Maybe", view.name());
                    assertEquals(List.of("Oasis"), view.artists());
                    assertEquals("https://img/a.jpg", view.iconURL());
                    assertEquals(1994, view.year());
                    assertEquals(2, view.trackCount());
                    assertEquals(2, view.tracks().size());

                    CollectionView.CollectionTrackView first = view.tracks().get(0);
                    assertEquals("vid1", first.id());
                    assertEquals("Rock 'n' Roll Star", first.name());
                    assertEquals(List.of("Oasis"), first.artists());
                    assertEquals("https://img/a.jpg", first.iconURL());
                    assertEquals(322, first.durationSeconds());
                    assertEquals(1, first.position(), "positions are 1-based");

                    CollectionView.CollectionTrackView second = view.tracks().get(1);
                    assertEquals("vid2", second.id());
                    assertNull(second.durationSeconds());
                    assertEquals(2, second.position());
                })
                .verifyComplete();

        verify(ytMusicService).getAlbumInfo("MPREb_1");
        verify(ytMusicService, never()).getPlaylistInfo(anyString());
    }

    @Test
    void playlist_callsGetPlaylistInfo_andHasNullYear() {
        when(ytMusicService.getPlaylistInfo("PL1")).thenReturn(Mono.just(playlist()));

        StepVerifier.create(controller.collection("PL1", DownloadType.PLAYLIST))
                .assertNext(response -> {
                    CollectionView view = response.getBody();
                    assertNotNull(view);
                    assertEquals(DownloadType.PLAYLIST, view.type());
                    assertEquals("PL1", view.id());
                    assertNull(view.year());
                    assertEquals(List.of("YouTube Music"), view.artists());
                    assertEquals(1, view.trackCount());
                    assertEquals("vid9", view.tracks().get(0).id());
                })
                .verifyComplete();

        verify(ytMusicService).getPlaylistInfo("PL1");
        verify(ytMusicService, never()).getAlbumInfo(anyString());
    }

    @Test
    void id_isTheRequestedId_notTheOneTheAdapterEchoes() {
        // A playlist asked for as VLPL... is answered as PL...; the client must be able to POST the
        // id it gets here to /download/collection unchanged, and media_items are keyed the same way.
        when(ytMusicService.getPlaylistInfo("VLPL1")).thenReturn(Mono.just(playlist()));

        StepVerifier.create(controller.collection("VLPL1", DownloadType.PLAYLIST))
                .assertNext(response -> {
                    assertNotNull(response.getBody());
                    assertEquals("VLPL1", response.getBody().id());
                })
                .verifyComplete();
    }

    @Test
    void nullVideoId_isDropped_andPositionsMatchTheTaskRows() {
        YoutubeCollectionInfo withGap = new YoutubeCollectionInfo("PL2", List.of(
                new YoutubeSongInfo(null, List.of("Blur"), "Unlisted", null, null),
                new YoutubeSongInfo("vid9", List.of("Blur"), "Parklife", null, 185)),
                null, "Gappy", List.of(), null);
        when(ytMusicService.getPlaylistInfo("PL2")).thenReturn(Mono.just(withGap));

        StepVerifier.create(controller.collection("PL2", DownloadType.PLAYLIST))
                .assertNext(response -> {
                    CollectionView view = response.getBody();
                    assertNotNull(view);
                    assertEquals(1, view.trackCount());
                    assertEquals("vid9", view.tracks().get(0).id());
                    assertEquals(1, view.tracks().get(0).position(), "numbered after the filter, like DownloadTaskRunner");
                })
                .verifyComplete();
    }

    @Test
    void nonNumericYear_isNull_notAnError() {
        YoutubeCollectionInfo odd = new YoutubeCollectionInfo("MPREb_2", List.of(), "n/a",
                "Untitled", List.of(), null);
        when(ytMusicService.getAlbumInfo("MPREb_2")).thenReturn(Mono.just(odd));

        StepVerifier.create(controller.collection("MPREb_2", DownloadType.ALBUM))
                .assertNext(response -> {
                    CollectionView view = response.getBody();
                    assertNotNull(view);
                    assertNull(view.year());
                    assertNull(view.iconURL());
                    assertEquals(0, view.trackCount());
                    assertTrue(view.tracks().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void typeSong_isRejectedWith400_withoutCallingTheAdapter() {
        StepVerifier.create(controller.collection("vid1", DownloadType.SONG))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();

        verifyNoInteractions(ytMusicService);
    }

    @Test
    void blankId_isRejectedWith400() {
        StepVerifier.create(controller.collection(" ", DownloadType.ALBUM))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();

        verifyNoInteractions(ytMusicService);
    }

    @Test
    void adapterBadRequest_propagates_andHandlerMapsTo404() {
        when(ytMusicService.getAlbumInfo("nope")).thenReturn(Mono.error(new YtMusicBadRequestException("404")));

        StepVerifier.create(controller.collection("nope", DownloadType.ALBUM))
                .verifyError(YtMusicBadRequestException.class);

        ResponseEntity<Void> response = controller.handleNotFound(new YtMusicBadRequestException("404"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void adapterUnavailable_propagates_andHandlerMapsTo502() {
        when(ytMusicService.getPlaylistInfo("PL1")).thenReturn(Mono.error(new YtMusicUnavailableException("down")));

        StepVerifier.create(controller.collection("PL1", DownloadType.PLAYLIST))
                .verifyError(YtMusicUnavailableException.class);

        ResponseEntity<Void> response = controller.handleUnavailable(new YtMusicUnavailableException("down"));
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }
}
