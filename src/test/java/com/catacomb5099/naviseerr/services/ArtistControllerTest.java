package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Playlist;
import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicSearchType;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
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
        List<Playlist> playlists = IntStream.range(0, count)
                .mapToObj(i -> new Playlist("PL" + i, "https://img/p" + i + ".jpg", "Playlist " + i, List.of("Someone"), 20))
                .toList();
        return new SearchResponse(List.of(), List.of(), List.of(), playlists);
    }

    @Test
    void realAdapterShape_mapsOntoTheSearchDtos_withTheContractsKeyNames() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(pixies()));
        when(ytMusicService.getResults("Pixies", YtMusicSearchType.PLAYLISTS)).thenReturn(Mono.just(playlists(12)));

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
                // playlists: the search result, capped at 10
                .jsonPath("$.playlists.length()").isEqualTo(10)
                .jsonPath("$.playlists[0].id").isEqualTo("PL0")
                .jsonPath("$.playlists[0].trackCount").isEqualTo(20)
                // similarArtists -> Artist: lowercase iconUrl carries the adapter's thumbnail, "" when null
                .jsonPath("$.similarArtists.length()").isEqualTo(2)
                .jsonPath("$.similarArtists[0].id").isEqualTo("UCauJZDRVzqj1QdLAQkUuq5w")
                .jsonPath("$.similarArtists[0].name").isEqualTo("The Breeders")
                .jsonPath("$.similarArtists[0].iconUrl").value(url -> ((String) url).startsWith("https://lh3.googleusercontent.com/"))
                .jsonPath("$.similarArtists[0].iconURL").doesNotExist()
                .jsonPath("$.similarArtists[1].name").isEqualTo("Frank Black")
                .jsonPath("$.similarArtists[1].iconUrl").isEqualTo("");

        verify(ytMusicService).getResults("Pixies", YtMusicSearchType.PLAYLISTS);
    }

    @Test
    void playlistSearchFailing_stillAnswersThePage_withAnEmptyShelf() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.just(pixies()));
        when(ytMusicService.getResults(anyString(), any())).thenReturn(Mono.error(new YtMusicUnavailableException("search down")));

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

        verify(ytMusicService, never()).getResults(anyString(), any());
    }

    @Test
    void topSongs_dropUnavailableAndIdless_andCapAtTen() {
        List<YtMusicDetailResponse.Track> songs = new java.util.ArrayList<>();
        songs.add(YtMusicDetailResponse.Track.builder().videoId("blocked").title("Blocked").isAvailable(false).build());
        songs.add(YtMusicDetailResponse.Track.builder().videoId(null).title("No id").build());
        IntStream.range(0, 12).forEach(i -> songs.add(YtMusicDetailResponse.Track.builder().videoId("v" + i).title("Song " + i).build()));
        when(ytMusicService.getArtistInfo("UCmany")).thenReturn(Mono.just(YtMusicDetailResponse.Artist.builder()
                .channelId("UCmany").name("Many").topSongs(songs).build()));
        when(ytMusicService.getResults("Many", YtMusicSearchType.PLAYLISTS)).thenReturn(Mono.just(playlists(0)));

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
        verify(ytMusicService, never()).getResults(anyString(), any());
    }

    @Test
    void adapterDown_is502() {
        when(ytMusicService.getArtistInfo(PIXIES)).thenReturn(Mono.error(new YtMusicUnavailableException("down")));

        client.get().uri("/artists/{id}", PIXIES).exchange().expectStatus().isEqualTo(502);
    }
}
