package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Playlist;
import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicSearchType;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Goes through the HTTP layer on purpose: the client is built against the JSON shape, so the key
 * names ({@code iconURL} vs {@code iconUrl}), the empty-not-null lists and the 404/502 handlers are
 * the contract, not the Java record.
 */
class ArtistControllerTest {

    private static final String PIXIES = "UCRt5ckI8kNVMFr-jyj1IIVg";

    private final YtMusicService ytMusicService = mock(YtMusicService.class);
    private final WebTestClient client = WebTestClient.bindToController(new ArtistController(ytMusicService)).build();

    /** The adapter's real answer for Pixies, trimmed — see src/test/resources/ytmusic/artist-pixies.json. */
    static YtMusicDetailResponse.Artist pixies() {
        try (var in = ArtistControllerTest.class.getResourceAsStream("/ytmusic/artist-pixies.json")) {
            return JsonMapper.builder().build().readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), YtMusicDetailResponse.Artist.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SearchResponse playlists(int count) {
        return new SearchResponse(List.of(), List.of(), List.of(), IntStream.range(0, count)
                .mapToObj(i -> playlist("PL" + i, "Playlist " + i))
                .toList());
    }

    private static Playlist playlist(String id, String title) {
        return new Playlist(id, "https://img/" + id + ".jpg", title, List.of("YouTube Music"), 20);
    }

    private static List<String> ids(List<Playlist> playlists) {
        return playlists.stream().map(Playlist::getId).toList();
    }

    /** A playlist's track list as the adapter answers it: one song per given artist name. */
    private static YoutubeCollectionInfo tracksBy(String id, String... artists) {
        return new YoutubeCollectionInfo(id, Arrays.stream(artists)
                .map(artist -> new YoutubeSongInfo("v-" + artist, List.of(artist), "Song by " + artist, null, null))
                .toList(), null, "Playlist " + id, List.of("YouTube Music"), null);
    }

    /** Every playlist opened has a track by {@code artist}, so the track-list check keeps them all. */
    private void everyTrackListNames(String artist) {
        when(ytMusicService.getPlaylistInfo(anyString()))
                .thenAnswer(call -> Mono.just(tracksBy(call.getArgument(0), "Someone Else", artist)));
    }

    @Test
    void realAdapterShape_mapsOntoTheSearchDtos_withTheContractsKeyNames() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(pixies()));
        when(ytMusicService.getResults("Pixies", YtMusicSearchType.FEATURED_PLAYLISTS, 20)).thenReturn(Mono.just(playlists(12)));
        everyTrackListNames("Pixies");

        client.get().uri("/artists/{id}", PIXIES).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(PIXIES)
                .jsonPath("$.name").isEqualTo("Pixies")
                .jsonPath("$.iconURL").value(url -> ((String) url).startsWith("https://lh3.googleusercontent.com/"))
                .jsonPath("$.description").value(d -> ((String) d).startsWith("The Pixies are an American"))
                .jsonPath("$.subscribers").isEqualTo("498K")
                // topSongs -> Track: predictable per-video thumbnail, no album link, names not ids
                .jsonPath("$.topSongs.length()").isEqualTo(3)
                .jsonPath("$.topSongs[0].id").isEqualTo("49FB9hhoO6c")
                .jsonPath("$.topSongs[0].name").isEqualTo("Where Is My Mind?")
                .jsonPath("$.topSongs[0].iconURL").isEqualTo("https://i.ytimg.com/vi/49FB9hhoO6c/hqdefault.jpg")
                .jsonPath("$.topSongs[0].streamURL").isEqualTo("")
                .jsonPath("$.topSongs[0].artists[0]").isEqualTo("Pixies")
                .jsonPath("$.topSongs[0].albumId").isEqualTo("")
                .jsonPath("$.topSongs[0].year").isEqualTo(0)
                // albums/singles -> Album: the artist's own name stands in for the missing artists
                .jsonPath("$.albums.length()").isEqualTo(2)
                .jsonPath("$.albums[0].id").isEqualTo("MPREb_sfqbxrgS5Jp")
                .jsonPath("$.albums[0].name").isEqualTo("Bossanova (2026 Remaster)")
                .jsonPath("$.albums[0].artists").isEqualTo(List.of("Pixies"))
                .jsonPath("$.albums[0].year").isEqualTo(2026)
                .jsonPath("$.albums[0].iconURL").value(url -> ((String) url).startsWith("https://yt3.googleusercontent.com/"))
                .jsonPath("$.singles.length()").isEqualTo(1)
                .jsonPath("$.singles[0].id").isEqualTo("MPREb_NdMCXhqvP6F")
                // playlists: the featured-playlist search, capped at 10
                .jsonPath("$.playlists.length()").isEqualTo(10)
                .jsonPath("$.playlists[0].id").isEqualTo("PL0")
                .jsonPath("$.playlists[0].trackCount").isEqualTo(20)
                // similarArtists -> Artist: lowercase iconUrl carries the adapter's thumbnail, "" when null
                .jsonPath("$.similarArtists.length()").isEqualTo(2)
                .jsonPath("$.similarArtists[0].id").isEqualTo("UCauJZDRVzqj1QdLAQkUuq5w")
                .jsonPath("$.similarArtists[0].name").isEqualTo("The Breeders")
                .jsonPath("$.similarArtists[0].iconUrl").isEqualTo("https://lh3.googleusercontent.com/3rBv8kN2ZqYlF0wXcJ7pQm4tHs9uVdRaEoLgKiTnMbWyPcSxUfZjAhGqDe6vOl1rN5kIwB8tCyXm=w540-h225-p-l90-rj")
                .jsonPath("$.similarArtists[0].iconURL").doesNotExist()
                .jsonPath("$.similarArtists[1].name").isEqualTo("Frank Black")
                .jsonPath("$.similarArtists[1].iconUrl").isEqualTo("");

