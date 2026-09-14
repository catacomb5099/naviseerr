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
import java.util.Arrays;
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
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    private UUID insertDownload(String status) {
        return insertDownload(status, "SONG");
    }

    private UUID insertDownload(String status, String type) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                        + "VALUES (:id, :ytId, :type, :status, now())")
                .bind("id", id).bind("ytId", "yt-" + id).bind("type", type).bind("status", status)
                .fetch().rowsUpdated().block();
        return id;
    }

    /**
     * The other half of admission. The runner's metadata call sits between {@code admitDownloads}
     * and this, so every test that needs an admitted download does what the runner does.
     */
    private Long admit(UUID downloadId, String... songNames) {
        List<DownloadTask> tasks = Arrays.stream(songNames)
                .map(name -> DownloadTask.initial(downloadId, "yt-" + name, name, NOW))
                .toList();
        return repository.createTasks(downloadId, songNames[0], tasks, NOW).block();
    }

    private UUID admitOneSong(String status) {
        UUID id = insertDownload(status);
        admit(id, "song");
        return id;
    }

    @Test
    void admitDownloads_returnsPendingRequestsWithTheirTypeAndId() {
        UUID id = insertDownload("PENDING", "ALBUM");

        List<Download> admissible = repository.admitDownloads(10).collectList().block();

        assertEquals(1, admissible.size());
        assertEquals(id, admissible.getFirst().getDownloadId());
        // Both are what the runner needs to pick an endpoint and call it. A download whose type did
        // not survive the read would be sent to the wrong ytmusic-adapter route.
        assertEquals(DownloadType.ALBUM, admissible.getFirst().getDownloadType());
        assertEquals("yt-" + id, admissible.getFirst().getYoutubeId());
    }

    @Test
    void admitDownloads_writesNothing() {
        UUID id = insertDownload("PENDING");

        repository.admitDownloads(10).collectList().block();

        // The metadata call happens after this and can fail, so leaving the row untouched is what
        // makes the next pass able to retry it.
        assertEquals("PENDING", statusOf(id));
        assertEquals(0L, countTaskRows());
    }

    @Test
    void admitDownloads_ignoresDownloadsThatAlreadyHaveTasks() {
        admitOneSong("PENDING");

        assertTrue(repository.admitDownloads(10).collectList().block().isEmpty());
    }

    @Test
    void admitDownloads_ignoresInProgressAndTerminalDownloads() {
        // IN_PROGRESS is deliberately NOT covered any more. The old statement had to cover it to be
        // crash-safe across its own two halves; now the task inserts and the status flip are one
        // statement, so an IN_PROGRESS download always has task rows and nothing needs recovering.
        insertDownload("IN_PROGRESS");
        insertDownload("SUCCEEDED");
        insertDownload("FAILED");
        insertDownload("PARTIAL_SUCCESS");

        assertTrue(repository.admitDownloads(10).collectList().block().isEmpty());
    }

    @Test
    void createTasks_createsOneRowPerSongAndAdmitsTheDownloadOnce() {
        UUID id = insertDownload("PENDING", "ALBUM");

        Long admitted = admit(id, "track one", "track two", "track three");

        assertEquals(1L, admitted, "one download was admitted, whatever its song count");
        assertEquals(3L, countTaskRows());
        assertEquals("IN_PROGRESS", statusOf(id));
        // The collection's own title, not any one song's -- this is what the client renders.
        assertEquals("track one", songNameOf(id));
        assertEquals(List.of("SEARCH_INIT", "SEARCH_INIT", "SEARCH_INIT"), phasesOf(id));
    }

    @Test
    void createTasks_givesEverySongItsOwnTaskIdAndYoutubeId() {
        UUID id = insertDownload("PENDING", "PLAYLIST");
        admit(id, "a", "b");

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "x", NOW, Duration.ofSeconds(60), true).collectList().block();

        assertEquals(2, claimed.size());
        assertEquals(2, claimed.stream().map(DownloadTask::taskId).distinct().count(),
                "two songs of one download must be two distinct task rows");
        assertEquals(List.of("yt-a", "yt-b"),
                claimed.stream().map(DownloadTask::youtubeId).sorted().toList());
        assertEquals(1, claimed.stream().map(DownloadTask::downloadId).distinct().count(),
                "and they must both still belong to the one download");
    }

    @Test
    void createTasks_runTwice_isANoOpRatherThanDuplicatingEverySong() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "a", "b");

        // Two overlapping passes, or a retried metadata call, must not double a collection.
        assertEquals(0L, admit(id, "a", "b"));
        assertEquals(2L, countTaskRows());
    }

    @Test
    void claim_returnsOnlyDueRowsAndStampsALease() {
        UUID id = admitOneSong("PENDING");

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
        admitOneSong("PENDING");

        assertTrue(repository.claimDueTasks(10, "a", NOW.minusSeconds(1), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty());
    }

    @Test
    void claim_skipsRowsHeldByALiveLease() {
        admitOneSong("PENDING");
        repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true).collectList().block();

        assertTrue(repository.claimDueTasks(10, "b", NOW.plusSeconds(1), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty());
    }

    @Test
    void claim_reclaimsRowsWhoseLeaseHasExpired_thisIsCrashRecovery() {
        admitOneSong("PENDING");
        repository.claimDueTasks(10, "dead", NOW, Duration.ofSeconds(60), true).collectList().block();

        List<DownloadTask> reclaimed = repository
                .claimDueTasks(10, "alive", NOW.plusSeconds(61), Duration.ofSeconds(60), true)
                .collectList().block();

        assertEquals(1, reclaimed.size());
    }

    @Test
    void save_roundTripsEveryFieldIncludingCandidates_andClearsTheLease() {
        UUID id = admitOneSong("PENDING");
        DownloadTask claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true).blockFirst();

        DownloadTask updated = claimed.toBuilder()
                .phase(DownloadPhase.DOWNLOAD_POLL)
                .nextAttemptAt(NOW.plusSeconds(5))
                .searchId("s1")
                .candidates(DownloadTaskFixtures.candidates("alice", "bob"))
                .candidateIndex(1).retryIndex(2)
                .slskdUsername("bob").slskdFilename("music/bob/song.flac").slskdTransferId("abc")
                .lastError("some error")
                .build();
        repository.save(updated, "a").block();

        DownloadTask reread = repository
                .claimDueTasks(10, "b", NOW.plusSeconds(10), Duration.ofSeconds(60), true).blockFirst();

        assertNotNull(reread, "lease must have been cleared by save()");
        assertEquals(claimed.taskId(), reread.taskId(), "save() keys on task_id, not download_id");
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
    void save_writesOnlyTheOneSongItWasGiven() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "a", "b");
        DownloadTask one = repository
                .claimDueTasks(10, "x", NOW, Duration.ofSeconds(60), true).blockFirst();

        repository.save(one.withPhase(DownloadPhase.SEARCH_POLL, NOW), "x").block();

        // Keying on download_id -- which is what SAVE_SQL did before this change -- would step both
        // of an album's songs on one song's slskd response.
        assertEquals(List.of("SEARCH_INIT", "SEARCH_POLL"), phasesOf(id).stream().sorted().toList());
    }

    @Test
    void finishTask_marksTheTaskTerminalButLeavesTheDownloadAlone() {
        UUID id = admitOneSong("PENDING");

        downloadService.finishTask(taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW).block();

        assertEquals("SUCCEEDED", phaseOf(id));
        // The download's status is settled by concludeDownloads(), not here -- see CONCLUDE_SQL.
        assertEquals("IN_PROGRESS", statusOf(id));
        assertEquals(1L, countTaskRows(), "the task row is history now, not garbage");
    }

    @Test
    void finishTask_recordsTheFailureReasonForLaterDebugging() {
        UUID id = admitOneSong("PENDING");

        downloadService.finishTask(taskIdOf(id), DownloadStatus.FAILED,
                DownloadFailureCode.TIMED_OUT, NOW).block();

        assertEquals("FAILED", phaseOf(id));
        // The NAME, not prose: the client owns the wording, so copy edits never touch this column.
        assertEquals("TIMED_OUT", failureReasonOf(id));
    }

    @Test
    void finishTask_onAnAlreadyTerminalTask_changesNothingAtAll() {
        UUID id = admitOneSong("PENDING");
        UUID taskId = taskIdOf(id);
        downloadService.finishTask(taskId, DownloadStatus.SUCCEEDED, null, NOW).block();
        Instant firstFinishedAt = finishedAtOf(id);

        // A duplicated step reaching Terminal a second time — legal, because a lease can expire
        // while the work is still alive.
        Long rows = downloadService
                .finishTask(taskId, DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED,
                        NOW.plusSeconds(3600))
                .block();

        assertEquals(0L, rows, "a duplicate finish must be a no-op, not a second write");
        assertEquals("SUCCEEDED", phaseOf(id), "must not overwrite a terminal phase");
        assertNull(failureReasonOf(id), "a successful song must not acquire a failure reason");
        // The one that actually bites: re-stamping finished_at would slide this row back inside the
        // feed's retention window and resurrect a card the user dismissed hours ago.
        assertEquals(firstFinishedAt, finishedAtOf(id), "finished_at must not move");
    }

    @Test
    void conclude_succeedsADownloadWhoseOnlySongSucceeded() {
        UUID id = admitOneSong("PENDING");
        downloadService.finishTask(taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW).block();

        assertEquals(1L, repository.concludeDownloads().block());
        assertEquals("SUCCEEDED", statusOf(id));
    }

    @Test
    void conclude_failsADownloadWhoseOnlySongFailed() {
        UUID id = admitOneSong("PENDING");
        downloadService.finishTask(taskIdOf(id), DownloadStatus.FAILED,
                DownloadFailureCode.NO_CANDIDATES, NOW).block();

        assertEquals(1L, repository.concludeDownloads().block());
        assertEquals("FAILED", statusOf(id));
    }

    @Test
    void conclude_reportsPartialSuccessWhenACollectionGotSomeOfItsSongs() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "found", "missing");
        List<UUID> tasks = taskIdsOf(id);
        downloadService.finishTask(tasks.get(0), DownloadStatus.SUCCEEDED, null, NOW).block();
        downloadService.finishTask(tasks.get(1), DownloadStatus.FAILED,
                DownloadFailureCode.NO_CANDIDATES, NOW).block();

        assertEquals(1L, repository.concludeDownloads().block());
        assertEquals("PARTIAL_SUCCESS", statusOf(id));
    }

    @Test
    void conclude_leavesADownloadAloneWhileAnyOfItsSongsIsStillRunning() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "done", "still going");
        downloadService.finishTask(taskIdsOf(id).getFirst(), DownloadStatus.SUCCEEDED, null, NOW)
                .block();

        assertEquals(0L, repository.concludeDownloads().block());
        assertEquals("IN_PROGRESS", statusOf(id),
                "one finished song of an album does not finish the album");
    }

    @Test
    void conclude_isIdempotent_soRunningItEveryPassCostsNothing() {
        UUID id = admitOneSong("PENDING");
        downloadService.finishTask(taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW).block();
        repository.concludeDownloads().block();

        // It runs unconditionally on every pass, so "already concluded" must match no rows at all
        // rather than rewriting the same status forever.
        assertEquals(0L, repository.concludeDownloads().block());
        assertEquals("SUCCEEDED", statusOf(id));
    }

    @Test
    void conclude_concludesEachOfTwoConcurrentlyFinishedDownloads() {
        // The race this statement exists to remove: the last two songs of a download finishing
        // together used to be able to leave it IN_PROGRESS forever, because each read a snapshot in
        // which the other was still running. Nothing decides per-song now, so the question is only
        // ever asked of settled rows.
        UUID album = insertDownload("PENDING", "ALBUM");
        admit(album, "a", "b");
        UUID song = admitOneSong("PENDING");
        for (UUID task : taskIdsOf(album)) {
            downloadService.finishTask(task, DownloadStatus.SUCCEEDED, null, NOW).block();
        }
        downloadService.finishTask(taskIdOf(song), DownloadStatus.FAILED,
                DownloadFailureCode.TIMED_OUT, NOW).block();

        assertEquals(2L, repository.concludeDownloads().block());
        assertEquals("SUCCEEDED", statusOf(album));
        assertEquals("FAILED", statusOf(song));
    }

    @Test
    void failUnadmitted_failsARequestWhoseMetadataNeverArrived() {
        UUID id = insertDownload("PENDING");

        assertEquals(1L, repository.failUnadmitted(id).block());

        assertEquals("FAILED", statusOf(id));
        assertEquals(0L, countTaskRows(), "there was never anything to search for");
    }

    @Test
    void failUnadmitted_doesNotTouchADownloadThatWasAlreadyAdmitted() {
        UUID id = admitOneSong("PENDING");

        assertEquals(0L, repository.failUnadmitted(id).block());
        assertEquals("IN_PROGRESS", statusOf(id));
    }

    @Test
    void save_advancesUpdatedAt() {
        UUID id = admitOneSong("PENDING");
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
        UUID id = admitOneSong("PENDING");
        downloadService.finishTask(taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW).block();

        assertTrue(repository
                .claimDueTasks(10, "a", NOW.plusSeconds(86_400), Duration.ofSeconds(60), true)
                .collectList().block().isEmpty(),
                "a finished download must never be stepped again");
    }

    @Test
    void claimDueTasks_withNoTransferSlots_stillReturnsSearchTasks_butNotDownloadInit() {
        UUID searching = admitOneSong("PENDING");
        UUID starting = admitOneSong("PENDING");
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
            moveToPhase(admitOneSong("PENDING"), DownloadPhase.DOWNLOAD_INIT);
        }

        assertEquals(maxConcurrentTransfers, countTaskRowsInPhase("DOWNLOAD_INIT"),
                "sanity check: every row really is sitting in DOWNLOAD_INIT");
        assertEquals(0L, repository.countActiveTransfers().block(),
                "DOWNLOAD_INIT rows must never count against the transfer cap -- otherwise the cap "
                        + "closes on rows that can never be claimed while it is closed, and never reopens");
    }

    @Test
    void claimDueTasks_toleratesARowWithCorruptCandidatesJson_andStillReturnsTheOtherValidRow() {
        UUID corrupt = admitOneSong("PENDING");
        UUID healthy = admitOneSong("PENDING");
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

    private String songNameOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT song_name FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("song_name", String.class)).one().block();
    }

    private String phaseOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).one().block();
    }

    private List<String> phasesOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id ORDER BY song_name")
                .bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).all().collectList().block();
    }

    private UUID taskIdOf(UUID downloadId) {
        return taskIdsOf(downloadId).getFirst();
    }

    private List<UUID> taskIdsOf(UUID downloadId) {
        return template.getDatabaseClient()
                .sql("SELECT task_id FROM download_tasks WHERE download_id = :id ORDER BY song_name")
                .bind("id", downloadId)
                .map((row, meta) -> row.get("task_id", UUID.class)).all().collectList().block();
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
