package com.catacomb5099.naviseerr.services.ytmusic;

import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class YtMusicServiceTest {

    private static final String SONGS_BODY = """
            {
              "query": "Oasis Wonderwall",
              "type": "songs",
              "count": 1,
              "items": [
                {
                  "type": "song",
                  "videoId": "hpSrLjc5SMs",
                  "browseId": null,
                  "playlistId": null,
                  "title": "Wonderwall",
                  "artists": [{"name": "Oasis", "channelId": "UCmMUZbaYdNH0bEd1PAlAqsA"}],
                  "album": {"name": "(What's The Story) Morning Glory?", "browseId": "MPREb_PITqkpE6ExP"},
                  "durationSeconds": 259,
                  "thumbnailUrl": "https://example.com/song.jpg",
                  "explicit": false,
                  "year": null
                }
              ]
            }
            """;

    private static final String MIXED_BODY = """
            {
              "query": "Oasis Wonderwall",
              "type": null,
              "count": 3,
              "items": [
                {
                  "type": "song",
                  "videoId": "hpSrLjc5SMs",
                  "browseId": null,
                  "playlistId": null,
                  "title": "Wonderwall",
                  "artists": [{"name": "Oasis", "channelId": "UCmMUZbaYdNH0bEd1PAlAqsA"}],
                  "album": null,
                  "durationSeconds": null,
                  "thumbnailUrl": "https://example.com/song.jpg",
                  "explicit": false,
                  "year": null
                },
                {
                  "type": "album",
                  "videoId": null,
                  "browseId": "MPREb_Hl8XJR59OrY",
                  "playlistId": "OLAK5uy_m--RCG58SjXLvgRiw0pASnMY6YjE8q3NU",
                  "title": "Definitely Maybe",
                  "artists": [{"name": "Oasis", "channelId": "UCmMUZbaYdNH0bEd1PAlAqsA"}],
                  "thumbnailUrl": "https://example.com/album.jpg",
                  "year": 1994
                },
                {
                  "type": "artist",
                  "videoId": null,
                  "browseId": "UCmMUZbaYdNH0bEd1PAlAqsA",
                  "title": "Oasis",
                  "artists": [],
                  "thumbnailUrl": "https://example.com/artist.jpg"
                }
              ]
            }
            """;

    private MockWebServer server;
    private YtMusicService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
        service = new YtMusicService(webClient);
        ReflectionTestUtils.setField(service, "searchResultLimit", 10);
        ReflectionTestUtils.setField(service, "mixedSearchLimit", 100);
        ReflectionTestUtils.setField(service, "timeoutMs", 2000L);
        ReflectionTestUtils.setField(service, "retryCount", 1);
        ReflectionTestUtils.setField(service, "firstBackOffDurationMs", 1L);
    }

    @AfterEach
    void tearDown() {
        try {
            server.shutdown();
        } catch (IOException ignored) {
            // already shut down by a test that closes the server itself
        }
    }

    @Test
    void getResults_happyPath_deserializesAndMapsSongsResponse() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .assertNext(response -> {
                    assertEquals(1, response.getTracks().size());
                    assertEquals("hpSrLjc5SMs", response.getTracks().get(0).getId());
                    assertTrue(response.getAlbums().isEmpty());
                    assertTrue(response.getArtists().isEmpty());
                })
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/search/songs?q=Oasis%20Wonderwall&limit=10", request.getPath());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_generalSearch_issuesExactlyOneUnfilteredRequest() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(MIXED_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall"))
                .assertNext(response -> {
                    assertEquals(1, response.getTracks().size());
                    assertEquals(1, response.getAlbums().size());
                    assertEquals(1, response.getArtists().size());
                })
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/search?q=Oasis%20Wonderwall&limit=100", request.getPath());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_typedSearch_stillUsesSearchResultLimit_notMixedSearchLimit() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .assertNext(response -> assertEquals(1, response.getTracks().size()))
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertTrue(request.getPath().contains("limit=10"));
        assertFalse(request.getPath().contains("limit=100"));
    }

    @Test
    void getResults_400WithDetailStringShape_mapsToBadRequest_andDoesNotRetry() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"Unsupported type 'bogus'. Must be one of: songs, videos, albums, artists, playlists\"}"));

        StepVerifier.create(service.getResults("x", YtMusicSearchType.SONGS))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(YtMusicBadRequestException.class, error);
                    assertTrue(error.getMessage().contains("Unsupported type"));
                })
                .verify();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_422WithDetailArrayShape_mapsToBadRequest_andDoesNotRetry() {
        server.enqueue(new MockResponse().setResponseCode(422)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"detail\":[{\"type\":\"missing\",\"loc\":[\"query\",\"q\"],\"msg\":\"Field required\",\"input\":null}]}"));

        StepVerifier.create(service.getResults("x", YtMusicSearchType.SONGS))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(YtMusicBadRequestException.class, error);
                    assertTrue(error.getMessage().contains("Field required"));
                })
                .verify();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_500EnvelopeShape_mapsToBadRequest_andDoesNotRetry() {
        server.enqueue(new MockResponse().setResponseCode(500)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"internal_auth_misuse\",\"message\":\"Internal error: attempted an auth-required call\"}}"));

        StepVerifier.create(service.getResults("x", YtMusicSearchType.SONGS))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(YtMusicBadRequestException.class, error);
                    assertEquals("Internal error: attempted an auth-required call", error.getMessage());
                })
                .verify();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_429EnvelopeShape_mapsToUnavailable_andRetriesToSuccess() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "30")
                .setBody("{\"error\":{\"code\":\"rate_limited\",\"message\":\"Upstream rate limit exceeded\"}}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .assertNext(response -> assertEquals(1, response.getTracks().size()))
                .verifyComplete();

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void getResults_502EnvelopeShape_mapsToUnavailable_andRetriesToSuccess() {
        server.enqueue(new MockResponse().setResponseCode(502)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"upstream_error\",\"message\":\"Upstream request failed\"}}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .assertNext(response -> assertEquals(1, response.getTracks().size()))
                .verifyComplete();

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void getResults_clientSideTimeout_mapsToUnavailable() {
        // retryCount=0 here: proving the retry filter itself accepts YtMusicUnavailableException
        // is already covered by the 429/502 tests above, without a timing-sensitive HTTP delay
        // that would otherwise interact with the WebClient connection pool on retry.
        ReflectionTestUtils.setField(service, "timeoutMs", 100L);
        ReflectionTestUtils.setField(service, "retryCount", 0);

        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBodyDelay(500, TimeUnit.MILLISECONDS)
                .setBody(SONGS_BODY));

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .expectError(YtMusicUnavailableException.class)
                .verify();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getResults_connectionRefused_mapsToUnavailable() throws IOException {
        server.shutdown();

        StepVerifier.create(service.getResults("Oasis Wonderwall", YtMusicSearchType.SONGS))
                .expectError(YtMusicUnavailableException.class)
                .verify();
    }

    // ---- metadata lookups (the download pipeline's only use of this provider) -------------------

    /** {@code GET /v1/songs/{videoId}}: `author` is a single string, not a list. */
    private static final String SONG_DETAIL_BODY = """
            {
              "videoId": "hpSrLjc5SMs",
              "title": "Wonderwall",
              "author": "Oasis",
              "channelId": "UCmMUZbaYdNH0bEd1PAlAqsA",
              "lengthSeconds": 259,
              "viewCount": 123456,
              "thumbnailUrl": "https://example.com/song.jpg"
            }
            """;

    /** {@code GET /v1/albums/{browseId}}: id is `browseId`, artists are a list, year is present. */
    private static final String ALBUM_DETAIL_BODY = """
            {
              "browseId": "MPREb_Hl8XJR59OrY",
              "title": "Definitely Maybe",
              "type": "Album",
              "year": 1994,
              "trackCount": 2,
              "artists": [{"name": "Oasis", "channelId": "UCmMUZbaYdNH0bEd1PAlAqsA"}],
              "thumbnailUrl": "https://example.com/album.jpg",
              "tracks": [
                {"videoId": "v1", "title": "Rock 'n' Roll Star",
                 "artists": [{"name": "Oasis", "channelId": "UC1"}], "trackNumber": 1},
                {"videoId": "v2", "title": "Shakermaker",
                 "artists": [{"name": "Oasis", "channelId": "UC1"}], "trackNumber": 2}
              ]
            }
            """;

    /** {@code GET /v1/playlists/{id}}: id is `id`, a single `author`, no year, tracks can be dead. */
    private static final String PLAYLIST_DETAIL_BODY = """
            {
              "id": "PL123",
              "title": "Britpop Essentials",
              "author": {"name": "YouTube Music", "channelId": "UC2"},
              "trackCount": 3,
              "thumbnailUrl": "https://example.com/playlist.jpg",
              "tracks": [
                {"videoId": "v1", "title": "Wonderwall",
                 "artists": [{"name": "Oasis", "channelId": "UC1"}], "isAvailable": true},
                {"videoId": "v2", "title": "Common People",
                 "artists": [{"name": "Pulp", "channelId": "UC3"}], "isAvailable": null},
                {"videoId": "v3", "title": "Taken Down",
                 "artists": [{"name": "Nobody", "channelId": "UC4"}], "isAvailable": false}
              ]
            }
            """;

    @Test
    void getSongInfo_flattensTheSingleAuthorIntoTheArtistList() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(SONG_DETAIL_BODY));

        StepVerifier.create(service.getSongInfo("hpSrLjc5SMs"))
                .assertNext(song -> {
                    assertEquals("hpSrLjc5SMs", song.id());
                    assertEquals("Wonderwall", song.name());
                    // The whole point of the flattening: callers never have to know that a song
                    // response says `author` where a collection's tracks say `artists`.
                    assertEquals(List.of("Oasis"), song.authorNames());
                })
                .verifyComplete();

        assertEquals("/v1/songs/hpSrLjc5SMs", server.takeRequest().getPath());
    }

    @Test
    void getSongInfo_withNoAuthor_reportsAnEmptyListNotNull() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"videoId\":\"v1\",\"title\":\"Untitled\",\"author\":null}"));

        StepVerifier.create(service.getSongInfo("v1"))
                .assertNext(song -> assertEquals(List.of(), song.authorNames()))
                .verifyComplete();
    }

    @Test
    void getAlbumInfo_mapsEveryTrackAndCarriesTheYear() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(ALBUM_DETAIL_BODY));

        StepVerifier.create(service.getAlbumInfo("MPREb_Hl8XJR59OrY"))
                .assertNext(album -> {
                    assertEquals("MPREb_Hl8XJR59OrY", album.id());
                    assertEquals("Definitely Maybe", album.name());
                    assertEquals("1994", album.year());
                    assertEquals(List.of("Oasis"), album.authorNames());
                    // One task row per entry here, so a dropped track is a song the user asked for
                    // and never gets.
                    assertEquals(List.of("v1", "v2"),
                            album.songs().stream().map(s -> s.id()).toList());
                    assertEquals("Rock 'n' Roll Star", album.songs().getFirst().name());
                })
                .verifyComplete();

        assertEquals("/v1/albums/MPREb_Hl8XJR59OrY", server.takeRequest().getPath());
    }

    @Test
    void getPlaylistInfo_readsItsOwnIdAndAuthorShape_andHasNoYear() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(PLAYLIST_DETAIL_BODY));

        StepVerifier.create(service.getPlaylistInfo("PL123"))
                .assertNext(playlist -> {
                    // A playlist reports `id`, not `browseId`, and one `author`, not `artists[]`.
                    assertEquals("PL123", playlist.id());
                    assertEquals("Britpop Essentials", playlist.name());
                    assertEquals(List.of("YouTube Music"), playlist.authorNames());
                    assertNull(playlist.year(), "only albums have a year");
                })
                .verifyComplete();

        assertEquals("/v1/playlists/PL123", server.takeRequest().getPath());
    }

    @Test
    void getPlaylistInfo_dropsATrackTheProviderSaysIsUnavailable_butKeepsAnUnknownOne() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(PLAYLIST_DETAIL_BODY));

        StepVerifier.create(service.getPlaylistInfo("PL123"))
                .assertNext(playlist -> assertEquals(List.of("v1", "v2"),
                        playlist.songs().stream().map(s -> s.id()).toList(),
                        "a region-blocked track would spend a whole search budget to fail, but an "
                                + "unknown availability (album responses omit the field) must not "
                                + "be treated as unavailable"))
                .verifyComplete();
    }

    @Test
    void getAlbumInfo_withNoTracks_reportsAnEmptyListRatherThanFailing() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"browseId\":\"MPREb_x\",\"title\":\"Empty\",\"tracks\":[]}"));

        // The caller decides what an empty collection means; this method's job is to report it
        // accurately, not to guess.
        StepVerifier.create(service.getAlbumInfo("MPREb_x"))
                .assertNext(album -> assertEquals(List.of(), album.songs()))
                .verifyComplete();
    }

    @Test
    void anUnknownId_mapsTo404_whichIsABadRequest_andIsNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(404)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"not_found\",\"message\":\"No song found for videoId 'nope'\"}}"));

        StepVerifier.create(service.getSongInfo("nope"))
                .expectErrorSatisfies(error -> {
                    // Deliberately NOT YtMusicUnavailableException. Retrying cannot make an id
                    // exist, and admission relies on this distinction to tell "fail this download"
                    // from "try again next pass" -- otherwise a mistyped id is re-requested every
                    // loop interval forever.
                    assertInstanceOf(YtMusicBadRequestException.class, error);
                    assertTrue(error.getMessage().contains("No song found"));
                })
                .verify();

        assertEquals(1, server.getRequestCount(), "a 404 must not be retried");
    }

    @Test
    void anUnavailableSidecar_isRetried_forMetadataToo() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(SONG_DETAIL_BODY));

        // The metadata calls share executeSearch's pipeline rather than reimplementing it, so the
        // retry policy AGENTS.md points at applies to them unchanged.
        StepVerifier.create(service.getSongInfo("hpSrLjc5SMs"))
                .assertNext(song -> assertEquals("Wonderwall", song.name()))
                .verifyComplete();

        assertEquals(2, server.getRequestCount());
    }
}
