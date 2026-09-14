package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The properties this whole change exists to provide. Each test simulates a crash by leaving state
 * exactly as a dead process would have left it, then checks that the loop's own queries recover it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadRecoveryIT {

    @Autowired R2dbcEntityTemplate template;
    @Autowired DownloadTaskRepository repository;
    @Autowired DownloadService downloadService;

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);

    @BeforeEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    private UUID insert(String status) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                        + "VALUES (:id, 'yt-1', 'SONG', :status, now())")
                .bind("id", id).bind("status", status).fetch().rowsUpdated().block();
        return id;
    }

    /**
     * The other half of admission: the runner's ytmusic-adapter call sits between
     * {@code admitDownloads} and {@code createTasks}, so fixtures do what the runner does.
     */
    private void admit(UUID downloadId) {
        repository.createTasks(downloadId, "song",
                List.of(DownloadTask.initial(downloadId, "yt-1", "song", NOW)), NOW).block();
    }

    private String statusOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT status FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("status", String.class)).one().block();
    }

    private UUID taskIdOf(UUID downloadId) {
        return template.getDatabaseClient()
                .sql("SELECT task_id FROM download_tasks WHERE download_id = :id")
                .bind("id", downloadId)
                .map((row, meta) -> row.get("task_id", UUID.class)).one().block();
    }

    @Test
    void aDownloadKilledMidTransferResumesAtTheSameStep_notFromScratch() {
        UUID id = insert("PENDING");
        admit(id);
        DownloadTask claimed = repository.claimDueTasks(10, "dead", NOW, LEASE, true).blockFirst();

        // The dead process had got as far as polling candidate 1's transfer.
        repository.save(claimed.toBuilder()
                .phase(DownloadPhase.DOWNLOAD_POLL)
                .nextAttemptAt(NOW.plusSeconds(5))
                .searchId("s1")
                .candidates(com.catacomb5099.naviseerr.support.DownloadTaskFixtures
                        .candidates("alice", "bob"))
                .candidateIndex(1).retryIndex(0)
                .slskdUsername("bob").slskdFilename("music/bob/song.flac").slskdTransferId("abc")
                .build(), "dead").block();
        // ...then took the lease and died without clearing it.
        repository.claimDueTasks(10, "dead", NOW.plusSeconds(5), LEASE, true).blockFirst();

        DownloadTask resumed = repository
                .claimDueTasks(10, "alive", NOW.plusSeconds(70), LEASE, true).blockFirst();

        assertNotNull(resumed, "an expired lease must make the row claimable again");
        assertEquals(DownloadPhase.DOWNLOAD_POLL, resumed.phase());
        assertEquals("abc", resumed.slskdTransferId());
        assertEquals(1, resumed.candidateIndex());
    }

    @Test
    void aRequestWhoseMetadataCallDiedIsStillPending_soTheNextPassRetriesIt() {
        // The crash window admission gained when it stopped being one statement: the select ran,
        // the ytmusic-adapter call was in flight, the process died. The row has to be untouched, or
        // the next pass cannot pick it up.
        UUID id = insert("PENDING");
        repository.admitDownloads(10).collectList().block();

        assertEquals("PENDING", statusOf(id));
        assertEquals(1, repository.admitDownloads(10).collectList().block().size(),
                "a request that never got its metadata must remain admissible");
    }

    @Test
    void aTerminalDownloadIsNeverReadmitted() {
        insert("SUCCEEDED");

        assertTrue(repository.admitDownloads(10).collectList().block().isEmpty());
        assertEquals(0L, repository.countActiveTransfers().block());
    }

    @Test
    void aDownloadReachingTerminalTwiceKeepsItsFirstOutcome() {
        UUID id = insert("PENDING");
        admit(id);
        UUID taskId = taskIdOf(id);

        downloadService.finishTask(taskId, DownloadStatus.SUCCEEDED, null, NOW).block();
        downloadService.finishTask(taskId, DownloadStatus.FAILED,
                DownloadFailureCode.SOURCES_EXHAUSTED, NOW).block();
        repository.concludeDownloads().block();

        assertEquals("SUCCEEDED", statusOf(id));
        // The task row keeps the first outcome, rather than being overwritten by the duplicate.
        // The guard is now just "still non-terminal": the livelock its second half used to prevent
        // (a download going terminal by a path that never marked its task) cannot happen any more,
        // because the download's status is derived FROM the task rows rather than written beside
        // them. See DownloadService.FINISH_TASK_SQL and DownloadTaskRepository.CONCLUDE_SQL.
        assertEquals("SUCCEEDED", template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).one().block(),
                "a duplicate finish must not rewrite a settled outcome");
    }

    @Test
    void aFinishedDownloadIsNeverSteppedAgain() {
        UUID id = insert("PENDING");
        admit(id);
        downloadService.finishTask(taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW).block();

        // Far in the future, so next_attempt_at is long past. Only the terminal-phase filter and
        // the partial index stop this row coming back.
        assertNull(repository.claimDueTasks(10, "a", NOW.plusSeconds(86_400), LEASE, true)
                .blockFirst());
    }
}
