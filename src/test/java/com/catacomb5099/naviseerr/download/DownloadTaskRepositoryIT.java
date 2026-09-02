package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import com.catacomb5099.naviseerr.support.DownloadTaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadTaskRepositoryIT {

    @Autowired R2dbcEntityTemplate template;
    @Autowired DownloadTaskRepository repository;
    @Autowired DownloadService downloadService;

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @BeforeEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        // songs.download_id has no ON DELETE CASCADE, so it must go before downloads -- otherwise a
        // songs row left behind by another test class in this shared Testcontainers instance (e.g.
        // one exercising DownloadService.requestDownload) blocks this delete with a FK violation.
        template.getDatabaseClient().sql("DELETE FROM songs").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    private UUID insertDownload(String status) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, song_name, status, created_at) "
                        + "VALUES (:id, 'song', :status, now())")
                .bind("id", id).bind("status", status)
                .fetch().rowsUpdated().block();
        return id;
    }

    @Test
    void admit_createsTaskAndFlipsPendingToInProgress() {
        UUID id = insertDownload("PENDING");

        Long admitted = repository.admitNewDownloads(10, NOW).block();

        assertEquals(1L, admitted);
        assertEquals("SEARCH_INIT", phaseOf(id));
        assertEquals("IN_PROGRESS", statusOf(id));
    }

    @Test
    void admit_alsoRecoversInProgressDownloadsThatLostTheirTask() {
        UUID id = insertDownload("IN_PROGRESS");

        assertEquals(1L, repository.admitNewDownloads(10, NOW).block());
        assertEquals("SEARCH_INIT", phaseOf(id));
    }

    @Test
    void admit_ignoresDownloadsThatAlreadyHaveATask() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        assertEquals(0L, repository.admitNewDownloads(10, NOW).block());
        assertEquals(1L, countTaskRows());
    }

    @Test
    void admit_ignoresTerminalDownloads() {
        insertDownload("SUCCEEDED");
        insertDownload("FAILED");

        assertEquals(0L, repository.admitNewDownloads(10, NOW).block());
    }

    @Test
    void claim_returnsOnlyDueRowsAndStampsALease() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "instance-a", NOW, Duration.ofSeconds(60), true)
                .collectList().block();

        assertEquals(1, claimed.size());
        assertEquals(id, claimed.getFirst().downloadId());
        assertEquals("song", claimed.getFirst().songName());
        assertEquals("instance-a", leaseOwnerOf(id));
    }

    @Test
    void claim_skipsRowsThatAreNotYetDue() {
        insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        assertTrue(repository.claimDueTasks(10, "a", NOW.minusSeconds(1), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty());
    }

    @Test
    void claim_skipsRowsHeldByALiveLease() {
        insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true).collectList().block();

        assertTrue(repository.claimDueTasks(10, "b", NOW.plusSeconds(1), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty());
    }

    @Test
    void claim_reclaimsRowsWhoseLeaseHasExpired_thisIsCrashRecovery() {
        insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        repository.claimDueTasks(10, "dead", NOW, Duration.ofSeconds(60), true).collectList().block();

        List<DownloadTask> reclaimed = repository
                .claimDueTasks(10, "alive", NOW.plusSeconds(61), Duration.ofSeconds(60), true)
                .collectList().block();

        assertEquals(1, reclaimed.size());
    }

    @Test
    void save_roundTripsEveryFieldIncludingCandidates_andClearsTheLease() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true).blockFirst();

        DownloadTask updated = new DownloadTask(id, "song", DownloadPhase.DOWNLOAD_POLL,
                NOW, NOW.plusSeconds(5), "s1", DownloadTaskFixtures.candidates("alice", "bob"),
                1, 2, "bob", "music/bob/song.flac", "abc", "some error");
        repository.save(updated, "a").block();

        DownloadTask reread = repository
                .claimDueTasks(10, "b", NOW.plusSeconds(10), Duration.ofSeconds(60), true).blockFirst();

        assertNotNull(reread, "lease must have been cleared by save()");
        assertEquals(DownloadPhase.DOWNLOAD_POLL, reread.phase());
        assertEquals("s1", reread.searchId());
        assertEquals(2, reread.candidates().size());
        assertEquals("music/bob/song.flac", reread.candidates().get(1).filename());
        assertEquals(1411, reread.candidates().get(1).bitRate());
        assertEquals(1, reread.candidateIndex());
        assertEquals(2, reread.retryIndex());
        assertEquals("abc", reread.slskdTransferId());
    }

    @Test
    void finishDownload_writesStatusAndMarksTheTaskTerminalAtomically() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        assertEquals("SUCCEEDED", statusOf(id));
        assertEquals("SUCCEEDED", phaseOf(id));
        assertEquals(1L, countTaskRows(), "the task row is history now, not garbage");
    }

    @Test
    void finishDownload_recordsTheFailureReasonForLaterDebugging() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        downloadService.finishDownload(id, DownloadStatus.FAILED, DownloadFailureCode.TIMED_OUT, NOW)
                .block();

        assertEquals("FAILED", phaseOf(id));
        // The NAME, not prose: the client owns the wording, so copy edits never touch this column.
        assertEquals("TIMED_OUT", template.getDatabaseClient()
                .sql("SELECT failure_reason FROM download_tasks WHERE download_id = :id")
                .bind("id", id)
                .map((row, meta) -> row.get("failure_reason", String.class)).one().block());
    }

    @Test
    void finishDownload_onAnAlreadyTerminalDownload_changesNothingAtAll() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();
        Instant firstFinishedAt = finishedAtOf(id);

        // A duplicated step reaching Terminal a second time — legal, because a lease can expire
        // while the work is still alive.
        Long rows = downloadService
                .finishDownload(id, DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED,
                        NOW.plusSeconds(3600))
                .block();

        assertEquals(0L, rows, "a duplicate finish must be a no-op, not a second write");
        assertEquals("SUCCEEDED", statusOf(id), "must not overwrite a terminal status");
        assertEquals("SUCCEEDED", phaseOf(id), "must not overwrite a terminal phase either");
        assertNull(failureReasonOf(id), "a successful download must not acquire a failure reason");
        // The one that actually bites: re-stamping finished_at would slide this row back inside the
        // feed's retention window and resurrect a card the user dismissed hours ago.
        assertEquals(firstFinishedAt, finishedAtOf(id), "finished_at must not move");
    }

    @Test
    void finishDownload_closesATaskWhoseDownloadWentTerminalWithoutIt() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        // markStatusIfInProgress moves `downloads` without touching the task, leaving the task row
        // non-terminal and therefore still in the due-work partial index.
        downloadService.markStatusIfInProgress(id, DownloadStatus.SUCCEEDED).block();
        assertEquals("SEARCH_INIT", phaseOf(id));

        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        // Without the "or the task is still non-terminal" half of the guard this row would never go
        // terminal, so the runner would keep claiming it forever.
        assertEquals("SUCCEEDED", phaseOf(id));
        assertTrue(repository
                .claimDueTasks(10, "a", NOW.plusSeconds(86_400), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty());
    }

    @Test
    void save_advancesUpdatedAt() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();
        Instant admitted = updatedAtOf(id);

        repository.save(claimed.withPhase(DownloadPhase.SEARCH_POLL, NOW), "a").block();

        // The feed's recency sort key. phase_entered_at cannot serve as one, because it deliberately
        // does not move when only progress changes.
        assertTrue(updatedAtOf(id).isAfter(admitted),
                "updated_at must move on every write, since the feed orders on it");
    }

    @Test
    void claimDueTasks_neverReturnsTerminalTasks() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        assertTrue(repository
                .claimDueTasks(10, "a", NOW.plusSeconds(86_400), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty(),
                "a finished download must never be stepped again");
    }

    @Test
    void claimDueTasks_withNoTransferSlots_stillReturnsSearchTasks_butNotDownloadInit() {
        UUID searching = insertDownload("PENDING");
        UUID starting = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        // Move one task to DOWNLOAD_INIT; leave the other at SEARCH_INIT. Direct SQL, not
        // repository.save(): save() now requires a live lease, and this is fixture setup, not a
        // claimed step.
        moveToPhase(starting, DownloadPhase.DOWNLOAD_INIT);

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), false)
                .collectList().block();

        assertEquals(1, claimed.size(), "polling and searching must never be starved by the cap");
        assertEquals(searching, claimed.getFirst().downloadId());
    }

    @Test
    void countActiveTransfers_doesNotCountDownloadInitRows_thisIsTheDeadlockRegressionGuard() {
        // Reproduces the durable deadlock: if DOWNLOAD_INIT counted against the transfer cap, then
        // once max-concurrent-transfers worth of downloads landed in DOWNLOAD_INIT together, the cap
        // would be "full" of rows that CLAIM_DUE_SQL simultaneously refuses to claim (since
        // transferSlotsFree would be false) -- those rows could then never advance out of
        // DOWNLOAD_INIT, so the count could never drop, and the gate would stay closed forever, even
        // across a restart, because it is backed by the DB. This wires the REAL countActiveTransfers()
        // to prove DOWNLOAD_INIT rows never contribute to that count in the first place.
        int maxConcurrentTransfers = 20;
        for (int i = 0; i < maxConcurrentTransfers; i++) {
            UUID id = insertDownload("PENDING");
            repository.admitNewDownloads(10, NOW).block();
            moveToPhase(id, DownloadPhase.DOWNLOAD_INIT);
        }

        assertEquals(maxConcurrentTransfers, countTaskRowsInPhase("DOWNLOAD_INIT"),
                "sanity check: every row really is sitting in DOWNLOAD_INIT");
        assertEquals(0L, repository.countActiveTransfers().block(),
                "DOWNLOAD_INIT rows must never count against the transfer cap -- otherwise the cap "
                        + "closes on rows that can never be claimed while it is closed, and never reopens");
    }

    @Test
    void claimDueTasks_toleratesARowWithCorruptCandidatesJson_andStillReturnsTheOtherValidRow() {
        UUID corrupt = insertDownload("PENDING");
        UUID healthy = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET candidates = 'not valid json' WHERE download_id = :id")
                .bind("id", corrupt)
                .fetch().rowsUpdated().block();

        // Before the fix, readCandidates threw on the corrupt row, which failed the whole Flux
        // returned by claimDueTasks -- discarding the healthy row too, not just the corrupt one.
        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .collectList().block();

        assertEquals(2, claimed.size(),
                "a row with unreadable candidates JSON must not abort the claim for the other row");
        DownloadTask corruptTask = claimed.stream()
                .filter(t -> t.downloadId().equals(corrupt)).findFirst().orElseThrow();
        DownloadTask healthyTask = claimed.stream()
                .filter(t -> t.downloadId().equals(healthy)).findFirst().orElseThrow();
        assertEquals(List.of(), corruptTask.candidates(),
                "unreadable candidates fall back to empty rather than poisoning the row");
        assertNotNull(healthyTask);
    }

    private String statusOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT status FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("status", String.class)).one().block();
    }

    private String phaseOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).one().block();
    }

    private long countTaskRows() {
        return template.getDatabaseClient()
                .sql("SELECT count(*) AS total FROM download_tasks")
                .map((row, meta) -> row.get("total", Long.class)).one().block();
    }

    private long countTaskRowsInPhase(String phase) {
        return template.getDatabaseClient()
                .sql("SELECT count(*) AS total FROM download_tasks WHERE phase = :phase")
                .bind("phase", phase)
                .map((row, meta) -> row.get("total", Long.class)).one().block();
    }

    /** Fixture setup only -- bypasses the lease guard that repository.save() enforces. */
    private void moveToPhase(UUID id, DownloadPhase phase) {
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET phase = :phase WHERE download_id = :id")
                .bind("phase", phase.name()).bind("id", id)
                .fetch().rowsUpdated().block();
    }

    private Instant finishedAtOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT finished_at FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("finished_at", Instant.class)).one().block();
    }

    private Instant updatedAtOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT updated_at FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("updated_at", Instant.class)).one().block();
    }

    private String failureReasonOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT failure_reason FROM download_tasks WHERE download_id = :id")
                .bind("id", id)
                .map((row, meta) -> Optional.ofNullable(row.get("failure_reason", String.class)))
                .one().block().orElse(null);
    }

    private String leaseOwnerOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT lease_owner FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("lease_owner", String.class)).one().block();
    }
}
