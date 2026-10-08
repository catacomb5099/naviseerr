package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.DownloadTaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.candidate;
import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.candidates;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DownloadServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private final UUID id = UUID.randomUUID();
    private final DownloadTaskRepository repository = mock(DownloadTaskRepository.class);
    private final SlskdService slskd = mock(SlskdService.class);
    private final LibraryOrganiser organiser = mock(LibraryOrganiser.class);
    private final R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
    private final DownloadService service = new DownloadService(template, repository, slskd, organiser);

    @BeforeEach
    void defaults() {
        when(repository.concludeDownloads()).thenReturn(Mono.just(0L));
        when(organiser.deletePartials(any())).thenReturn(Mono.empty());
        when(slskd.cancelDownload(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void cancel_ofAQueuedDownload_failsItUnadmitted_andNeverTouchesTasks() {
        when(repository.failUnadmitted(id, DownloadFailureCode.CANCELLED, NOW)).thenReturn(Mono.just(1L));

        assertEquals(1L, service.cancel(id, null, NOW).block());

        verify(repository, never()).cancelTasks(any(), any(), any());
        verify(repository).concludeDownloads();
    }

    @Test
    void cancel_ofARunningDownload_cancelsItsSongs_stopsTheirTransfers_andConcludes() {
        when(repository.failUnadmitted(any(), any(), any())).thenReturn(Mono.just(0L));
        DownloadTask polling = DownloadTaskFixtures.downloadPolling(DownloadTaskFixtures.candidates("alice"), 0, 0, "t-1");
        DownloadTask searching = DownloadTaskFixtures.searchPolling("s-1");
        when(repository.cancelTasks(id, null, NOW)).thenReturn(Flux.just(polling, searching));

        assertEquals(2L, service.cancel(id, null, NOW).block());

        verify(slskd).cancelDownload("alice", "t-1");
        verify(slskd, never()).cancelDownload(eq(searching.slskdUsername()), any());
        verify(organiser, times(2)).deletePartials(any());
        // The spec names this order as what makes the admission race come out right:
        // the queued-download check first, then the songs, then the download's status.
        InOrder statements = inOrder(repository);
        statements.verify(repository).failUnadmitted(id, DownloadFailureCode.CANCELLED, NOW);
        statements.verify(repository).cancelTasks(id, null, NOW);
        statements.verify(repository).concludeDownloads();
    }

    @Test
    void cancel_ofOneSong_skipsTheUnadmittedCheck() {
        UUID taskId = UUID.randomUUID();
        when(repository.cancelTasks(id, taskId, NOW)).thenReturn(Flux.empty());

        assertEquals(0L, service.cancel(id, taskId, NOW).block());

        verify(repository, never()).failUnadmitted(any(), any(), any());
        verify(repository).concludeDownloads();   // always, so the response body is never the "Waiting" quirk
    }

    @Test
    void cancel_whenSlskdRefuses_stillCancelsTheRow() {
        when(repository.failUnadmitted(any(), any(), any())).thenReturn(Mono.just(0L));
        DownloadTask polling = DownloadTaskFixtures.downloadPolling(DownloadTaskFixtures.candidates("alice"), 0, 0, "t-1");
        when(repository.cancelTasks(id, null, NOW)).thenReturn(Flux.just(polling));
        when(slskd.cancelDownload(any(), any())).thenReturn(Mono.error(new RuntimeException("slskd down")));

        assertEquals(1L, service.cancel(id, null, NOW).block());
    }

    @Test
    void retry_thatResetSongs_doesNotReadmit() {
        when(repository.retry(id, null, NOW)).thenReturn(Mono.just(1L));
        assertEquals(1L, service.retry(id, null, NOW).block());
        verify(repository, never()).readmit(any());
    }

    @Test
    void retry_withNothingToReset_fallsBackToReadmitting() {
        when(repository.retry(id, null, NOW)).thenReturn(Mono.just(0L));
        when(repository.readmit(id)).thenReturn(Mono.just(1L));
        assertEquals(1L, service.retry(id, null, NOW).block());
    }

    @Test
    void retry_withNothingToRetryOrReadmit_isZero() {
        when(repository.retry(id, null, NOW)).thenReturn(Mono.just(0L));
        when(repository.readmit(id)).thenReturn(Mono.just(0L));

        assertEquals(0L, service.retry(id, null, NOW).block());   // the endpoint turns this 0 into its 409
    }

    @Test
    void retry_ofOneSong_neverReadmits() {
        UUID taskId = UUID.randomUUID();
        when(repository.retry(id, taskId, NOW)).thenReturn(Mono.just(0L));

        assertEquals(0L, service.retry(id, taskId, NOW).block());
        verify(repository, never()).readmit(any());   // a bogus taskId must not re-queue an unadmitted failure
    }

    // ---- the lists a person picks from --------------------------------------------------------------

    private final UUID taskId = UUID.randomUUID();

    private DownloadTaskRepository.CachedSearch song(String phase, List<DownloadCandidate> candidates,
                                                     List<DownloadCandidate> files, Instant searchedAt) {
        return new DownloadTaskRepository.CachedSearch(taskId, phase, "Live Forever (Official Video) - Oasis", 0,
                candidates, 0, null, candidates.isEmpty() ? null : candidates.getFirst().filename(), files, searchedAt);
    }

    private static DownloadTaskRepository.CachedAlbumSearch album(String phase, String outcome, List<StoredFolder> folders) {
        return new DownloadTaskRepository.CachedAlbumSearch(DownloadType.ALBUM, phase, outcome, 0, "Definitely Maybe",
                List.of("Oasis"), folders, folders.isEmpty() ? null : NOW);
    }

    private static StoredFolder folder(String username, String path, Map<UUID, DownloadCandidate> files) {
        return new StoredFolder(username, path, true, 2, 1_500_000, 3, files, true);
    }

    @Test
    void candidates_withRememberedFiles_isReady_marksTheCurrentOne_andWordsTheQuery() {
        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(
                song("DOWNLOAD_POLL", candidates("bob"), candidates("alice", "bob"), NOW)));

        TaskCandidatesView view = service.candidates(id, taskId).block();

        assertEquals(PickListStatus.READY, view.status());
        assertNull(view.reason());
        assertEquals("Live Forever", view.query(), "the title alone, as the first search asked Soulseek");
        assertEquals(NOW, view.searchedAt());
        assertEquals(DownloadStage.DOWNLOADING, view.songStage());
        assertEquals("bob", view.current().username());
        assertEquals(List.of(false, true), view.candidates().stream().map(TaskCandidatesView.Candidate::isCurrent).toList());
        TaskCandidatesView.Candidate alice = view.candidates().getFirst();
        assertEquals("flac", alice.extension());
        assertEquals(240, alice.lengthSeconds());
        assertEquals(1411, alice.bitrateKbps());
        assertEquals("EXACT", alice.grade());
        verify(repository, never()).cachedAlbumSearch(any());
    }

    @Test
    void candidates_withNothingRemembered_whileSearching_isSearching() {
        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(song("SEARCH_POLL", List.of(), List.of(), null)));
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(new DownloadTaskRepository.CachedAlbumSearch(
                DownloadType.SONG, null, null, null, null, List.of(), List.of(), null)));

        TaskCandidatesView view = service.candidates(id, taskId).block();

        assertEquals(PickListStatus.SEARCHING, view.status());
        assertNull(view.reason());
        assertNull(view.current());
        assertTrue(view.candidates().isEmpty());
        assertEquals(DownloadStage.SEARCHING, view.songStage());
    }

    @Test
    void candidates_withNothingRemembered_afterSearching_saysWhy() {
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.empty());

        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(song("FAILED", List.of(), List.of(), null)));
        assertEquals("BEFORE_CACHE", service.candidates(id, taskId).block().reason());

        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(song("FAILED", List.of(), List.of(), NOW)));
        TaskCandidatesView none = service.candidates(id, taskId).block();
        assertEquals(PickListStatus.NONE, none.status());
        assertEquals("NO_RESULTS", none.reason());
        assertEquals(DownloadStage.FAILED, none.songStage());

        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(new DownloadTaskRepository.CachedSearch(taskId,
                "SUCCEEDED", "Live Forever - Oasis", 0, List.of(), 0, "/music/Oasis/Live Forever.flac", null, List.of(), null)));
        assertEquals("ALREADY_IN_LIBRARY", service.candidates(id, taskId).block().reason());
    }

    @Test
    void candidates_ofAnAlbumsSong_withNoOwnSearch_listsTheFoldersThatHoldIt() {
        UUID otherSong = UUID.randomUUID();
        DownloadCandidate bobsFile = candidate("bob");
        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(song("DOWNLOAD_INIT", List.of(bobsFile), List.of(), null)));
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(album("DONE", "WHOLE_FOLDER", List.of(
                folder("bob", "music/bob", Map.of(taskId, bobsFile, otherSong, candidate("bob"))),
                folder("carol", "music/carol", Map.of(otherSong, candidate("carol"))),
                folder("dave", "music/dave", Map.of(taskId, candidate("dave")))))));

        TaskCandidatesView view = service.candidates(id, taskId).block();

        assertEquals(PickListStatus.READY, view.status());
        assertEquals(List.of("bob", "dave"), view.candidates().stream().map(TaskCandidatesView.Candidate::username).toList(),
                "one entry per folder that holds this song, in the folders' order; carol's folder lacks it");
        assertTrue(view.candidates().getFirst().isCurrent());
        assertEquals(DownloadStage.READY_TO_DOWNLOAD, view.songStage());
    }

    @Test
    void candidates_ofAnUnknownSong_isEmpty() {
        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.empty());
        assertTrue(service.candidates(id, taskId).blockOptional().isEmpty());
    }

    @Test
    void albumCandidates_ofASongDownload_isNotAnAlbum() {
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(new DownloadTaskRepository.CachedAlbumSearch(
                DownloadType.PLAYLIST, null, null, null, "Britpop", List.of(), List.of(), null)));

        assertThrows(DownloadService.NotAnAlbumException.class, () -> service.albumCandidates(id).block());
        verify(repository, never()).songPicks(any());
    }

    @Test
    void albumCandidates_listsEachFoldersFilesInTrackOrder_andWhichFolderTheSongsComeFrom() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DownloadCandidate bob1 = new DownloadCandidate("bob", "music\\bob\\Definitely Maybe\\01 - Rock n Roll Star.flac", "",
                null, 38_000_000L, 1L, false, true, 2, 1_500_000, "EXACT", DownloadCandidate.ALBUM_FOLDER, 322);
        DownloadCandidate bob2 = new DownloadCandidate("bob", "music\\bob\\Definitely Maybe\\02 - Shakermaker.flac", "",
                null, 30_000_000L, 1L, false, true, 2, 1_500_000, "EXACT", DownloadCandidate.ALBUM_FOLDER, 308);
        DownloadCandidate carol2 = new DownloadCandidate("carol", "music\\carol\\DM\\02 - Shakermaker.mp3", "",
                320, 12_000_000L, 1L, false, false, 9, 400_000, "EXACT", DownloadCandidate.ALBUM_FOLDER, 309);
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(album("DONE", "WHOLE_FOLDER", List.of(
                folder("bob", "music\\bob\\Definitely Maybe", new java.util.LinkedHashMap<>(Map.of(second, bob2, first, bob1))),
                folder("carol", "music\\carol\\DM", Map.of(second, carol2))))));
        when(repository.songPicks(id)).thenReturn(Flux.just(
                new DownloadTaskRepository.SongPick(first, 1, "Rock 'n' Roll Star", "DOWNLOAD_POLL", bob1),
                new DownloadTaskRepository.SongPick(second, 2, "Shakermaker", "DOWNLOAD_INIT", bob2)));

        AlbumCandidatesView view = service.albumCandidates(id).block();

        assertEquals(PickListStatus.READY, view.status());
        assertEquals("Definitely Maybe", view.query());
        assertEquals(2, view.songCount());
        AlbumCandidatesView.Folder bob = view.folders().getFirst();
        assertEquals("music\\bob\\Definitely Maybe", bob.folder());
        assertEquals(2, bob.fileCount());
        assertEquals(68_000_000L, bob.totalSize());
        assertEquals(3, bob.extras());
        assertEquals(2, bob.songsCurrent());
        assertTrue(bob.isCurrent());
        assertEquals(List.of(1, 2), bob.files().stream().map(AlbumCandidatesView.File::index).toList(), "track order, not map order");
        AlbumCandidatesView.File star = bob.files().getFirst();
        assertEquals("01 - Rock n Roll Star.flac", star.name());
        assertEquals("Rock 'n' Roll Star", star.title());
        assertEquals("flac", star.extension());
        assertEquals(322, star.lengthSeconds());
        AlbumCandidatesView.Folder carol = view.folders().get(1);
        assertEquals(0, carol.songsCurrent());
        assertFalse(carol.isCurrent());
        assertEquals(320, carol.files().getFirst().bitrateKbps());
    }

    // ---- picking ------------------------------------------------------------------------------------

    @Test
    void pick_ofAFileNotInTheSongsList_changesNothing_andTellsSlskdNothing() {
        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(song("DOWNLOAD_POLL", candidates("alice"), candidates("alice"), NOW)));

        assertEquals(0L, service.pick(id, taskId, "zed", "music/zed/song.flac", NOW).block(), "a stale dialog: the endpoint answers 409");

        verify(repository, never()).pick(any(), any(), any());
        verifyNoInteractions(slskd, organiser);
    }

    @Test
    void pick_rePointsTheSongAtTheChosenFileAsItsOnlyCandidate_cancelsTheOldTransfer_andRemovesItsPartials() {
        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(song("DOWNLOAD_POLL", candidates("alice"), candidates("alice", "bob"), NOW)));
        DownloadTask old = DownloadTaskFixtures.downloadPolling(candidates("alice"), 0, 0, "t-1");
        when(repository.pick(eq(id), any(), eq(NOW))).thenReturn(Flux.just(old));

        assertEquals(1L, service.pick(id, taskId, "bob", "music/bob/song.flac", NOW).block());

        DownloadCandidate manual = candidate("bob").asManual();
        assertEquals(DownloadCandidate.MANUAL, manual.source());
        assertEquals("EXACT", manual.grade());
        verify(repository).pick(id, Map.of(taskId, manual), NOW);
        verify(slskd).cancelDownload("alice", "t-1");
        verify(organiser).deletePartials(old);
    }

    @Test
    void pick_ofAnAlbumsSong_choosesAmongTheFoldersThatHoldIt() {
        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.just(song("DOWNLOAD_INIT", candidates("bob"), List.of(), null)));
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(album("DONE", "WHOLE_FOLDER", List.of(
                folder("bob", "music/bob", Map.of(taskId, candidate("bob"))),
                folder("dave", "music/dave", Map.of(taskId, candidate("dave")))))));
        when(repository.pick(eq(id), any(), eq(NOW))).thenReturn(Flux.just(DownloadTaskFixtures.downloadInit(candidates("bob"), 0, 0)));

        assertEquals(1L, service.pick(id, taskId, "dave", "music/dave/song.flac", NOW).block());

        verify(repository).pick(id, Map.of(taskId, candidate("dave").asManual()), NOW);
        verify(slskd, never()).cancelDownload(any(), any());   // nothing was enqueued yet
        verify(organiser).deletePartials(any());
    }

    @Test
    void pick_ofAnUnknownSong_isEmpty_soTheEndpointAnswers404() {
        when(repository.cachedSearch(id, taskId)).thenReturn(Mono.empty());
        assertTrue(service.pick(id, taskId, "alice", "x", NOW).blockOptional().isEmpty());
    }

    @Test
    void albumPick_rePointsEverySongTheFolderHolds_andStopsEachOldTransfer() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(album("DONE", "WHOLE_FOLDER", List.of(
                folder("bob", "music/bob", Map.of(first, candidate("bob"), second, candidate("bob"))),
                folder("carol", "music/carol", Map.of(first, candidate("carol"), second, candidate("carol")))))));
        DownloadTask oldFirst = DownloadTaskFixtures.downloadPolling(candidates("bob"), 0, 0, "t-1").toBuilder().taskId(first).build();
        DownloadTask oldSecond = DownloadTaskFixtures.downloadPolling(candidates("bob"), 0, 0, "t-2").toBuilder().taskId(second).build();
        when(repository.pick(eq(id), any(), eq(NOW))).thenReturn(Flux.just(oldFirst, oldSecond));

        assertEquals(2L, service.albumPick(id, "carol", "music/carol", NOW).block());

        verify(repository).pick(id, Map.of(first, candidate("carol").asManual(), second, candidate("carol").asManual()), NOW);
        verify(slskd).cancelDownload("bob", "t-1");
        verify(slskd).cancelDownload("bob", "t-2");
        verify(organiser, times(2)).deletePartials(any());
    }

    @Test
    void albumPick_ofAFolderNotRemembered_changesNothing() {
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(album("DONE", "WHOLE_FOLDER", List.of(
                folder("bob", "music/bob", Map.of(taskId, candidate("bob")))))));

        assertEquals(0L, service.albumPick(id, "bob", "music/elsewhere", NOW).block());

        verify(repository, never()).pick(any(), any(), any());
    }

    @Test
    void albumPick_ofAPlaylist_isNotAnAlbum_andAnUnknownDownloadIsEmpty() {
        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(new DownloadTaskRepository.CachedAlbumSearch(
                DownloadType.PLAYLIST, null, null, null, "Britpop", List.of(), List.of(), null)));
        assertThrows(DownloadService.NotAnAlbumException.class, () -> service.albumPick(id, "bob", "x", NOW).block());

        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.empty());
        assertTrue(service.albumPick(id, "bob", "x", NOW).blockOptional().isEmpty());
    }

    @Test
    void albumCandidates_withNoFolders_saysWhy() {
        when(repository.songPicks(id)).thenReturn(Flux.empty());

        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(new DownloadTaskRepository.CachedAlbumSearch(
                DownloadType.ALBUM, null, null, null, "Old", List.of("Band"), List.of(), null)));
        assertEquals("NO_ALBUM_SEARCH", service.albumCandidates(id).block().reason());

        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(album("SEARCH_POLL", null, List.of())));
        assertEquals(PickListStatus.SEARCHING, service.albumCandidates(id).block().status());

        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(album("DONE", "NO_WHOLE_FOLDER", List.of())));
        assertEquals("NO_WHOLE_FOLDER", service.albumCandidates(id).block().reason());

        when(repository.cachedAlbumSearch(id)).thenReturn(Mono.just(album("DONE", "WHOLE_FOLDER", List.of())));
        AlbumCandidatesView before = service.albumCandidates(id).block();
        assertEquals(PickListStatus.NONE, before.status());
        assertEquals("BEFORE_CACHE", before.reason());
    }
}
