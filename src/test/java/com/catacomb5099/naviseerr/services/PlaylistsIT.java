package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import com.catacomb5099.naviseerr.download.DownloadType;
import com.catacomb5099.naviseerr.download.TrackLists;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableAdvice;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The playlist routes over the real playlists table (V20); only YouTube (TrackLists) is a mock. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class PlaylistsIT {

    @Autowired PlaylistRepository repository;
    @Autowired TrackLists realTrackLists;
    @Autowired DatabaseClient db;

    private final TrackLists trackLists = mock(TrackLists.class);
    private WebTestClient client;

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToController(new PlaylistController(repository, trackLists))
                    .controllerAdvice(new YtMusicUnavailableAdvice()).configureClient()
                    .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();   // reads a picture back
        }
        return client;
    }

    private static YoutubeSongInfo song(String id, String image) {
        return new YoutubeSongInfo(id, List.of("Oasis"), List.of("UCoasis"), "Song " + id, image, 200, "1M plays", 3);
    }

    private void source(DownloadType type, String id, YoutubeSongInfo... songs) {
        when(trackLists.of(type, id)).thenReturn(Mono.just(
                new YoutubeCollectionInfo(id, List.of(songs), null, "Source", List.of("Oasis"), "https://img/source.jpg")));
    }

    private UUID newDraft(String title) {
        PlaylistView view = client().post().uri("/playlists").bodyValue(Map.of("title", title)).exchange()
                .expectStatus().isCreated().expectBody(PlaylistView.class).returnResult().getResponseBody();
        assertNotNull(view);
        return view.id();
    }

    private PlaylistView get(UUID id) {
        return client().get().uri("/playlists/" + id).exchange().expectStatus().isOk()
                .expectBody(PlaylistView.class).returnResult().getResponseBody();
    }

    private List<String> order(UUID id) {
        return get(id).tracks().stream().map(CollectionView.CollectionTrackView::id).toList();
    }

    private void add(UUID id, DownloadType type, String sourceId, List<String> songIds, int added, int alreadyIn, int count) {
        client().post().uri("/playlists/" + id + "/songs")
                .bodyValue(songIds == null ? Map.of("type", type, "id", sourceId) : Map.of("type", type, "id", sourceId, "songIds", songIds))
                .exchange().expectStatus().isOk()
                .expectBody().json("{\"added\":" + added + ",\"alreadyIn\":" + alreadyIn + ",\"trackCount\":" + count + "}");
    }

    @Test
    void aBlankTitleBecomesMyPlaylistN_andTheDraftStartsEmpty() {
        PlaylistView view = client().post().uri("/playlists").bodyValue(Map.of("title", " ")).exchange()
                .expectStatus().isCreated().expectBody(PlaylistView.class).returnResult().getResponseBody();
        assertNotNull(view);
        assertTrue(view.title().matches("My playlist #\\d+"), view.title());
        assertEquals(0, view.trackCount());
        assertEquals(List.of(), view.tracks());
        assertNull(view.downloadedAt());
        assertNull(view.coverUrl());
    }

    @Test
    void addingAppendsInTheSourcesOrder_skipsSongsAlreadyIn_andSaysHowMany() {
        UUID id = newDraft("Road trip");
        source(DownloadType.ALBUM, "MPREb_a", song("a", "https://img/1.jpg"), song("b", "https://img/1.jpg"),
                new YoutubeSongInfo(null, List.of("x"), "No id", null, null), song("c", "https://img/2.jpg"));
        source(DownloadType.PLAYLIST, "PLx", song("c", null), song("d", null), song("d", null), song("a", null));

        add(id, DownloadType.ALBUM, "MPREb_a", null, 3, 0, 3);
        add(id, DownloadType.PLAYLIST, "PLx", null, 1, 2, 4);

        assertEquals(List.of("a", "b", "c", "d"), order(id));
        PlaylistView view = get(id);
        assertNull(view.tracks().getFirst().plays(), "an album's play counts and track numbers are not kept");
        assertEquals(List.of(1, 2, 3, 4), view.tracks().stream().map(CollectionView.CollectionTrackView::position).toList());
    }

    @Test
    void onlyTheChosenSongsAreAdded_stillInTheSourcesOrder() {
        UUID id = newDraft("Subset");
        source(DownloadType.ALBUM, "MPREb_s", song("a", null), song("b", null), song("c", null));

        add(id, DownloadType.ALBUM, "MPREb_s", List.of("c", "a"), 2, 0, 2);

        assertEquals(List.of("a", "c"), order(id));
    }

    @Test
    void reorderPutsTheListedSongsFirst_keepsUnlistedOnesAfter_andIgnoresUnknownIds() {
        UUID id = newDraft("Order");
        source(DownloadType.ALBUM, "MPREb_o", song("a", null), song("b", null), song("c", null), song("d", null));
        add(id, DownloadType.ALBUM, "MPREb_o", null, 4, 0, 4);

        client().put().uri("/playlists/" + id + "/order").bodyValue(List.of("c", "zz", "a", "c")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.tracks[0].id").isEqualTo("c");

        assertEquals(List.of("c", "a", "b", "d"), order(id));
    }

    @Test
    void removeTakesOneSongOut() {
        UUID id = newDraft("Remove");
        source(DownloadType.ALBUM, "MPREb_r", song("a", null), song("b", null), song("c", null));
        add(id, DownloadType.ALBUM, "MPREb_r", null, 3, 0, 3);

        client().delete().uri("/playlists/" + id + "/songs/b").exchange().expectStatus().isOk();

        assertEquals(List.of("a", "c"), order(id));
    }

    @Test
    void renameAndDescribe_withSpotifysLimits() {
        UUID id = newDraft("Old");
        client().patch().uri("/playlists/" + id).bodyValue(Map.of("title", " New ", "description", "For the car")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.title").isEqualTo("New")
                .jsonPath("$.description").isEqualTo("For the car");
        client().patch().uri("/playlists/" + id).bodyValue(Map.of("description", "Only this")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.title").isEqualTo("New");

        client().patch().uri("/playlists/" + id).bodyValue(Map.of("title", "")).exchange().expectStatus().isBadRequest();
        client().patch().uri("/playlists/" + id).bodyValue(Map.of("title", "x".repeat(101))).exchange().expectStatus().isBadRequest();
        client().patch().uri("/playlists/" + id).bodyValue(Map.of("description", "x".repeat(301))).exchange().expectStatus().isBadRequest();
    }

    @Test
    void theListIsLastEditedFirst_withAMosaicOfFourDifferentPictures_andNoTracks() {
        UUID older = newDraft("Older");
        UUID newer = newDraft("Newer");
        source(DownloadType.ALBUM, "MPREb_m", song("a", "https://img/1.jpg"), song("b", "https://img/1.jpg"),
                song("c", "https://img/2.jpg"), song("d", null), song("e", "https://img/3.jpg"),
                song("f", "https://img/4.jpg"), song("g", "https://img/5.jpg"));
        add(older, DownloadType.ALBUM, "MPREb_m", null, 7, 0, 7);   // editing it makes it the latest

        client().get().uri("/playlists").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].id").isEqualTo(older.toString())
                .jsonPath("$[0].trackCount").isEqualTo(7)
                .jsonPath("$[0].mosaic").isEqualTo(List.of("https://img/1.jpg", "https://img/2.jpg", "https://img/3.jpg", "https://img/4.jpg"))
                .jsonPath("$[0].tracks").doesNotExist()
                .jsonPath("$[1].id").isEqualTo(newer.toString());
    }

    @Test
    void aCreatedPlaylistRefusesEveryEdit_butCanBeDuplicated() {
        UUID id = newDraft("Frozen");
        source(DownloadType.ALBUM, "MPREb_f", song("a", null), song("b", null));
        add(id, DownloadType.ALBUM, "MPREb_f", null, 2, 0, 2);
        db.sql("UPDATE playlists SET downloaded_at = now() WHERE playlist_id = :id").bind("id", id).fetch().rowsUpdated().block();
        clearInvocations(trackLists);

        client().patch().uri("/playlists/" + id).bodyValue(Map.of("title", "x")).exchange().expectStatus().isEqualTo(409);
        client().post().uri("/playlists/" + id + "/songs").bodyValue(Map.of("type", "ALBUM", "id", "MPREb_f")).exchange()
                .expectStatus().isEqualTo(409);
        client().put().uri("/playlists/" + id + "/order").bodyValue(List.of("b", "a")).exchange().expectStatus().isEqualTo(409);
        client().delete().uri("/playlists/" + id + "/songs/a").exchange().expectStatus().isEqualTo(409);
        client().delete().uri("/playlists/" + id).exchange().expectStatus().isEqualTo(409);
        verifyNoInteractions(trackLists);
        assertEquals(List.of("a", "b"), order(id));

        PlaylistView copy = client().post().uri("/playlists/" + id + "/copy").exchange().expectStatus().isCreated()
                .expectBody(PlaylistView.class).returnResult().getResponseBody();
        assertNotNull(copy);
        assertEquals("Copy of Frozen", copy.title());
        assertNull(copy.downloadedAt(), "the copy is a draft");
        assertEquals(List.of("a", "b"), order(copy.id()));
    }

    @Test
    void aDraftCanBeDeleted_andUnknownIdsAre404() {
        UUID id = newDraft("Gone");
        client().delete().uri("/playlists/" + id).exchange().expectStatus().isNoContent();
        client().get().uri("/playlists/" + id).exchange().expectStatus().isNotFound();
        client().patch().uri("/playlists/" + id).bodyValue(Map.of("title", "x")).exchange().expectStatus().isNotFound();
        client().delete().uri("/playlists/" + id).exchange().expectStatus().isNotFound();
        client().post().uri("/playlists/" + id + "/copy").exchange().expectStatus().isNotFound();
        client().post().uri("/playlists/" + id + "/songs").bodyValue(Map.of("type", "SONG", "id", "a")).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void moreThanFiveThousandSongsIsRefused() {
        UUID id = newDraft("Huge");
        source(DownloadType.PLAYLIST, "PLhuge", IntStream.rangeClosed(1, PlaylistRepository.MAX_SONGS + 1)
                .mapToObj(i -> song("v" + i, null)).toArray(YoutubeSongInfo[]::new));

        client().post().uri("/playlists/" + id + "/songs").bodyValue(Map.of("type", "PLAYLIST", "id", "PLhuge")).exchange()
                .expectStatus().isBadRequest();
        assertEquals(0, get(id).trackCount(), "nothing is half-added");
    }

    @Test
    void aSavedPlaylistIsATrackListLikeAnyOther_inYourOrder() {
        UUID id = newDraft("As a source");
        source(DownloadType.ALBUM, "MPREb_t", song("a", null), song("b", null));
        add(id, DownloadType.ALBUM, "MPREb_t", null, 2, 0, 2);
        client().put().uri("/playlists/" + id + "/order").bodyValue(List.of("b")).exchange().expectStatus().isOk();

        YoutubeCollectionInfo list = realTrackLists.of(DownloadType.CUSTOM, id.toString()).block();

        assertNotNull(list);
        assertEquals("As a source", list.name());
        assertEquals(List.of("b", "a"), list.songs().stream().map(YoutubeSongInfo::id).toList());
        assertEquals(List.of(), list.authorNames(), "no user names on a download");
        assertEquals(List.of(), realTrackLists.of(DownloadType.CUSTOM, "not-a-uuid").block().songs());
    }

    private static byte[] jpeg(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 7);
        bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xD8; bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private WebTestClient.ResponseSpec putCover(UUID id, MediaType type, byte[] body) {
        return client().put().uri("/playlists/" + id + "/cover").contentType(type).bodyValue(body).exchange();
    }

    @Test
    void aPictureIsStoredAndServedAsAJpeg_withANewAddressEachTime_andCanBeRemoved() {
        UUID id = newDraft("Pictured");
        byte[] picture = jpeg(300 * 1024);   // past WebFlux's 256 KB codec limit, as a real upload is

        PlaylistView set = putCover(id, MediaType.IMAGE_JPEG, picture).expectStatus().isOk()
                .expectBody(PlaylistView.class).returnResult().getResponseBody();
        assertNotNull(set);
        assertTrue(set.coverUrl().startsWith("/playlists/" + id + "/cover?v="), set.coverUrl());

        byte[] served = client().get().uri(set.coverUrl()).exchange().expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_JPEG)
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Cache-Control", "max-age=31536000, private, immutable")
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertArrayEquals(picture, served);

        String second = putCover(id, MediaType.IMAGE_JPEG, jpeg(1000)).expectStatus().isOk()
                .expectBody(PlaylistView.class).returnResult().getResponseBody().coverUrl();
        assertNotEquals(set.coverUrl(), second, "a new picture is a new address, so no browser shows the old one");

        PlaylistView copy = client().post().uri("/playlists/" + id + "/copy").exchange().expectStatus().isCreated()
                .expectBody(PlaylistView.class).returnResult().getResponseBody();
        assertNotNull(copy.coverUrl(), "Duplicate keeps the picture");

        client().delete().uri("/playlists/" + id + "/cover").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.coverUrl").isEmpty();
        client().get().uri("/playlists/" + id + "/cover").exchange().expectStatus().isNotFound();
    }

    @Test
    void anythingButAJpegUnder4MbIsRefused_andACreatedPlaylistKeepsItsPicture() {
        UUID id = newDraft("Picky");
        putCover(id, MediaType.IMAGE_JPEG, "<svg/>".getBytes()).expectStatus().isBadRequest();
        putCover(id, MediaType.IMAGE_JPEG, new byte[0]).expectStatus().isBadRequest();
        putCover(id, MediaType.IMAGE_PNG, jpeg(1000)).expectStatus().isEqualTo(415);
        putCover(id, MediaType.IMAGE_JPEG, jpeg(PlaylistController.MAX_COVER_BYTES + 1)).expectStatus().isBadRequest();
        assertNull(get(id).coverUrl());

        db.sql("UPDATE playlists SET downloaded_at = now() WHERE playlist_id = :id").bind("id", id).fetch().rowsUpdated().block();
        putCover(id, MediaType.IMAGE_JPEG, jpeg(1000)).expectStatus().isEqualTo(409);
        client().delete().uri("/playlists/" + id + "/cover").exchange().expectStatus().isEqualTo(409);
    }
}
