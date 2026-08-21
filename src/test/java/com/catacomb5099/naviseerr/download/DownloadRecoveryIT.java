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
                .sql("INSERT INTO downloads (download_id, song_name, status, created_at) "
                        + "VALUES (:id, 'song', :status, now())")
                .bind("id", id).bind("status", status).fetch().rowsUpdated().block();
        return id;
    }

    @Test
    void aDownloadKilledMidTransferResumesAtTheSameStep_notFromScratch() {
        UUID id = insert("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository.claimDueTasks(10, "dead", NOW, LEASE, true).blockFirst();

        // The dead process had got as far as polling candidate 1's transfer.
        repository.save(new DownloadTask(id, "song", DownloadPhase.DOWNLOAD_POLL, NOW,
                NOW.plusSeconds(5), "s1",
                com.catacomb5099.naviseerr.support.DownloadTaskFixtures.candidates("alice", "bob"),
                1, 0, "bob", "music/bob/song.flac", "abc", null), "dead").block();
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
    void anInProgressDownloadThatLostItsTaskRowIsRecovered_notStrandedForever() {
        UUID id = insert("IN_PROGRESS");   // exactly today's stranded-row state

        repository.admitNewDownloads(10, NOW).block();

        DownloadTask recovered = repository.claimDueTasks(10, "a", NOW, LEASE, true).blockFirst();
        assertNotNull(recovered);
        assertEquals(id, recovered.downloadId());
        assertEquals(DownloadPhase.SEARCH_INIT, recovered.phase());
    }

    @Test
    void aTerminalDownloadIsNeverReadmitted() {
        insert("SUCCEEDED");

        assertEquals(0L, repository.admitNewDownloads(10, NOW).block());
        assertEquals(0L, repository.countActiveTransfers().block());
    }

    @Test
    void aDownloadReachingTerminalTwiceKeepsItsFirstStatus() {
        UUID id = insert("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.FAILED, "boom", NOW).block();

        assertEquals("SUCCEEDED", template.getDatabaseClient()
                .sql("SELECT status FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("status", String.class)).one().block());
        assertEquals("FAILED", template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).one().block(),
                "the task write is unconditional, which is what prevents a livelock");
    }

    @Test
    void aFinishedDownloadIsNeverSteppedAgain() {
        UUID id = insert("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        // Far in the future, so next_attempt_at is long past. Only the terminal-phase filter and
        // the partial index stop this row coming back.
        assertNull(repository.claimDueTasks(10, "a", NOW.plusSeconds(86_400), LEASE, true)
                .blockFirst());
    }
}
