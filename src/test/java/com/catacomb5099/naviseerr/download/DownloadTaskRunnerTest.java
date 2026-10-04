package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.curator.CuratorClient;
import com.catacomb5099.naviseerr.services.RadioRepository;
import com.catacomb5099.naviseerr.curator.CuratorEdition;
import com.catacomb5099.naviseerr.curator.CuratorException;
import com.catacomb5099.naviseerr.curator.CuratorTrack;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.ServerState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DownloadTaskRunnerTest {

    private DownloadTaskRepository repository;
    private DownloadStepExecutor executor;
    private DownloadService downloadService;
    private SlskdService slskdService;
    private YtMusicService ytMusicService;
    private LibraryOrganiser organiser;
    private CuratorClient curatorClient;
    private RadioRepository radioRepository;
    private DownloadTaskRunner runner;

    @BeforeEach
    void setUp() {
        repository = mock(DownloadTaskRepository.class);
        executor = mock(DownloadStepExecutor.class);
        downloadService = mock(DownloadService.class);
        slskdService = mock(SlskdService.class);
        ytMusicService = mock(YtMusicService.class);
        organiser = mock(LibraryOrganiser.class);
        curatorClient = mock(CuratorClient.class);
        radioRepository = mock(RadioRepository.class);
        when(organiser.isEnabled()).thenReturn(false);
        when(organiser.deletePartials(any())).thenReturn(Mono.empty());
        when(repository.downloadsToFinalise(anyInt(), any())).thenReturn(Flux.empty());
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.empty());
        when(repository.createTasks(any(), any(), any())).thenReturn(Mono.just(1L));
        when(repository.upsertMedia(any())).thenReturn(Mono.just(1L));
        when(repository.concludeDownloads()).thenReturn(Mono.just(0L));
        when(repository.failUnadmitted(any(), any(), any())).thenReturn(Mono.just(1L));
        when(repository.countActiveDownloads()).thenReturn(Mono.just(0L));
        when(repository.countActiveTransfers()).thenReturn(Mono.just(0L));
        when(repository.countActiveSearches()).thenReturn(Mono.just(0L));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(Flux.empty());
        when(repository.save(any(), any())).thenReturn(Mono.just(1L));
        when(downloadService.finishTask(any(), any(), any(), any(), any())).thenReturn(Mono.just(1L));
        when(slskdService.getAllSearches()).thenReturn(Flux.empty());
        when(slskdService.getAllDownloads()).thenReturn(Flux.empty());
        when(slskdService.getServerState()).thenReturn(Mono.just(SlskdFixtures.serverState()));
        runner = new DownloadTaskRunner(repository, executor, downloadService, slskdService,
                ytMusicService, curatorClient, radioRepository, organiser, Clock.fixed(T0, ZoneOffset.UTC),
                Duration.ofSeconds(2), 10, Duration.ofSeconds(60), 20, 20, 2);
    }

    // ---- library organiser ---------------------------------------------------------------------

    @Test
    void withTheOrganiserOff_noFilingQueryIsMade() {
        runner.pass().block();

        verify(repository, never()).tasksToOrganise(anyInt(), any());
    }

    @Test
    void withTheOrganiserOn_eachFiledSongGetsItsLibraryPathWritten_andAMissingFileIsLeftForNextPass() {
        LibraryOrganiser.Job filed = job(UUID.randomUUID());
        LibraryOrganiser.Job notYet = job(UUID.randomUUID());
        when(organiser.isEnabled()).thenReturn(true);
        when(organiser.cutoff(T0)).thenReturn(T0.minusSeconds(600));
        when(repository.tasksToOrganise(10, T0.minusSeconds(600))).thenReturn(Flux.just(filed, notYet));
        when(organiser.file(filed, T0)).thenReturn(Mono.just(java.nio.file.Path.of("/music/A/B/c.flac")));
        when(organiser.file(notYet, T0)).thenReturn(Mono.empty());
        when(repository.setLibraryPath(any(), any())).thenReturn(Mono.just(1L));

        runner.pass().block();

        verify(repository).setLibraryPath(filed.taskId(), "/music/A/B/c.flac");
        verify(repository, never()).setLibraryPath(eq(notYet.taskId()), any());
    }

    @Test
    void aFilingErrorDoesNotStopThePass() {
        when(organiser.isEnabled()).thenReturn(true);
        when(organiser.cutoff(any())).thenReturn(T0.minusSeconds(600));
        when(repository.tasksToOrganise(anyInt(), any())).thenReturn(Flux.just(job(UUID.randomUUID())));
        when(organiser.file(any(), any())).thenReturn(Mono.error(new java.io.IOException("read-only")));

        assertDoesNotThrow(() -> runner.pass().block());
    }

    @Test
    void aSongThatFailedForGood_hasItsPartialFilesRemoved_butOnlyOnTheFirstFinish() {
        DownloadTask task = downloadPolling(candidates("alice"), 0, 0, "abc");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Terminal(DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED)));

        runner.pass().block();
        verify(organiser).deletePartials(task);

        // A duplicate finish (expired lease, re-stepped row) updates no row and cleans nothing again.
        when(downloadService.finishTask(any(), any(), any(), any(), any())).thenReturn(Mono.just(0L));
        runner.pass().block();
        verify(organiser, times(1)).deletePartials(task);
    }

    @Test
    void aSongThatSucceeded_keepsItsFile_forTheFilingStep() {
        DownloadTask task = downloadPolling(candidates("alice"), 0, 0, "abc");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Terminal(DownloadStatus.SUCCEEDED, null)));

        runner.pass().block();

        verify(organiser, never()).deletePartials(any());
    }

    @Test
    void aFinishedPlaylist_getsItsPlaylistFileWritten_thenIsStampedOrganised() {
        UUID playlistId = UUID.randomUUID();
        LibraryOrganiser.Collection playlist = new LibraryOrganiser.Collection(playlistId,
                DownloadType.PLAYLIST, "Alt Nation 1989");
        LibraryOrganiser.Entry entry = new LibraryOrganiser.Entry("/music/A/b/c.flac", "c", List.of("A"), 100);
        when(organiser.isEnabled()).thenReturn(true);
        when(organiser.cutoff(T0)).thenReturn(T0.minusSeconds(600));
        when(repository.tasksToOrganise(anyInt(), any())).thenReturn(Flux.empty());
        when(repository.downloadsToFinalise(10, T0.minusSeconds(600))).thenReturn(Flux.just(playlist));
        when(repository.playlistEntries(playlistId)).thenReturn(Flux.just(entry));
        when(organiser.writePlaylist("Alt Nation 1989", List.of(entry)))
                .thenReturn(Mono.just(java.nio.file.Path.of("/music/Playlists/Alt Nation 1989.m3u8")));
        when(repository.setOrganisedAt(playlistId, T0)).thenReturn(Mono.just(1L));

        runner.pass().block();

        verify(organiser).writePlaylist("Alt Nation 1989", List.of(entry));
        verify(repository).setOrganisedAt(playlistId, T0);
    }

    @Test
    void aFinishedAlbum_needsNoExtraFile_andIsStampedOrganised() {
        UUID albumId = UUID.randomUUID();
        when(organiser.isEnabled()).thenReturn(true);
        when(organiser.cutoff(T0)).thenReturn(T0.minusSeconds(600));
        when(repository.tasksToOrganise(anyInt(), any())).thenReturn(Flux.empty());
        when(repository.downloadsToFinalise(anyInt(), any())).thenReturn(Flux.just(
                new LibraryOrganiser.Collection(albumId, DownloadType.ALBUM, "Doolittle")));
        when(repository.setOrganisedAt(albumId, T0)).thenReturn(Mono.just(1L));

        runner.pass().block();

        verify(organiser, never()).writePlaylist(any(), any());
        verify(repository, never()).playlistEntries(any());
        verify(repository).setOrganisedAt(albumId, T0);
    }

    @Test
    void aPlaylistFileThatCannotBeWritten_isNotStampedOrganised_soTheNextPassRetries() {
        UUID playlistId = UUID.randomUUID();
        when(organiser.isEnabled()).thenReturn(true);
        when(organiser.cutoff(any())).thenReturn(T0.minusSeconds(600));
        when(repository.tasksToOrganise(anyInt(), any())).thenReturn(Flux.empty());
        when(repository.downloadsToFinalise(anyInt(), any())).thenReturn(Flux.just(
                new LibraryOrganiser.Collection(playlistId, DownloadType.PLAYLIST, "Mix")));
        when(repository.playlistEntries(playlistId)).thenReturn(Flux.empty());
        when(organiser.writePlaylist(any(), any())).thenReturn(Mono.error(new java.io.IOException("read-only")));

        assertDoesNotThrow(() -> runner.pass().block());

        verify(repository, never()).setOrganisedAt(any(), any());
    }

    private static LibraryOrganiser.Job job(UUID taskId) {
        return new LibraryOrganiser.Job(taskId, DownloadType.SONG, "music\\a\\c.flac", T0.minusSeconds(5),
                "c", List.of("A"), null, List.of());
    }

    @Test
    void atTheTransferCap_downloadInitTasksAreExcludedFromTheClaim() {
        when(repository.countActiveTransfers()).thenReturn(Mono.just(20L));

        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)),
                eq(false), eq(2));
    }

    @Test
    void belowTheTransferCap_downloadInitTasksAreClaimable() {
        when(repository.countActiveTransfers()).thenReturn(Mono.just(19L));

        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)),
                eq(true), eq(2));
    }

    @Test
    void atTheSearchCap_noSearchInitTaskIsClaimed_butEverythingElseStillIs() {
        // slskd runs two searches at a time and queues the rest inside itself, so a third search we
        // start only waits in that queue while our search budget runs down. Zero slots, not a
        // yes/no: the claim takes the NUMBER of free slots so one pass can never overshoot the cap.
        when(repository.countActiveSearches()).thenReturn(Mono.just(2L));

        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)),
                eq(true), eq(0));
    }

    @Test
    void belowTheSearchCap_exactlyTheFreeSlotsWorthOfSearchesMayStart() {
        when(repository.countActiveSearches()).thenReturn(Mono.just(1L));

        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)),
                eq(true), eq(1));
    }

    @Test
    void searchInitTasks_areSteppedOneAfterAnother_becauseSlskdRejectsOverlappingSearchPosts() {
        DownloadTask first = at(DownloadPhase.SEARCH_INIT).toBuilder().taskId(UUID.randomUUID()).build();
        DownloadTask second = at(DownloadPhase.SEARCH_INIT).toBuilder().taskId(UUID.randomUUID()).build();
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(Flux.just(first, second));
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        // The first POST takes a moment; with concurrent stepping the second would be fired
        // before it returns, which is exactly what slskd answers with 429.
        when(executor.execute(eq(first), any(), any())).thenReturn(
                Mono.delay(Duration.ofMillis(50))
                        .doOnNext(t -> order.add("first done"))
                        .thenReturn(new DownloadDecision.Continue(first.dueAt(T0.plusSeconds(2)))));
        when(executor.execute(eq(second), any(), any())).thenAnswer(inv -> {
            order.add("second started");
            return Mono.just(new DownloadDecision.Continue(second.dueAt(T0.plusSeconds(2))));
        });

        runner.pass().block();

        assertEquals(List.of("first done", "second started"), order);
    }

    @Test
    void pass_admitsUpToRemainingCapacity() {
        when(repository.countActiveDownloads()).thenReturn(Mono.just(18L));

        runner.pass().block();

        verify(repository).admitDownloads(2);
    }

    @Test
    void pass_admitsNothingWhenAtCapacity() {
        when(repository.countActiveDownloads()).thenReturn(Mono.just(20L));

        runner.pass().block();

        verify(repository, never()).admitDownloads(anyInt());
    }

    @Test
    void pass_admitsAtMostBatchSize() {
        when(repository.countActiveDownloads()).thenReturn(Mono.just(0L));

        runner.pass().block();

        verify(repository).admitDownloads(10);
    }

    @Test
    void pass_whenNothingIsClaimed_stillMakesAKeepAliveCall_toExerciseTheConnectionPool() {
        runner.pass().block();

        verify(slskdService).getServerState();
        verify(slskdService, never()).getAllSearches();
        verify(slskdService, never()).getAllDownloads();
    }

    @Test
    void pass_whenNothingIsClaimed_andTheKeepAliveCallFails_isSwallowedNotPropagated() {
        when(slskdService.getServerState()).thenReturn(Mono.error(new RuntimeException("slskd is down")));

        assertDoesNotThrow(() -> runner.pass().block());
    }

    @Test
    void aClaimedSearchPollTask_triggersGetAllSearches_butNotGetAllDownloads() {
        DownloadTask task = searchPolling("s1");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(2)))));

        runner.pass().block();

        verify(slskdService).getAllSearches();
        verify(slskdService, never()).getAllDownloads();
    }

    @Test
    void aClaimedDownloadPollTask_triggersGetAllDownloads_butNotGetAllSearches() {
        DownloadTask task = downloadPolling(candidates("alice"), 0, 0, "abc");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(5)))));

        runner.pass().block();

        verify(slskdService).getAllDownloads();
        verify(slskdService, never()).getAllSearches();
    }

    @Test
    void theFetchedBatchesArePassedToTheExecutorForTheMatchingRow() {
        DownloadTask task = searchPolling("s1");
        SearchState state = SlskdFixtures.searchState("s1", true, "Completed");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(slskdService.getAllSearches()).thenReturn(Flux.just(state));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(2)))));

        runner.pass().block();

        verify(executor).execute(eq(task), eq(java.util.Map.of("s1", state)), eq(java.util.Map.of()));
    }

    @Test
    void advanceDecision_savesTheNextTask_andDoesNotFinishTheDownload() {
        DownloadTask task = at(DownloadPhase.SEARCH_INIT);
        DownloadTask next = task.withPhase(DownloadPhase.SEARCH_POLL, T0);
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Advance(next)));

        runner.pass().block();

        verify(repository).save(eq(next), any());
        verify(downloadService, never()).finishTask(any(), any(), any(), any(), any());
    }

    @Test
    void continueDecision_savesTheNextTask() {
        DownloadTask task = searchPolling("s1");
        DownloadTask next = task.dueAt(T0.plusSeconds(2));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Continue(next)));

        runner.pass().block();

        verify(repository).save(eq(next), any());
    }

    @Test
    void terminalDecision_finishesTheDownload_andNeverSavesTheTask() {
        DownloadTask task = searchPolling("s1");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Terminal(DownloadStatus.FAILED, DownloadFailureCode.NO_CANDIDATES)));

        runner.pass().block();

        // The TASK's id, not the download's: one song of a collection finishing is not the
        // collection finishing. And the owner the claim stamped, so the finish only lands while
        // this step still holds the lease.
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(repository).claimDueTasks(anyInt(), owner.capture(), any(), any(), anyBoolean(), anyInt());
        verify(downloadService).finishTask(eq(TASK_ID), eq(DownloadStatus.FAILED), any(), any(), eq(owner.getValue()));
        verify(repository, never()).save(any(), any());
    }

    @Test
    void oneFailingStepDoesNotStopTheOthersInTheSamePass() {
        DownloadTask bad = searchPolling("bad");
        DownloadTask good = searchPolling("good");
        DownloadTask goodNext = good.dueAt(T0.plusSeconds(2));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(Flux.just(bad, good));
        when(executor.execute(eq(bad), any(), any())).thenReturn(Mono.error(new RuntimeException("boom")));
        when(executor.execute(eq(good), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Continue(goodNext)));

        runner.pass().block();

        verify(repository).save(eq(goodNext), any());
    }

    @Test
    void aFailedWriteDoesNotStopThePass() {
        DownloadTask task = searchPolling("s1");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(2)))));
        when(repository.save(any(), any())).thenReturn(Mono.error(new RuntimeException("db down")));

        runner.pass().block();   // must complete, not throw

        verify(repository).save(any(), any());
    }

    @Test
    void theSaveAfterDownloadInitIsRefused_cancelsTheOrphanedTransferInSlskd() {
        DownloadTask task = downloadInit(candidates("alice"), 0, 0);
        DownloadTask enqueued = task.withPhase(DownloadPhase.DOWNLOAD_POLL, T0).toBuilder()
                .slskdUsername("alice").slskdTransferId("t-9").build();
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(new DownloadDecision.Advance(enqueued)));
        when(repository.save(any(), any())).thenReturn(Mono.just(0L));   // the row was cancelled meanwhile
        when(slskdService.cancelDownload("alice", "t-9")).thenReturn(Mono.empty());

        runner.pass().block();

        verify(slskdService).cancelDownload("alice", "t-9");
    }

    @Test
    void theSaveAfterDownloadInitIsAccepted_leavesTheTransferRunning() {
        DownloadTask task = downloadInit(candidates("alice"), 0, 0);
        DownloadTask enqueued = task.withPhase(DownloadPhase.DOWNLOAD_POLL, T0).toBuilder()
                .slskdUsername("alice").slskdTransferId("t-9").build();
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(new DownloadDecision.Advance(enqueued)));

        runner.pass().block();

        verify(slskdService, never()).cancelDownload(any(), any());
    }

    // ---- metadata gathering --------------------------------------------------------------------

    @Test
    void aSongRequest_becomesOneTaskFromItsSongMetadata() {
        Download request = pendingRequest(DownloadType.SONG, "vid-1");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(ytMusicService.getSongInfo("vid-1"))
                .thenReturn(Mono.just(new YoutubeSongInfo("vid-1", List.of("Rick Astley"), List.of("UC-rick"),
                        "Never Gonna Give You Up", "https://img/rick.jpg", 213, null)));

        runner.pass().block();

        verify(ytMusicService).getSongInfo("vid-1");
        verify(repository).createTasks(eq(request.getDownloadId()),
                argThat(tasks -> tasks.size() == 1
                        && tasks.getFirst().youtubeId().equals("vid-1")
                        // The Soulseek wording: title, then primary artist, the shape the matcher
                        // still splits on. NOT the bare title -- that is what the display uses.
                        && tasks.getFirst().songName().equals("Never Gonna Give You Up - Rick Astley")),
                eq(T0));
        // The download's own media row and the song's are the same id, so one row is enough --
        // but it must carry what the card shows.
        verify(repository).upsertMedia(argThat(items -> items.stream()
                .anyMatch(m -> m.youtubeId().equals("vid-1")
                        && m.title().equals("Never Gonna Give You Up")
                        && m.imageUrl().equals("https://img/rick.jpg")
                        && m.artists().equals(List.of("Rick Astley"))
                        && m.artistIds().equals(List.of("UC-rick")))));
    }

    @Test
    void aCuratedRequest_becomesOneTaskPerSongFromTheCuratorsEdition() {
        Download request = pendingRequest(DownloadType.CURATED, "80s-indie-pop");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(curatorClient.getEdition("80s-indie-pop")).thenReturn(Mono.just(new CuratorEdition(
                "80s indie pop", Map.of("year", "1980-1989"), "2026-09-28", 1L, List.of(
                new CuratorTrack("kkxixKRfEnk", "Cico Buff", List.of("Cocteau Twins"), "Blue Bell Knoll", null,
                        1988, 6_100_000L, "top", "#23 of 1036 by plays"),
                new CuratorTrack(null, "Unplayable", List.of("Nobody"), "Nothing", null, null, 0L, "random", ""),
                new CuratorTrack("ewnLtRyqAzo", "Decomposing Trees", List.of("Galaxie 500"), "On Fire", null,
                        1988, 180_000L, "random", "random pick")))));

        runner.pass().block();

        verify(curatorClient).getEdition("80s-indie-pop");
        verifyNoInteractions(ytMusicService);
        // Two searchable rows: the song without a videoId is dropped, as it is for an album.
        verify(repository).createTasks(eq(request.getDownloadId()),
                argThat(tasks -> tasks.size() == 2
                        && tasks.getFirst().youtubeId().equals("kkxixKRfEnk")
                        && tasks.getFirst().songName().equals("Cico Buff - Cocteau Twins")
                        && tasks.get(1).youtubeId().equals("ewnLtRyqAzo")),
                eq(T0));
        // The playlist's own media row is keyed by the category the REQUEST carried, named after the
        // edition, credited to Naviseerr and pictured with its first song; every song gets YouTube's
        // predictable thumbnail because the curator stores none. Nor does it store channel ids, so
        // every name gets a blank one and renders as plain text.
        verify(repository).upsertMedia(argThat(items -> items.size() == 3
                && items.stream().anyMatch(m -> m.youtubeId().equals("80s-indie-pop")
                        && m.title().equals("80s indie pop")
                        && m.artists().equals(List.of("Naviseerr"))
                        && m.artistIds().equals(List.of(""))
                        && m.trackCount() == 2
                        && m.imageUrl().equals("https://i.ytimg.com/vi/kkxixKRfEnk/hqdefault.jpg"))
                && items.stream().anyMatch(m -> m.youtubeId().equals("ewnLtRyqAzo")
                        && m.title().equals("Decomposing Trees")
                        && m.artists().equals(List.of("Galaxie 500"))
                        && m.artistIds().equals(List.of(""))
                        && m.imageUrl().equals("https://i.ytimg.com/vi/ewnLtRyqAzo/hqdefault.jpg"))));
    }

    @Test
    void aCuratedRequestTheCuratorHasNoEditionFor_isFailedNotRetried() {
        Download request = pendingRequest(DownloadType.CURATED, "90s-grime");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(curatorClient.getEdition("90s-grime")).thenReturn(Mono.error(
                new CuratorException("curator returned 404: no edition for 90s-grime", false, 404)));

        runner.pass().block();

        verify(repository).failUnadmitted(request.getDownloadId(), DownloadFailureCode.METADATA_UNAVAILABLE, T0);
        verify(repository, never()).createTasks(any(), any(), any());
    }

    @Test
    void aCuratedRequestWhileTheCuratorIsDown_staysPendingForTheNextPass() {
        Download request = pendingRequest(DownloadType.CURATED, "80s-indie-pop");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(curatorClient.getEdition("80s-indie-pop")).thenReturn(Mono.error(
                new CuratorException("curator edition of 80s-indie-pop failed: Connection refused", true)));

        runner.pass().block();

        verify(repository, never()).failUnadmitted(any(), any(), any());
        verify(repository, never()).createTasks(any(), any(), any());
    }

    @Test
    void aFinishedCuratedPlaylist_getsItsPlaylistFileWritten_likeAYouTubePlaylist() {
        UUID downloadId = UUID.randomUUID();
        LibraryOrganiser.Collection curated = new LibraryOrganiser.Collection(downloadId,
                DownloadType.CURATED, "80s indie pop");
        LibraryOrganiser.Entry entry = new LibraryOrganiser.Entry("/music/A/b/c.flac", "c", List.of("A"), 100);
        when(organiser.isEnabled()).thenReturn(true);
        when(organiser.cutoff(T0)).thenReturn(T0.minusSeconds(600));
        when(repository.tasksToOrganise(anyInt(), any())).thenReturn(Flux.empty());
        when(repository.downloadsToFinalise(10, T0.minusSeconds(600))).thenReturn(Flux.just(curated));
        when(repository.playlistEntries(downloadId)).thenReturn(Flux.just(entry));
        when(organiser.writePlaylist("80s indie pop", List.of(entry)))
                .thenReturn(Mono.just(java.nio.file.Path.of("/music/Playlists/80s indie pop.m3u8")));
        when(repository.setOrganisedAt(downloadId, T0)).thenReturn(Mono.just(1L));

        runner.pass().block();

        verify(organiser).writePlaylist("80s indie pop", List.of(entry));
        verify(repository).setOrganisedAt(downloadId, T0);
    }

    @Test
    void aRadioRequest_downloadsTheSavedSongs_neverAFreshRadioFromYouTube() {
        UUID radioId = UUID.randomUUID();
        Download request = pendingRequest(DownloadType.RADIO, radioId.toString());
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(radioRepository.find(radioId)).thenReturn(Mono.just(new YoutubeCollectionInfo(radioId.toString(),
                List.of(new YoutubeSongInfo("q1", List.of("Earth, Wind & Fire"), List.of("UCewf"), "September",
                                "https://i.ytimg.com/vi/q1/hqdefault.jpg", 216, null),
                        new YoutubeSongInfo("q2", List.of("Queen"), List.of("UCq"), "Another One Bites The Dust",
                                "https://i.ytimg.com/vi/q2/hqdefault.jpg", 215, null)),
                null, "Billie Jean radio", List.of("Michael Jackson"), List.of("UCmj"), "https://img/seed.jpg")));

        runner.pass().block();

        verifyNoInteractions(ytMusicService);
        verify(repository).createTasks(eq(request.getDownloadId()),
                argThat(tasks -> tasks.size() == 2
                        && tasks.getFirst().songName().equals("September - Earth, Wind & Fire")
                        && tasks.get(1).youtubeId().equals("q2")),
                eq(T0));
        // The downloads list names it after the radio, keyed by the radio id the request carried.
        verify(repository).upsertMedia(argThat(items -> items.stream().anyMatch(m ->
                m.youtubeId().equals(radioId.toString()) && m.title().equals("Billie Jean radio")
                        && m.trackCount() == 2)));
    }

    @Test
    void aRadioRequestForARadioNobodySaved_isFailedNotRetried() {
        UUID radioId = UUID.randomUUID();
        Download unknown = pendingRequest(DownloadType.RADIO, radioId.toString());
        Download garbage = pendingRequest(DownloadType.RADIO, "not-a-uuid");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(unknown, garbage));
        when(radioRepository.find(radioId)).thenReturn(Mono.empty());

        runner.pass().block();

        verify(repository).failUnadmitted(unknown.getDownloadId(), DownloadFailureCode.METADATA_UNAVAILABLE, T0);
        verify(repository).failUnadmitted(garbage.getDownloadId(), DownloadFailureCode.METADATA_UNAVAILABLE, T0);
        verify(repository, never()).createTasks(any(), any(), any());
    }

    @Test
    void aRadioIsAPlaylistToTheLibrary() {
        assertTrue(DownloadType.RADIO.isPlaylist());
        assertTrue(DownloadType.RADIO.isCollection());
    }

    @Test
    void soulseekQuery_isTitleDashPrimaryArtist_orTitleAloneWhenNoArtistIsKnown() {
        assertEquals("Riptide - Vance Joy", DownloadTaskRunner.soulseekQuery(
                new YoutubeSongInfo("v", List.of("Vance Joy", "Someone Else"), "Riptide", null, null)));
        assertEquals("Riptide", DownloadTaskRunner.soulseekQuery(
                new YoutubeSongInfo("v", List.of(), "Riptide", null, null)));
    }

    @Test
    void anAlbumRequest_becomesOneTaskPerTrack() {
        Download request = pendingRequest(DownloadType.ALBUM, "MPREb_1");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(ytMusicService.getAlbumInfo("MPREb_1")).thenReturn(Mono.just(
                new YoutubeCollectionInfo("MPREb_1", List.of(
                        new YoutubeSongInfo("v1", List.of("A"), "one", "https://img/a.jpg", 100),
                        new YoutubeSongInfo("v2", List.of("A"), "two", "https://img/a.jpg", 100),
                        new YoutubeSongInfo("v3", List.of("A"), "three", "https://img/a.jpg", 100)),
                        "1999", "The Album", List.of("A"), List.of("UC-a"), "https://img/a.jpg")));

        runner.pass().block();

        // One download, three searchable rows -- the whole point of the 1:N task table.
        verify(repository).createTasks(eq(request.getDownloadId()),
                argThat(tasks -> tasks.size() == 3), eq(T0));
        // Four media rows: the album itself, keyed by the id the REQUEST carried, plus its tracks.
        verify(repository).upsertMedia(argThat(items -> items.size() == 4
                && items.getFirst().youtubeId().equals("MPREb_1")
                && items.getFirst().title().equals("The Album")
                && items.getFirst().artistIds().equals(List.of("UC-a"))
                && items.getFirst().trackCount() == 3));
    }

    @Test
    void aPlaylistRequest_usesThePlaylistEndpoint_notTheAlbumOne() {
        Download request = pendingRequest(DownloadType.PLAYLIST, "VLPL1");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(ytMusicService.getPlaylistInfo("VLPL1")).thenReturn(Mono.just(
                new YoutubeCollectionInfo("PL1",
                        List.of(new YoutubeSongInfo("v1", List.of(), "one", null, null)), null, "Mix",
                        List.of(), null)));

        runner.pass().block();

        verify(ytMusicService).getPlaylistInfo("VLPL1");
        verify(ytMusicService, never()).getAlbumInfo(any());
        // The adapter answered with the bare id; the media row still has to be keyed by the one the
        // request carried, or the feed's join finds nothing.
        verify(repository).upsertMedia(argThat(items -> items.getFirst().youtubeId().equals("VLPL1")));
    }

    @Test
    void anUnresolvableId_failsTheDownloadRatherThanRetryingItForever() {
        Download request = pendingRequest(DownloadType.SONG, "nope");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(ytMusicService.getSongInfo("nope"))
                .thenReturn(Mono.error(new YtMusicBadRequestException("no such video")));

        runner.pass().block();

        // A 400/404 cannot be made right by asking again, and a row left PENDING would be
        // re-requested every loop interval for the life of the install.
        verify(repository).failUnadmitted(request.getDownloadId(),
                DownloadFailureCode.METADATA_UNAVAILABLE, T0);
        verify(repository, never()).createTasks(any(), any(), any());
    }

    @Test
    void anUnavailableSidecar_leavesTheRequestPendingForTheNextPass() {
        Download request = pendingRequest(DownloadType.SONG, "vid-1");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(ytMusicService.getSongInfo("vid-1"))
                .thenReturn(Mono.error(new YtMusicUnavailableException("connection refused")));

        assertDoesNotThrow(() -> runner.pass().block());

        // The opposite of the case above: failing here would kill every download requested while
        // the sidecar happened to be restarting.
        verify(repository, never()).failUnadmitted(any(), any(), any());
        verify(repository, never()).createTasks(any(), any(), any());
    }

    @Test
    void aCollectionWithNoDownloadableTracks_fails() {
        Download request = pendingRequest(DownloadType.PLAYLIST, "VLempty");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(ytMusicService.getPlaylistInfo("VLempty")).thenReturn(Mono.just(
                new YoutubeCollectionInfo("VLempty", List.of(), null, "Empty", List.of(), null)));

        runner.pass().block();

        verify(repository).failUnadmitted(eq(request.getDownloadId()), any(), any());
    }

    // ---- conclusion ----------------------------------------------------------------------------

    @Test
    void everyPassConcludesFinishedDownloads() {
        // Unconditionally, and after stepping: a download whose last song finished in this very
        // pass reports its outcome on this tick, and one missed by a crash is caught by the next.
        runner.pass().block();

        verify(repository).concludeDownloads();
    }

    @Test
    void aFailingConcludeDoesNotStopThePass() {
        when(repository.concludeDownloads()).thenReturn(Mono.error(new RuntimeException("db down")));

        assertDoesNotThrow(() -> runner.pass().block());
    }

    private static Download pendingRequest(DownloadType type, String youtubeId) {
        return Download.builder()
                .downloadId(UUID.randomUUID())
                .youtubeId(youtubeId)
                .downloadType(type)
                .status(DownloadStatus.PENDING)
                .createdAt(T0)
                .build();
    }

    @Test
    void claimUsesTheConfiguredBatchSizeAndLease() {
        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)), eq(true),
                eq(2));
    }
}