        verify(ytMusicService).getResults("Pixies", YtMusicSearchType.FEATURED_PLAYLISTS, 20);
    }

    @Test
    void topSongs_carryYouTubesPlayCount_asWorded_andNullWhereTheAdapterHasNone() {
        YtMusicDetailResponse.Artist artist = pixies();
        artist.getTopSongs().get(0).setViews("311M plays");
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(artist));
        when(ytMusicService.getResults(anyString(), any(), anyInt())).thenReturn(Mono.just(playlists(0)));

        client.get().uri("/artists/{id}", PIXIES).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.topSongs[0].plays").isEqualTo("311M plays")
                .jsonPath("$.topSongs[1].plays").isEmpty();
    }

    /**
     * The real answer for Oasis is "Presenting Oasis" and "Oasis 2025 Setlist" next to "'70s Lite
     * Hits" and "Presenting The Kooks" (a related act). The fixture's related artists are The
     * Breeders and Frank Black.
     */
    @Test
    void playlistsTitledAfterTheArtistOrARelatedArtist_areDropped_theRestKeepYouTubesOrder() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(pixies()));
        when(ytMusicService.getResults("Pixies", YtMusicSearchType.FEATURED_PLAYLISTS, 20)).thenReturn(Mono.just(
                new SearchResponse(List.of(), List.of(), List.of(), List.of(
                        playlist("own", "Presenting Pixies"),
                        playlist("keep1", "'90s Alt Rock Anthems"),
                        playlist("related", "The Breeders Essentials"),
                        playlist("keep2", "Indie Sing-Alongs"),
                        playlist("related2", "presenting frank black"),
                        playlist("keep3", "College Rock Classics")))));
        everyTrackListNames("Pixies");

        client.get().uri("/artists/{id}", PIXIES).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.playlists.length()").isEqualTo(3)
                .jsonPath("$.playlists[0].id").isEqualTo("keep1")
                .jsonPath("$.playlists[1].id").isEqualTo("keep2")
                .jsonPath("$.playlists[2].id").isEqualTo("keep3");

        // the title filter runs first, so a "Presenting Pixies" never costs an adapter call
        verify(ytMusicService, never()).getPlaylistInfo("own");
        verify(ytMusicService, never()).getPlaylistInfo("related");
    }

    @Test
    void theShelfCapsAtTen_afterFiltering_andOnlyTheFirstTwelveCandidatesAreOpened() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(pixies()));
        List<Playlist> twenty = new java.util.ArrayList<>();
        IntStream.range(0, 5).forEach(i -> twenty.add(playlist("own" + i, "Pixies Mix " + i)));
        IntStream.range(0, 15).forEach(i -> twenty.add(playlist("keep" + i, "Mixtape " + i)));
        when(ytMusicService.getResults("Pixies", YtMusicSearchType.FEATURED_PLAYLISTS, 20))
                .thenReturn(Mono.just(new SearchResponse(List.of(), List.of(), List.of(), twenty)));
        everyTrackListNames("Pixies");

        client.get().uri("/artists/{id}", PIXIES).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.playlists.length()").isEqualTo(10)
                .jsonPath("$.playlists[0].id").isEqualTo("keep0")
                .jsonPath("$.playlists[9].id").isEqualTo("keep9");

        verify(ytMusicService, times(12)).getPlaylistInfo(anyString());
        verify(ytMusicService).getPlaylistInfo("keep11");
        verify(ytMusicService, never()).getPlaylistInfo("keep12");
    }

    /**
     * The real answer for Oasis: "Summer House" (131 tracks, no Oasis) and "'70s Lite Hits" pass the
     * title filter but the artist is not in them; "Supersonic Sing-Along" really is. The match is the
     * whole folded name — "Pixies Tribute Band" is not Pixies, but "pixies" and "PIXIES" are — and
     * the slow first lookup must not let a faster one overtake it.
     */
    @Test
    void aCandidateWhoseTrackListNeverNamesTheArtist_isDropped_theRestKeepYouTubesOrder() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(pixies()));
        when(ytMusicService.getResults("Pixies", YtMusicSearchType.FEATURED_PLAYLISTS, 20)).thenReturn(Mono.just(
                new SearchResponse(List.of(), List.of(), List.of(), List.of(
                        playlist("summer", "Summer House"),
                        playlist("sing", "Supersonic Sing-Along"),
                        playlist("lite", "'70s Lite Hits"),
                        playlist("indie", "Indie Mix")))));
        when(ytMusicService.getPlaylistInfo("summer")).thenReturn(Mono.just(tracksBy("summer", "Kygo", "Pixies Tribute Band")));
        when(ytMusicService.getPlaylistInfo("sing")).thenReturn(Mono.just(tracksBy("sing", "Blur", "pixies")).delayElement(Duration.ofMillis(200)));
        when(ytMusicService.getPlaylistInfo("lite")).thenReturn(Mono.just(tracksBy("lite", "ABBA", "Carpenters")));
        when(ytMusicService.getPlaylistInfo("indie")).thenReturn(Mono.just(tracksBy("indie", "PIXIES")));

        client.get().uri("/artists/{id}", PIXIES).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.playlists.length()").isEqualTo(2)
                .jsonPath("$.playlists[0].id").isEqualTo("sing")
                .jsonPath("$.playlists[1].id").isEqualTo("indie");
    }

    @Test
    void aCandidateThatFailsToOpen_isDropped_theOthersSurvive() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(pixies()));
        when(ytMusicService.getResults("Pixies", YtMusicSearchType.FEATURED_PLAYLISTS, 20)).thenReturn(Mono.just(
                new SearchResponse(List.of(), List.of(), List.of(), List.of(
                        playlist("a", "Mixtape A"),
                        playlist("gone", "Mixtape Gone"),
                        playlist("c", "Mixtape C")))));
        everyTrackListNames("Pixies");
        when(ytMusicService.getPlaylistInfo("gone")).thenReturn(Mono.error(new YtMusicBadRequestException("No playlist found")));

        client.get().uri("/artists/{id}", PIXIES).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.playlists.length()").isEqualTo(2)
                .jsonPath("$.playlists[0].id").isEqualTo("a")
                .jsonPath("$.playlists[1].id").isEqualTo("c");
    }

    @Test
    void titleMatching_ignoresCaseAccentsAndPunctuation_andSkipsNamesThatFoldToNothing() {
        List<Playlist> result = ArtistController.withoutTitledAfter(List.of(
                        playlist("accent", "BEYONCE: The Hits"),
                        playlist("punct", "Best of AC/DC"),
                        playlist("spaced", "A C D C Live"),
                        playlist("keep", "Pop Party"),
                        playlist("nullTitle", null)),
                java.util.Arrays.asList("Beyoncé", "ACDC", "***", "", null));

        assertEquals(List.of("keep", "nullTitle"), ids(result));
    }

    /** Blur, Muse, Air, Live, Kiss: a substring test would drop half of YouTube's catalogue for them. */
    @Test
    void shortNames_matchWholeWordsOnly_soBlurKeepsBlurredLines() {
        List<Playlist> result = ArtistController.withoutTitledAfter(List.of(
                        playlist("blur1", "Blurred Lines Party"),
                        playlist("blur2", "Presenting Blur"),
                        playlist("muse", "Amusement Park Hits"),
                        playlist("kooks1", "Presenting The Kooks"),
                        playlist("kooks2", "Kooks Corner"),
                        playlist("acdc", "Best of AC/DC")),
                List.of("Blur", "Muse", "The Kooks", "ACDC"));

        assertEquals(List.of("blur1", "muse", "kooks2"), ids(result));
    }

    @Test
    void withoutTitledAfter_nullPlaylists_isAnEmptyList() {
        assertEquals(List.of(), ArtistController.withoutTitledAfter(null, List.of("Pixies")));
    }

    @Test
    void playlistSearchFailing_stillAnswersThePage_withAnEmptyShelf() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(pixies()));
        when(ytMusicService.getResults(anyString(), any(), anyInt())).thenReturn(Mono.error(new YtMusicUnavailableException("search down")));

        client.get().uri("/artists/{id}", PIXIES).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Pixies")
                .jsonPath("$.playlists").isArray()
                .jsonPath("$.playlists.length()").isEqualTo(0);
    }

    @Test
    void adapterListsMissing_becomeEmptyLists_neverNull_andNoNameMeansNoPlaylistSearch() {
        when(ytMusicService.getArtistInfo("UCbare")).thenReturn(Mono.just(YtMusicDetailResponse.Artist.builder()
                .channelId("UCbare").build()));

        client.get().uri("/artists/UCbare").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("UCbare")
                .jsonPath("$.name").isEqualTo("")
                .jsonPath("$.iconURL").isEqualTo("")
                .jsonPath("$.description").value(d -> { if (d != null) throw new AssertionError("description should be null"); })
                .jsonPath("$.topSongs").isEqualTo(List.of())
                .jsonPath("$.albums").isEqualTo(List.of())
                .jsonPath("$.singles").isEqualTo(List.of())
                .jsonPath("$.playlists").isEqualTo(List.of())
                .jsonPath("$.similarArtists").isEqualTo(List.of());

        verify(ytMusicService, never()).getResults(anyString(), any(), anyInt());
    }

    @Test
    void topSongs_dropUnavailableAndIdless_andCapAtTen() {
        List<YtMusicDetailResponse.Track> songs = new java.util.ArrayList<>();
        songs.add(YtMusicDetailResponse.Track.builder().videoId("blocked").title("Blocked").isAvailable(false).build());
        songs.add(YtMusicDetailResponse.Track.builder().videoId(null).title("No id").build());
        IntStream.range(0, 12).forEach(i -> songs.add(YtMusicDetailResponse.Track.builder().videoId("v" + i).title("Song " + i).build()));
        when(ytMusicService.getArtistInfo("UCmany")).thenReturn(Mono.just(YtMusicDetailResponse.Artist.builder()
                .channelId("UCmany").name("Many").topSongs(songs).build()));
        when(ytMusicService.getResults("Many", YtMusicSearchType.FEATURED_PLAYLISTS, 20)).thenReturn(Mono.just(playlists(0)));

        client.get().uri("/artists/UCmany").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.topSongs.length()").isEqualTo(10)
                .jsonPath("$.topSongs[0].id").isEqualTo("v0")
                .jsonPath("$.topSongs[9].id").isEqualTo("v9");
    }

    @Test
    void unknownId_is404() {
        when(ytMusicService.getArtistInfo("nope")).thenReturn(Mono.error(new YtMusicBadRequestException("No artist found")));

        client.get().uri("/artists/nope").exchange().expectStatus().isNotFound();
        verify(ytMusicService, never()).getResults(anyString(), any(), anyInt());
    }

    @Test
    void adapterDown_is502() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.error(new YtMusicUnavailableException("down")));

        client.get().uri("/artists/{id}", PIXIES).exchange().expectStatus().isEqualTo(502);
    }
}
