package com.catacomb5099.naviseerr.download;

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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private DownloadTaskRunner runner;

    @BeforeEach
    void setUp() {
        repository = mock(DownloadTaskRepository.class);
        executor = mock(DownloadStepExecutor.class);
        downloadService = mock(DownloadService.class);
        slskdService = mock(SlskdService.class);
        ytMusicService = mock(YtMusicService.class);
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.empty());
        when(repository.createTasks(any(), any(), any())).thenReturn(Mono.just(1L));
        when(repository.upsertMedia(any())).thenReturn(Mono.just(1L));
        when(repository.concludeDownloads()).thenReturn(Mono.just(0L));
        when(repository.failUnadmitted(any(), any(), any())).thenReturn(Mono.just(1L));
        when(repository.countActiveDownloads()).thenReturn(Mono.just(0L));
        when(repository.countActiveTransfers()).thenReturn(Mono.just(0L));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(Flux.empty());
        when(repository.save(any(), any())).thenReturn(Mono.just(1L));
        when(downloadService.finishTask(any(), any(), any(), any())).thenReturn(Mono.just(1L));
        when(slskdService.getAllSearches()).thenReturn(Flux.empty());
        when(slskdService.getAllDownloads()).thenReturn(Flux.empty());
        when(slskdService.getServerState()).thenReturn(Mono.just(SlskdFixtures.serverState()));
        runner = new DownloadTaskRunner(repository, executor, downloadService, slskdService,
                ytMusicService, Clock.fixed(T0, ZoneOffset.UTC),
                Duration.ofSeconds(2), 10, Duration.ofSeconds(60), 20, 20);
    }

    @Test
    void atTheTransferCap_downloadInitTasksAreExcludedFromTheClaim() {
        when(repository.countActiveTransfers()).thenReturn(Mono.just(20L));

        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)),
                eq(false));
    }

    @Test
    void belowTheTransferCap_downloadInitTasksAreClaimable() {
        when(repository.countActiveTransfers()).thenReturn(Mono.just(19L));

        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)),
                eq(true));
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
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(2)))));

        runner.pass().block();

        verify(slskdService).getAllSearches();
        verify(slskdService, never()).getAllDownloads();
    }

    @Test
    void aClaimedDownloadPollTask_triggersGetAllDownloads_butNotGetAllSearches() {
        DownloadTask task = downloadPolling(candidates("alice"), 0, 0, "abc");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
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
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
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
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Advance(next)));

        runner.pass().block();

        verify(repository).save(eq(next), any());
        verify(downloadService, never()).finishTask(any(), any(), any(), any());
    }

    @Test
    void continueDecision_savesTheNextTask() {
        DownloadTask task = searchPolling("s1");
        DownloadTask next = task.dueAt(T0.plusSeconds(2));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any()))
                .thenReturn(Mono.just(new DownloadDecision.Continue(next)));

        runner.pass().block();

        verify(repository).save(eq(next), any());
    }

    @Test
    void terminalDecision_finishesTheDownload_andNeverSavesTheTask() {
        DownloadTask task = searchPolling("s1");
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Terminal(DownloadStatus.FAILED, DownloadFailureCode.NO_CANDIDATES)));

        runner.pass().block();

        // The TASK's id, not the download's: one song of a collection finishing is not the
        // collection finishing.
        verify(downloadService).finishTask(eq(TASK_ID), eq(DownloadStatus.FAILED), any(), any());
        verify(repository, never()).save(any(), any());
    }

    @Test
    void oneFailingStepDoesNotStopTheOthersInTheSamePass() {
        DownloadTask bad = searchPolling("bad");
        DownloadTask good = searchPolling("good");
        DownloadTask goodNext = good.dueAt(T0.plusSeconds(2));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean()))
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
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean())).thenReturn(Flux.just(task));
        when(executor.execute(eq(task), any(), any())).thenReturn(Mono.just(
                new DownloadDecision.Continue(task.dueAt(T0.plusSeconds(2)))));
        when(repository.save(any(), any())).thenReturn(Mono.error(new RuntimeException("db down")));

        runner.pass().block();   // must complete, not throw

        verify(repository).save(any(), any());
    }

    // ---- metadata gathering --------------------------------------------------------------------

    @Test
    void aSongRequest_becomesOneTaskFromItsSongMetadata() {
        Download request = pendingRequest(DownloadType.SONG, "vid-1");
        when(repository.admitDownloads(anyInt())).thenReturn(Flux.just(request));
        when(ytMusicService.getSongInfo("vid-1"))
                .thenReturn(Mono.just(new YoutubeSongInfo("vid-1", List.of("Rick Astley"),
                        "Never Gonna Give You Up", "https://img/rick.jpg", 213)));

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
                        && m.artists().equals(List.of("Rick Astley")))));
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
                        "1999", "The Album", List.of("A"), "https://img/a.jpg")));

        runner.pass().block();

        // One download, three searchable rows -- the whole point of the 1:N task table.
        verify(repository).createTasks(eq(request.getDownloadId()),
                argThat(tasks -> tasks.size() == 3), eq(T0));
        // Four media rows: the album itself, keyed by the id the REQUEST carried, plus its tracks.
        verify(repository).upsertMedia(argThat(items -> items.size() == 4
                && items.getFirst().youtubeId().equals("MPREb_1")
                && items.getFirst().title().equals("The Album")
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

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)), eq(true));
    }
}
