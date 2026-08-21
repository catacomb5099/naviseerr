package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Album;
import com.catacomb5099.naviseerr.schema.response.Artist;
import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.schema.response.Track;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicSearchType;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class SearchServiceTest {

    private final YtMusicService ytMusicService = mock(YtMusicService.class);
    private final SearchService searchService = new SearchService(ytMusicService);

    private static Track track() {
        return new Track("vid1", "https://example.com/t.jpg", "", "Wonderwall", List.of("Oasis"), "MPREb_1", 0);
    }

    private static Album album() {
        return new Album("MPREb_1", "https://example.com/a.jpg", "Definitely Maybe", List.of("Oasis"), 1994);
    }

    private static Artist artist() {
        return new Artist("UC1", "https://example.com/ar.jpg", "Oasis");
    }

    @Test
    void search_combinesTracksAlbumsAndArtists_fromCombinedGetResults() {
        SearchResponse combined = new SearchResponse(List.of(track()), List.of(album()), List.of(artist()));
        when(ytMusicService.getResults("Oasis")).thenReturn(Mono.just(combined));

        StepVerifier.create(searchService.search("Oasis"))
                .assertNext(response -> {
                    assertTrue(response.getTracks().size() == 1);
                    assertTrue(response.getAlbums().size() == 1);
                    assertTrue(response.getArtists().size() == 1);
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Oasis");
        verify(ytMusicService, never()).getResults(anyString(), any());
    }

    @Test
    void searchTracks_delegatesToYtMusicServiceWithSongsType_andPopulatesOnlyTracks() {
        SearchResponse tracksOnly = new SearchResponse(List.of(track()), Collections.emptyList(), Collections.emptyList());
        when(ytMusicService.getResults("Oasis", YtMusicSearchType.SONGS)).thenReturn(Mono.just(tracksOnly));

        StepVerifier.create(searchService.searchTracks("Oasis"))
                .assertNext(response -> {
                    assertTrue(response.getTracks().size() == 1);
                    assertTrue(response.getAlbums().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.SONGS);
    }

    @Test
    void searchAlbums_delegatesToYtMusicServiceWithAlbumsType_andPopulatesOnlyAlbums() {
        SearchResponse albumsOnly = new SearchResponse(Collections.emptyList(), List.of(album()), Collections.emptyList());
        when(ytMusicService.getResults("Definitely Maybe", YtMusicSearchType.ALBUMS)).thenReturn(Mono.just(albumsOnly));

        StepVerifier.create(searchService.searchAlbums("Definitely Maybe"))
                .assertNext(response -> {
                    assertTrue(response.getAlbums().size() == 1);
                    assertTrue(response.getTracks().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Definitely Maybe", YtMusicSearchType.ALBUMS);
    }

    @Test
    void searchArtists_delegatesToYtMusicServiceWithArtistsType_andPopulatesOnlyArtists() {
        SearchResponse artistsOnly = new SearchResponse(Collections.emptyList(), Collections.emptyList(), List.of(artist()));
        when(ytMusicService.getResults("Oasis", YtMusicSearchType.ARTISTS)).thenReturn(Mono.just(artistsOnly));

        StepVerifier.create(searchService.searchArtists("Oasis"))
                .assertNext(response -> {
                    assertTrue(response.getArtists().size() == 1);
                    assertTrue(response.getTracks().isEmpty());
                    assertTrue(response.getAlbums().isEmpty());
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.ARTISTS);
    }

    @Test
    void search_zeroResults_yieldsEmptyListsNotAnError() {
        SearchResponse empty = new SearchResponse(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        when(ytMusicService.getResults("zzzzzzzznotarealthing")).thenReturn(Mono.just(empty));

        StepVerifier.create(searchService.search("zzzzzzzznotarealthing"))
                .assertNext(response -> {
                    assertTrue(response.getTracks().isEmpty());
                    assertTrue(response.getAlbums().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void search_providerFailure_propagatesAsError_notSwallowed() {
        when(ytMusicService.getResults("Oasis")).thenReturn(Mono.error(new RuntimeException("provider down")));

        StepVerifier.create(searchService.search("Oasis"))
                .verifyError(RuntimeException.class);
    }

    @Test
    void handleBadRequest_mapsTo400() {
        ResponseEntity<Void> response = searchService.handleBadRequest(new YtMusicBadRequestException("bad"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void handleUnavailable_mapsTo502() {
        ResponseEntity<Void> response = searchService.handleUnavailable(new YtMusicUnavailableException("down"));
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }
}
