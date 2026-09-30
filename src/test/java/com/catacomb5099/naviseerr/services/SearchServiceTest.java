package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Album;
import com.catacomb5099.naviseerr.schema.response.Artist;
import com.catacomb5099.naviseerr.schema.response.Playlist;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class SearchServiceTest {

    private final YtMusicService ytMusicService = mock(YtMusicService.class);
    private final SearchService searchService = new SearchService(ytMusicService);

    private static Track track() {
        return new Track("vid1", "https://example.com/t.jpg", "", "Wonderwall", List.of("Oasis"), "MPREb_1", 0, null);
    }

    private static Album album() {
        return new Album("MPREb_1", "https://example.com/a.jpg", "Definitely Maybe", List.of("Oasis"), 1994);
    }

    private static Artist artist() {
        return new Artist("UC1", "https://example.com/ar.jpg", "Oasis");
    }

    private static Playlist playlist() {
        return new Playlist("PLK1PkWQlWtnNfovRdGWpKffO1Wdi2kvDx", "https://example.com/p.jpg", "Britpop Essentials", List.of("YouTube Music"), 42);
    }

    private static SearchResponse only(List<Track> tracks, List<Album> albums, List<Artist> artists, List<Playlist> playlists) {
        return new SearchResponse(tracks, albums, artists, playlists);
    }

    /** Every search "All" makes, answered with one item each (the mixed page with nothing) unless a test overrides one. */
    private void stubEveryCategory(String query) {
        when(ytMusicService.getResults(query)).thenReturn(Mono.just(only(List.of(), List.of(), List.of(), List.of())));
        when(ytMusicService.getResults(query, YtMusicSearchType.SONGS, 20)).thenReturn(Mono.just(only(List.of(track()), List.of(), List.of(), List.of())));
        when(ytMusicService.getResults(query, YtMusicSearchType.ALBUMS, 20)).thenReturn(Mono.just(only(List.of(), List.of(album()), List.of(), List.of())));
        when(ytMusicService.getResults(query, YtMusicSearchType.ARTISTS, 20)).thenReturn(Mono.just(only(List.of(), List.of(), List.of(artist()), List.of())));
        when(ytMusicService.getResults(query, YtMusicSearchType.PLAYLISTS, 20)).thenReturn(Mono.just(only(List.of(), List.of(), List.of(), List.of(playlist()))));
        when(ytMusicService.getResults(query, YtMusicSearchType.FEATURED_PLAYLISTS)).thenReturn(Mono.just(only(List.of(), List.of(), List.of(), List.of(playlist("RDCLAK5uy_1", "Cool Britannia")))));
    }

    @Test
    void search_asksTheMixedPageAndEachCategory_twentyOfEach_andFillsEveryShelf() {
        stubEveryCategory("Oasis");

        StepVerifier.create(searchService.search("Oasis"))
                .assertNext(response -> {
                    assertEquals(1, response.getTracks().size());
                    assertEquals(1, response.getAlbums().size());
                    assertEquals(1, response.getArtists().size());
                    assertEquals(List.of("RDCLAK5uy_1", "PLK1PkWQlWtnNfovRdGWpKffO1Wdi2kvDx"), ids(response.getPlaylists()));
                    assertEquals(List.of(), response.getUnavailable());
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Oasis");
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.SONGS, 20);
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.ALBUMS, 20);
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.ARTISTS, 20);
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.PLAYLISTS, 20);
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.FEATURED_PLAYLISTS);
    }

    @Test
    void search_putsTheMixedPageOnTopOfEachShelf_thenTheCategorySearches_eachItemOnce() {
        stubEveryCategory("Oasis");
        Track hit = new Track("hit", "", "", "Champagne Supernova", List.of("Oasis"), "", 0, null);
        Track sameSongFromMixed = new Track("vid1", "", "", "Wonderwall", List.of("Oasis"), "", 0, null);
        Artist oasisFromMixed = new Artist("UC1", "", "Oasis");
        when(ytMusicService.getResults("Oasis")).thenReturn(Mono.just(only(
                List.of(hit, sameSongFromMixed), List.of(), List.of(oasisFromMixed), List.of())));

        StepVerifier.create(searchService.search("Oasis"))
                .assertNext(response -> {
                    assertEquals(List.of("hit", "vid1"), response.getTracks().stream().map(Track::getId).toList());
                    // Wonderwall is in both: it keeps the mixed page's place, but the songs search's copy, which knows its album.
                    assertEquals("MPREb_1", response.getTracks().get(1).getAlbumId());
                    assertEquals(1, response.getArtists().size());
                    assertEquals(1, response.getAlbums().size());
                })
                .verifyComplete();
    }

    @Test
    void search_mixedPageFailing_stillShowsTheCategorySearches() {
        stubEveryCategory("Oasis");
        when(ytMusicService.getResults("Oasis")).thenReturn(Mono.error(new YtMusicUnavailableException("mixed down")));

        StepVerifier.create(searchService.search("Oasis"))
                .assertNext(response -> {
                    assertEquals(1, response.getTracks().size());
                    assertEquals(1, response.getAlbums().size());
                    assertEquals(1, response.getArtists().size());
                    assertEquals(2, response.getPlaylists().size());
                    assertEquals(List.of("mixed"), response.getUnavailable());
                })
                .verifyComplete();
    }

    @Test
    void topThenRest_keepsTopOrder_appendsOnlyNewOnes_andTakesRestsCopyOfASharedOne() {
        List<String> merged = SearchService.topThenRest(List.of("b1", "a1"), List.of("a2", "c2", "b2", "c3"), s -> s.substring(0, 1));
        assertEquals(List.of("b2", "a2", "c3"), merged);
        assertEquals(List.of("x"), SearchService.topThenRest(List.of(), List.of("x"), s -> s));
        assertEquals(List.of("x"), SearchService.topThenRest(List.of("x"), List.of(), s -> s));
    }

    @Test
    void search_oneShelfFailing_stillShowsTheOthers() {
        stubEveryCategory("Oasis");
        when(ytMusicService.getResults("Oasis", YtMusicSearchType.ALBUMS, 20)).thenReturn(Mono.error(new YtMusicUnavailableException("albums down")));
        when(ytMusicService.getResults("Oasis", YtMusicSearchType.PLAYLISTS, 20)).thenReturn(Mono.error(new YtMusicUnavailableException("playlists down")));

        StepVerifier.create(searchService.search("Oasis"))
                .assertNext(response -> {
                    assertEquals(1, response.getTracks().size());
                    assertTrue(response.getAlbums().isEmpty());
                    assertEquals(1, response.getArtists().size());
                    assertTrue(response.getPlaylists().isEmpty());
                    // Named, so the client can say "couldn't load albums" rather than show none.
                    assertEquals(List.of("albums", "playlists"), response.getUnavailable());
                })
                .verifyComplete();
    }

    @Test
    void search_songSearchFailing_isAnError_soADownAdapterNeverReadsAsNoResults() {
        stubEveryCategory("Oasis");
        when(ytMusicService.getResults("Oasis", YtMusicSearchType.SONGS, 20)).thenReturn(Mono.error(new YtMusicUnavailableException("provider down")));

        StepVerifier.create(searchService.search("Oasis"))
                .verifyError(YtMusicUnavailableException.class);
    }

    @Test
    void searchTracks_delegatesToYtMusicServiceWithSongsType_andPopulatesOnlyTracks() {
        SearchResponse tracksOnly = new SearchResponse(List.of(track()), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        when(ytMusicService.getResults("Oasis", YtMusicSearchType.SONGS, 20)).thenReturn(Mono.just(tracksOnly));

        StepVerifier.create(searchService.searchTracks("Oasis", 20))
                .assertNext(response -> {
                    assertTrue(response.getTracks().size() == 1);
                    assertTrue(response.getAlbums().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.SONGS, 20);
    }

    @Test
    void searchAlbums_delegatesToYtMusicServiceWithAlbumsType_andPopulatesOnlyAlbums() {
        SearchResponse albumsOnly = new SearchResponse(Collections.emptyList(), List.of(album()), Collections.emptyList(), Collections.emptyList());
        when(ytMusicService.getResults("Definitely Maybe", YtMusicSearchType.ALBUMS, 20)).thenReturn(Mono.just(albumsOnly));

        StepVerifier.create(searchService.searchAlbums("Definitely Maybe", 20))
                .assertNext(response -> {
                    assertTrue(response.getAlbums().size() == 1);
                    assertTrue(response.getTracks().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Definitely Maybe", YtMusicSearchType.ALBUMS, 20);
    }

    @Test
    void searchArtists_delegatesToYtMusicServiceWithArtistsType_andPopulatesOnlyArtists() {
        SearchResponse artistsOnly = new SearchResponse(Collections.emptyList(), Collections.emptyList(), List.of(artist()), Collections.emptyList());
        when(ytMusicService.getResults("Oasis", YtMusicSearchType.ARTISTS, 20)).thenReturn(Mono.just(artistsOnly));

        StepVerifier.create(searchService.searchArtists("Oasis", 20))
                .assertNext(response -> {
                    assertTrue(response.getArtists().size() == 1);
                    assertTrue(response.getTracks().isEmpty());
                    assertTrue(response.getAlbums().isEmpty());
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.ARTISTS, 20);
    }

    @Test
    void categorySearches_passTheAskedForLimitThrough_soShowMoreCanGetALongerList() {
        SearchResponse none = new SearchResponse(List.of(), List.of(), List.of(), List.of());
        when(ytMusicService.getResults(anyString(), any(), anyInt())).thenReturn(Mono.just(none));

        searchService.searchTracks("Oasis", 60).block();
        searchService.searchAlbums("Oasis", 40).block();
        searchService.searchArtists("Oasis", 80).block();

        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.SONGS, 60);
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.ALBUMS, 40);
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.ARTISTS, 80);
    }

    @Test
    void categorySearches_limitOutsideWhatTheAdapterAccepts_isPulledIntoRange() {
        SearchResponse none = new SearchResponse(List.of(), List.of(), List.of(), List.of());
        when(ytMusicService.getResults(anyString(), any(), anyInt())).thenReturn(Mono.just(none));

        searchService.searchTracks("Oasis", 5000).block();
        searchService.searchAlbums("Oasis", 0).block();
        searchService.searchArtists("Oasis", -3).block();

        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.SONGS, 100);
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.ALBUMS, 1);
        verify(ytMusicService).getResults("Oasis", YtMusicSearchType.ARTISTS, 1);
    }

    @Test
    void searchPlaylists_biggerLimit_growsTheFanMadeSearchOnly_notTheFeaturedFiller() {
        when(ytMusicService.getResults("Britpop", YtMusicSearchType.PLAYLISTS, 60)).thenReturn(Mono.just(playlistsOnly(playlist("PL1", "Britpop Bangers"))));
        when(ytMusicService.getResults("Britpop", YtMusicSearchType.FEATURED_PLAYLISTS)).thenReturn(Mono.just(playlistsOnly(playlist("RDCLAK5uy_1", "Cool Britannia"))));

        StepVerifier.create(searchService.searchPlaylists("Britpop", 60))
                .assertNext(response -> assertEquals(2, response.getPlaylists().size()))
                .verifyComplete();

        verify(ytMusicService).getResults("Britpop", YtMusicSearchType.PLAYLISTS, 60);
        verify(ytMusicService).getResults("Britpop", YtMusicSearchType.FEATURED_PLAYLISTS);
        verify(ytMusicService, never()).getResults(eq("Britpop"), eq(YtMusicSearchType.FEATURED_PLAYLISTS), anyInt());
    }

    private static Playlist playlist(String id, String name) {
        return new Playlist(id, "https://example.com/p.jpg", name, List.of("someone"), 0);
    }

    private static SearchResponse playlistsOnly(Playlist... playlists) {
        return new SearchResponse(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), List.of(playlists));
    }

    private static List<String> ids(List<Playlist> playlists) {
        return playlists.stream().map(Playlist::getId).toList();
    }

    @Test
    void searchPlaylists_asksForFanMadeAndFeaturedPlaylists_andReturnsBoth() {
        Playlist fanMade = playlist("PL1", "Britpop Bangers");
        Playlist featured = playlist("RDCLAK5uy_1", "Cool Britannia");
        when(ytMusicService.getResults("Britpop", YtMusicSearchType.PLAYLISTS, 20)).thenReturn(Mono.just(playlistsOnly(fanMade)));
        when(ytMusicService.getResults("Britpop", YtMusicSearchType.FEATURED_PLAYLISTS)).thenReturn(Mono.just(playlistsOnly(featured)));

        StepVerifier.create(searchService.searchPlaylists("Britpop", 20))
                .assertNext(response -> {
                    assertEquals(List.of("RDCLAK5uy_1", "PL1"), ids(response.getPlaylists()));
                    assertTrue(response.getTracks().isEmpty());
                    assertTrue(response.getAlbums().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                })
                .verifyComplete();

        verify(ytMusicService).getResults("Britpop", YtMusicSearchType.PLAYLISTS, 20);
        verify(ytMusicService).getResults("Britpop", YtMusicSearchType.FEATURED_PLAYLISTS);
    }

    @Test
    void searchPlaylists_featuredSearchFailing_stillReturnsFanMadePlaylists() {
        when(ytMusicService.getResults("Britpop", YtMusicSearchType.PLAYLISTS, 20)).thenReturn(Mono.just(playlistsOnly(playlist("PL1", "Britpop Bangers"))));
        when(ytMusicService.getResults("Britpop", YtMusicSearchType.FEATURED_PLAYLISTS)).thenReturn(Mono.error(new YtMusicUnavailableException("no such route")));

        StepVerifier.create(searchService.searchPlaylists("Britpop", 20))
                .assertNext(response -> assertEquals(List.of("PL1"), ids(response.getPlaylists())))
                .verifyComplete();
    }

    @Test
    void searchPlaylists_fanMadeSearchFailing_isStillAnError() {
        when(ytMusicService.getResults("Britpop", YtMusicSearchType.PLAYLISTS, 20)).thenReturn(Mono.error(new YtMusicUnavailableException("down")));
        when(ytMusicService.getResults("Britpop", YtMusicSearchType.FEATURED_PLAYLISTS)).thenReturn(Mono.just(playlistsOnly(playlist("RDCLAK5uy_1", "Cool Britannia"))));

        StepVerifier.create(searchService.searchPlaylists("Britpop", 20))
                .expectError(YtMusicUnavailableException.class)
                .verify();
    }

    @Test
    void mix_pinsTopTwoOfEachSourceInOrder_thenShufflesTheRestOfBoth() {
        List<Playlist> featured = List.of(playlist("F1", "f1"), playlist("F2", "f2"), playlist("F3", "f3"), playlist("F4", "f4"));
        List<Playlist> fanMade = List.of(playlist("M1", "m1"), playlist("M2", "m2"), playlist("M3", "m3"), playlist("M4", "m4"));

        List<String> mixed = ids(SearchService.mix(featured, fanMade, new Random(7)));

        assertEquals(List.of("F1", "F2", "M1", "M2"), mixed.subList(0, 4));
        assertEquals(Set.of("F3", "F4", "M3", "M4"), new HashSet<>(mixed.subList(4, 8)));
        assertEquals(8, mixed.size());
    }

    @Test
    void mix_shufflesTheTailDifferentlyForDifferentSeeds_butNeverTheHead() {
        List<Playlist> featured = List.of(playlist("F1", "f1"), playlist("F2", "f2"), playlist("F3", "f3"), playlist("F4", "f4"), playlist("F5", "f5"));
        List<Playlist> fanMade = List.of(playlist("M1", "m1"), playlist("M2", "m2"), playlist("M3", "m3"), playlist("M4", "m4"), playlist("M5", "m5"));

        Set<List<String>> tails = new HashSet<>();
        for (int seed = 0; seed < 20; seed++) {
            List<String> mixed = ids(SearchService.mix(featured, fanMade, new Random(seed)));
            assertEquals(List.of("F1", "F2", "M1", "M2"), mixed.subList(0, 4));
            tails.add(mixed.subList(4, mixed.size()));
        }
        assertTrue(tails.size() > 1, "the tail should not come out in the same order every time");
        assertTrue(tails.stream().anyMatch(tail -> tail.get(0).startsWith("M")), "fan-made playlists should sometimes lead the tail: both pools are shuffled together, not one after the other");
    }

    @Test
    void mix_shortLists_takeWhatThereIs() {
        assertEquals(List.of("F1"), ids(SearchService.mix(List.of(playlist("F1", "f1")), List.of(), new Random(1))));
        assertEquals(List.of("M1"), ids(SearchService.mix(List.of(), List.of(playlist("M1", "m1")), new Random(1))));
        assertEquals(List.of(), ids(SearchService.mix(List.of(), List.of(), new Random(1))));
    }

    @Test
    void search_zeroResults_yieldsEmptyListsNotAnError() {
        SearchResponse empty = new SearchResponse(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        when(ytMusicService.getResults(eq("zzzzzzzznotarealthing"), any(), anyInt())).thenReturn(Mono.just(empty));
        when(ytMusicService.getResults(eq("zzzzzzzznotarealthing"), any())).thenReturn(Mono.just(empty));
        when(ytMusicService.getResults("zzzzzzzznotarealthing")).thenReturn(Mono.just(empty));

        StepVerifier.create(searchService.search("zzzzzzzznotarealthing"))
                .assertNext(response -> {
                    assertTrue(response.getTracks().isEmpty());
                    assertTrue(response.getAlbums().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                    assertTrue(response.getPlaylists().isEmpty());
                })
                .verifyComplete();
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
