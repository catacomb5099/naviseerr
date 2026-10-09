package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Duration RETENTION = Duration.ofMinutes(10);

    private DownloadService downloadService;
    private ActiveDownloadRepository activeDownloadRepository;
    private DownloadController controller;

    @BeforeEach
    void setUp() {
        downloadService = mock(DownloadService.class);
        activeDownloadRepository = mock(ActiveDownloadRepository.class);
        controller = new DownloadController(downloadService, activeDownloadRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5), RETENTION);
        // Nothing already exists unless a test says so; Mockito's default for a Mono is null.
        when(activeDownloadRepository.findCurrent(any(), any())).thenReturn(Mono.empty());
    }

    private static ActiveDownloadView view() {
        return new ActiveDownloadView(UUID.randomUUID(), "vid-1", DownloadType.SONG, "song",
                List.of("artist", "nobody"), Arrays.asList("UC-artist", null), "https://img/1.jpg",
                DownloadStage.DOWNLOADING,
                new BigDecimal("43.00"), 1, 0, 0, 0, NOW, NOW, NOW, null, null);
    }

    private static DownloadSongView song() {
        return new DownloadSongView(UUID.randomUUID(), "vid-1", 1, "song", List.of("artist"),
                List.of("UC-artist"), "https://img/1.jpg", 200, DownloadStage.DOWNLOADING, new BigDecimal("43.00"), null,
                NOW, NOW, null, 3, 0, 0, "alice", "music/alice/song.flac", null, null);
    }

    // ---- cancel ----------------------------------------------------------------------------------

    @Test
    void cancel_whenSomethingWasCancelled_is200WithTheFreshCard() {
        ActiveDownloadView card = view();
        when(downloadService.cancel(card.downloadId(), null, NOW)).thenReturn(Mono.just(2L));
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

        ResponseEntity<ActiveDownloadView> response = controller.cancel(card.downloadId(), null).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(card, response.getBody());
    }

    @Test
    void cancel_whenNothingWasLeftToCancel_is409WithTheCurrentCard() {
        ActiveDownloadView card = view();
        when(downloadService.cancel(card.downloadId(), null, NOW)).thenReturn(Mono.just(0L));
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

        ResponseEntity<ActiveDownloadView> response = controller.cancel(card.downloadId(), null).block();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(card, response.getBody());   // the client applies the 409 body
    }

    @Test
    void cancel_ofAnUnknownDownload_is404() {
        UUID unknown = UUID.randomUUID();
        when(downloadService.cancel(unknown, null, NOW)).thenReturn(Mono.just(0L));
        when(activeDownloadRepository.findByIds(List.of(unknown))).thenReturn(Flux.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.cancel(unknown, null).block().getStatusCode());
    }

    @Test
    void cancel_ofOneSong_passesTheTaskIdThrough() {
        ActiveDownloadView card = view();
        UUID taskId = UUID.randomUUID();
        when(downloadService.cancel(card.downloadId(), taskId, NOW)).thenReturn(Mono.just(1L));
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

        assertEquals(HttpStatus.OK, controller.cancel(card.downloadId(), taskId).block().getStatusCode());
        verify(downloadService).cancel(card.downloadId(), taskId, NOW);
    }

    // ---- retry -----------------------------------------------------------------------------------

    @Test
    void retry_whenSomethingWasRetried_is202WithTheFreshCard() {
        ActiveDownloadView card = view();
        when(downloadService.retry(card.downloadId(), null, NOW)).thenReturn(Mono.just(1L));
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

        ResponseEntity<ActiveDownloadView> response = controller.retry(card.downloadId(), null).block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(card, response.getBody());
    }

    @Test
    void retry_whenNothingToRetry_is409WithTheCurrentCard() {
        ActiveDownloadView card = view();
        when(downloadService.retry(card.downloadId(), null, NOW)).thenReturn(Mono.just(0L));
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

        ResponseEntity<ActiveDownloadView> response = controller.retry(card.downloadId(), null).block();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(card, response.getBody());
    }

    @Test
    void retry_ofAnUnknownDownload_is404() {
        UUID unknown = UUID.randomUUID();
        when(downloadService.retry(unknown, null, NOW)).thenReturn(Mono.just(0L));
        when(activeDownloadRepository.findByIds(List.of(unknown))).thenReturn(Flux.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.retry(unknown, null).block().getStatusCode());
    }

    @Test
    void retry_ofOneSong_passesTheTaskIdThrough() {
        ActiveDownloadView card = view();
        UUID taskId = UUID.randomUUID();
        when(downloadService.retry(card.downloadId(), taskId, NOW)).thenReturn(Mono.just(1L));
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

        assertEquals(HttpStatus.ACCEPTED, controller.retry(card.downloadId(), taskId).block().getStatusCode());
        verify(downloadService).retry(card.downloadId(), taskId, NOW);
    }

    // ---- one download, every song ----------------------------------------------------------------

    @Test
    void downloadDetail_returnsTheCardAndItsSongs() {
        ActiveDownloadView card = view();
        DownloadSongView song = song();
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));
        when(activeDownloadRepository.findSongs(card.downloadId())).thenReturn(Flux.just(song));

        ResponseEntity<DownloadDetailView> response = controller.downloadDetail(card.downloadId()).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(card, response.getBody().download());
        assertEquals(List.of(song), response.getBody().songs());
    }

    @Test
    void downloadDetail_putsOneArtistIdPerName_onTheWire_nullWhereThereIsNone() {
        ActiveDownloadView card = view();
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));
        when(activeDownloadRepository.findSongs(card.downloadId())).thenReturn(Flux.just(song()));

        // Through the JSON layer on purpose: the key name and the kept null ARE the client contract.
        WebTestClient.bindToController(controller).build()
                .get().uri("/downloads/" + card.downloadId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.download.artists.length()").isEqualTo(2)
                .jsonPath("$.download.artistIds.length()").isEqualTo(2)
                .jsonPath("$.download.artistIds[0]").isEqualTo("UC-artist")
                .jsonPath("$.download.artistIds[1]").isEmpty()
                .jsonPath("$.songs[0].artistIds[0]").isEqualTo("UC-artist");
    }

    @Test
    void downloadDetail_forAnUnknownId_is404() {
        UUID unknown = UUID.randomUUID();
        when(activeDownloadRepository.findByIds(List.of(unknown))).thenReturn(Flux.empty());
        when(activeDownloadRepository.findSongs(unknown)).thenReturn(Flux.empty());

        // Unlike GET /downloads?ids=, a single-resource GET has exactly one thing to say about an
        // id it has no row for.
        assertEquals(HttpStatus.NOT_FOUND, controller.downloadDetail(unknown).block().getStatusCode());
    }

    @Test
    void downloadDetail_forAQueuedDownload_hasTheCardAndNoSongsYet() {
        ActiveDownloadView card = view();
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));
        when(activeDownloadRepository.findSongs(card.downloadId())).thenReturn(Flux.empty());

        ResponseEntity<DownloadDetailView> response = controller.downloadDetail(card.downloadId()).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().songs().isEmpty(), "not admitted yet: no task rows, not an error");
    }

    // ---- the lists a person picks a file from ----------------------------------------------------

    @Test
    void candidates_isTheListAsJson_withIsCurrentOnTheWire() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskCandidatesView list = new TaskCandidatesView(taskId, PickListStatus.READY, null, "Live Forever", "s-1", NOW,
                DownloadStage.DOWNLOADING, new TaskCandidatesView.Current("alice", "music/alice/song.flac"),
                List.of(new TaskCandidatesView.Candidate("alice", "music/alice/song.flac", 1000L, null, 240, "flac",
                        1_000_000, true, 0, "EXACT", true)));
        when(downloadService.candidates(id, taskId)).thenReturn(Mono.just(list));

        WebTestClient.bindToController(controller).build()
                .get().uri("/downloads/" + id + "/tasks/" + taskId + "/candidates")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("READY")
                .jsonPath("$.reason").isEmpty()
                .jsonPath("$.query").isEqualTo("Live Forever")
                .jsonPath("$.searchId").isEqualTo("s-1")
                .jsonPath("$.songStage").isEqualTo("DOWNLOADING")
                .jsonPath("$.current.username").isEqualTo("alice")
                .jsonPath("$.candidates[0].isCurrent").isEqualTo(true)
                .jsonPath("$.candidates[0].bitrateKbps").isEmpty()
                .jsonPath("$.candidates[0].lengthSeconds").isEqualTo(240);
    }

    @Test
    void candidates_ofAnUnknownSong_is404WithAMessage() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(downloadService.candidates(id, taskId)).thenReturn(Mono.empty());

        WebTestClient.bindToController(controller).build()
                .get().uri("/downloads/" + id + "/tasks/" + taskId + "/candidates")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.message").isNotEmpty();
    }

    @Test
    void albumCandidates_isTheFolderList_or404_or409ForAnythingButAnAlbum() {
        UUID id = UUID.randomUUID();
        AlbumCandidatesView list = new AlbumCandidatesView(id, PickListStatus.NONE, "NO_WHOLE_FOLDER", "Definitely Maybe",
                "s-2", NOW, 11, List.of());
        when(downloadService.albumCandidates(id)).thenReturn(Mono.just(list));
        WebTestClient http = WebTestClient.bindToController(controller).build();

        http.get().uri("/downloads/" + id + "/album-candidates").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("NONE")
                .jsonPath("$.reason").isEqualTo("NO_WHOLE_FOLDER")
                .jsonPath("$.searchId").isEqualTo("s-2")
                .jsonPath("$.searchedAt").isNotEmpty()
                .jsonPath("$.songCount").isEqualTo(11);

        when(downloadService.albumCandidates(id)).thenReturn(Mono.empty());
        http.get().uri("/downloads/" + id + "/album-candidates").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.message").isNotEmpty();

        when(downloadService.albumCandidates(id)).thenReturn(Mono.error(new DownloadService.NotAnAlbumException(id)));
        http.get().uri("/downloads/" + id + "/album-candidates").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.reason").isEqualTo("NOT_AN_ALBUM")
                .jsonPath("$.message").isNotEmpty();
    }

    // ---- picking a file by hand ------------------------------------------------------------------

    @Test
    void pick_whenTheSongWasRePointed_is202WithTheFreshCard() {
        ActiveDownloadView card = view();
        UUID taskId = UUID.randomUUID();
        when(downloadService.pick(card.downloadId(), taskId, "bob", "music/bob/song.flac", NOW)).thenReturn(Mono.just(1L));
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

        ResponseEntity<Object> response = controller.pick(card.downloadId(), taskId,
                new DownloadController.PickRequest("bob", "music/bob/song.flac", null)).block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(card, response.getBody());
    }

    @Test
    void pick_whenNothingChanged_is409WithTheCurrentCard() {
        ActiveDownloadView card = view();
        UUID taskId = UUID.randomUUID();
        when(downloadService.pick(any(), any(), any(), any(), any())).thenReturn(Mono.just(0L));
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));

        ResponseEntity<Object> response = controller.pick(card.downloadId(), taskId,
                new DownloadController.PickRequest("bob", "music/bob/song.flac", null)).block();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(card, response.getBody());
    }

    @Test
    void pick_withoutBothFields_is400_withoutWriting() {
        ResponseEntity<Object> response = controller.pick(UUID.randomUUID(), UUID.randomUUID(),
                new DownloadController.PickRequest("bob", " ", null)).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(downloadService, never()).pick(any(), any(), any(), any(), any());
    }

    @Test
    void pick_ofUnknownIds_is404WithAMessage() {
        UUID id = UUID.randomUUID();
        when(downloadService.pick(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        WebTestClient.bindToController(controller).build()
                .post().uri("/downloads/" + id + "/tasks/" + UUID.randomUUID() + "/pick")
                .bodyValue(new DownloadController.PickRequest("bob", "music/bob/song.flac", null))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.message").isNotEmpty();
    }

    @Test
    void albumPick_mapsTheSameWay_andAnythingButAnAlbumIs409NotAnAlbum() {
        ActiveDownloadView card = view();
        when(activeDownloadRepository.findByIds(List.of(card.downloadId()))).thenReturn(Flux.just(card));
        WebTestClient http = WebTestClient.bindToController(controller).build();

        when(downloadService.albumPick(card.downloadId(), "bob", "music\\bob\\DM", NOW)).thenReturn(Mono.just(11L));
        http.post().uri("/downloads/" + card.downloadId() + "/album-pick")
                .bodyValue(new DownloadController.PickRequest("bob", null, "music\\bob\\DM"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.downloadId").isEqualTo(card.downloadId().toString());

        http.post().uri("/downloads/" + card.downloadId() + "/album-pick")
                .bodyValue(new DownloadController.PickRequest("bob", "a file, not a folder", null))
                .exchange()
                .expectStatus().isBadRequest();

        when(downloadService.albumPick(any(), any(), any(), any()))
                .thenReturn(Mono.error(new DownloadService.NotAnAlbumException(card.downloadId())));
        http.post().uri("/downloads/" + card.downloadId() + "/album-pick")
                .bodyValue(new DownloadController.PickRequest("bob", null, "music\\bob\\DM"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.reason").isEqualTo("NOT_AN_ALBUM");
    }

    // ---- requesting a download -----------------------------------------------------------------

    @Test
    void downloadSong_acceptsTheIdAndAcksImmediately() {
        Download saved = Download.builder().downloadId(UUID.randomUUID()).build();
        when(downloadService.requestDownload("vid-1", DownloadType.SONG))
                .thenReturn(Mono.just(saved));

        ResponseEntity<?> response = controller.downloadSong("vid-1").block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(saved, response.getBody());
    }

    @Test
    void downloadSong_whenTheSameSongAlreadyHasADownload_is409WithItsCard_andInsertsNothing() {
        ActiveDownloadView existing = view();
        when(activeDownloadRepository.findCurrent(DownloadType.SONG, "vid-1")).thenReturn(Mono.just(existing));

        ResponseEntity<?> response = controller.downloadSong("vid-1").block();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(existing, response.getBody());   // the same card cancel and retry answer with
        verify(downloadService, never()).requestDownload(any(), any());
    }

    @Test
    void downloadSong_whenNothingMatches_acksAsBefore() {
        Download saved = Download.builder().downloadId(UUID.randomUUID()).build();
        when(activeDownloadRepository.findCurrent(DownloadType.SONG, "vid-1")).thenReturn(Mono.empty());
        when(downloadService.requestDownload("vid-1", DownloadType.SONG)).thenReturn(Mono.just(saved));

        ResponseEntity<?> response = controller.downloadSong("vid-1").block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(saved, response.getBody());
    }

    @Test
    void downloadCollection_checksForAnExistingDownloadByTheRequestedType() {
        ActiveDownloadView existing = view();
        when(activeDownloadRepository.findCurrent(DownloadType.ALBUM, "MPREb_1")).thenReturn(Mono.just(existing));

        ResponseEntity<?> response = controller.downloadCollection("MPREb_1", DownloadType.ALBUM).block();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        // Exact type + id: the same id requested as a PLAYLIST would be a different download.
        verify(activeDownloadRepository).findCurrent(DownloadType.ALBUM, "MPREb_1");
        verify(downloadService, never()).requestDownload(any(), any());
    }

    @Test
    void aFailedLookupIsReportedAsAServerError_notAsAnAck() {
        when(activeDownloadRepository.findCurrent(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("db down")));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                controller.downloadSong("vid-1").block().getStatusCode());
        verify(downloadService, never()).requestDownload(any(), any());
    }

    @Test
    void downloadSong_withABlankId_isRejectedWithoutWriting() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.downloadSong("  ").block().getStatusCode());
        verify(downloadService, never()).requestDownload(any(), any());
    }

    @Test
    void downloadCollection_recordsTheRequestedType() {
        Download saved = Download.builder().downloadId(UUID.randomUUID()).build();
        when(downloadService.requestDownload("MPREb_1", DownloadType.ALBUM))
                .thenReturn(Mono.just(saved));

        ResponseEntity<?> response =
                controller.downloadCollection("MPREb_1", DownloadType.ALBUM).block();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        // The type decides which ytmusic-adapter endpoint admission calls, so losing it here would
        // send an album id to the playlist route.
        verify(downloadService).requestDownload("MPREb_1", DownloadType.ALBUM);
    }

    @Test
    void downloadCollection_withTypeSong_isRejected() {
        // A single track has its own route; accepting SONG here would create a download the
        // collection branch of admission cannot resolve.
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.downloadCollection("vid-1", DownloadType.SONG).block().getStatusCode());
        verify(downloadService, never()).requestDownload(any(), any());
    }

    @Test
    void aFailedInsertIsReportedAsAServerError_notAsAnAck() {
        when(downloadService.requestDownload(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("db down")));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                controller.downloadSong("vid-1").block().getStatusCode());
    }

    @Test
    void activeDownloads_returnsThePollIntervalAndRetentionWindowAlongsideTheRows() {
        ActiveDownloadView view = view();
        when(activeDownloadRepository.findActive(any())).thenReturn(Flux.just(view));

        ActiveDownloadsResponse response = controller.activeDownloads().block();

        assertEquals(5000L, response.pollIntervalMs());
        // Published so the client's auto-dismiss delay and its memory of what the user dismissed can
        // both be sized to outlive this window instead of guessing at it.
        assertEquals(600_000L, response.terminalRetentionMs());
        assertEquals(List.of(view), response.downloads());
    }

    @Test
    void activeDownloads_asksForRowsFinishedWithinTheRetentionWindow() {
        when(activeDownloadRepository.findActive(any())).thenReturn(Flux.empty());

        controller.activeDownloads().block();

        verify(activeDownloadRepository).findActive(NOW.minus(RETENTION));
    }

    @Test
    void activeDownloads_withNothingInFlight_returnsAnEmptyList() {
        when(activeDownloadRepository.findActive(any())).thenReturn(Flux.empty());

        assertTrue(controller.activeDownloads().block().downloads().isEmpty());
    }

    @Test
    void downloadsByIds_returnsWhateverResolved() {
        ActiveDownloadView view = view();
        when(activeDownloadRepository.findByIds(anyList())).thenReturn(Flux.just(view));

        ResponseEntity<DownloadsByIdResponse> response =
                controller.downloadsByIds(List.of(view.downloadId())).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(view), response.getBody().downloads());
    }

    @Test
    void downloadsByIds_withNoIds_isRejectedWithoutQuerying() {
        ResponseEntity<DownloadsByIdResponse> response = controller.downloadsByIds(List.of()).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(activeDownloadRepository, never()).findByIds(anyList());
    }

    @Test
    void downloadsByIds_aboveTheCap_isRejectedWithoutQuerying() {
        // The only thing these read endpoints add that a caller could size themselves.
        List<UUID> tooMany = Stream.generate(UUID::randomUUID)
                .limit(DownloadController.MAX_RESOLVE_IDS + 1).toList();

        ResponseEntity<DownloadsByIdResponse> response = controller.downloadsByIds(tooMany).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(activeDownloadRepository, never()).findByIds(anyList());
    }

    @Test
    void downloadsByIds_atTheCap_isAccepted() {
        when(activeDownloadRepository.findByIds(anyList())).thenReturn(Flux.empty());
        List<UUID> exactly = Stream.generate(UUID::randomUUID)
                .limit(DownloadController.MAX_RESOLVE_IDS).toList();

        assertEquals(HttpStatus.OK, controller.downloadsByIds(exactly).block().getStatusCode());
    }

    // ---- the history table's type filter -------------------------------------------------------

    private static final AllDownloadsResponse EMPTY_PAGE = new AllDownloadsResponse(List.of(), 0);

    @Test
    void allDownloads_withNoType_asksForEverything() {
        when(activeDownloadRepository.findAll(any(), any(), any())).thenReturn(Mono.just(EMPTY_PAGE));

        controller.allDownloads(20, 1, null).block();

        verify(activeDownloadRepository).findAll(20, 1, List.of());
    }

    @Test
    void allDownloads_passesTheTypeThrough() {
        when(activeDownloadRepository.findAll(any(), any(), any())).thenReturn(Mono.just(EMPTY_PAGE));

        controller.allDownloads(20, 1, DownloadType.SONG).block();

        verify(activeDownloadRepository).findAll(20, 1, List.of(DownloadType.SONG));
    }

    @Test
    void allDownloads_playlistAlsoMeansCurated() {
        when(activeDownloadRepository.findAll(any(), any(), any())).thenReturn(Mono.just(EMPTY_PAGE));

        controller.allDownloads(20, 1, DownloadType.PLAYLIST).block();

        // To the user a suggested playlist is a playlist; the client's Playlists pill already folds
        // the two, so the server's filter has to agree or the pill shows a shorter list than the count.
        verify(activeDownloadRepository).findAll(20, 1, List.of(DownloadType.PLAYLIST, DownloadType.CURATED, DownloadType.RADIO));
    }

    @Test
    void allDownloads_withAnUnknownType_is400_beforeTheQueryRuns() {
        // Through the HTTP layer, because the rejection is Spring's enum conversion, not this class.
        WebTestClient http = WebTestClient.bindToController(controller).build();

        http.get().uri("/downloads/all?type=MIXTAPE").exchange().expectStatus().isBadRequest();

        verify(activeDownloadRepository, never()).findAll(any(), any(), any());
    }
}
