package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.*;
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
    private DownloadTaskRunner runner;

    @BeforeEach
    void setUp() {
        repository = mock(DownloadTaskRepository.class);
        executor = mock(DownloadStepExecutor.class);
        downloadService = mock(DownloadService.class);
        slskdService = mock(SlskdService.class);
        when(repository.admitNewDownloads(anyInt(), any())).thenReturn(Mono.just(0L));
        when(repository.countActiveDownloads()).thenReturn(Mono.just(0L));
        when(repository.countActiveTransfers()).thenReturn(Mono.just(0L));
        when(repository.claimDueTasks(anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(Flux.empty());
        when(repository.save(any(), any())).thenReturn(Mono.just(1L));
        when(downloadService.finishDownload(any(), any(), any(), any())).thenReturn(Mono.just(1L));
        when(slskdService.getAllSearches()).thenReturn(Flux.empty());
        when(slskdService.getAllDownloads()).thenReturn(Flux.empty());
        runner = new DownloadTaskRunner(repository, executor, downloadService, slskdService,
                Clock.fixed(T0, ZoneOffset.UTC),
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

        verify(repository).admitNewDownloads(2, T0);
    }

    @Test
    void pass_admitsNothingWhenAtCapacity() {
        when(repository.countActiveDownloads()).thenReturn(Mono.just(20L));

        runner.pass().block();

        verify(repository, never()).admitNewDownloads(anyInt(), any());
    }

    @Test
    void pass_admitsAtMostBatchSize() {
        when(repository.countActiveDownloads()).thenReturn(Mono.just(0L));

        runner.pass().block();

        verify(repository).admitNewDownloads(10, T0);
    }

    @Test
    void pass_whenNothingIsClaimed_neverCallsEitherBatchedSlskdEndpoint() {
        runner.pass().block();

        verify(slskdService, never()).getAllSearches();
        verify(slskdService, never()).getAllDownloads();
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
        verify(downloadService, never()).finishDownload(any(), any(), any(), any());
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
                new DownloadDecision.Terminal(DownloadStatus.FAILED, "no candidates")));

        runner.pass().block();

        verify(downloadService).finishDownload(eq(ID), eq(DownloadStatus.FAILED), any(), any());
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

    @Test
    void claimUsesTheConfiguredBatchSizeAndLease() {
        runner.pass().block();

        verify(repository).claimDueTasks(eq(10), any(), eq(T0), eq(Duration.ofSeconds(60)), eq(true));
    }
}
