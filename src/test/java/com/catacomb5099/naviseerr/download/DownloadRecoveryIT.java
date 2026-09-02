package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import com.catacomb5099.naviseerr.schema.request.TrackQuery;
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
        // songs.download_id has no ON DELETE CASCADE, so it must go before downloads -- otherwise a
        // songs row left behind by another test class in this shared Testcontainers instance (e.g.
        // one exercising DownloadService.requestDownload) blocks this delete with a FK violation.
        template.getDatabaseClient().sql("DELETE FROM songs").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    /**
     * A download and its song, the only shape production can produce (one CTE writes both) and the
     * only shape {@code ADMIT_SQL} admits, since it inner joins {@code songs} for the {@code song_id}
     * it writes. {@code downloads.song_name} is no longer written by production, so it is not written
     * here either.
     */
    private UUID insert(String status) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, status, created_at) "
                        + "VALUES (:id, :status, now())")
                .bind("id", id).bind("status", status).fetch().rowsUpdated().block();
        template.getDatabaseClient()
                .sql("INSERT INTO songs (song_id, download_id, name) "
                        + "VALUES (gen_random_uuid(), :id, 'song')")
                .bind("id", id).fetch().rowsUpdated().block();
        return id;
    }

    @Test
    void aDownloadKilledMidTransferResumesAtTheSameStep_notFromScratch() {
        UUID id = insert("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository.claimDueTasks(10, "dead", NOW, LEASE, true).blockFirst();

        // The dead process had got as far as polling candidate 1's transfer.
        repository.save(new DownloadTask(id, new TrackQuery("song"), DownloadPhase.DOWNLOAD_POLL, NOW,
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
    void aDownloadReachingTerminalTwiceKeepsItsFirstOutcome() {
        UUID id = insert("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.FAILED,
                DownloadFailureCode.SOURCES_EXHAUSTED, NOW).block();

        assertEquals("SUCCEEDED", template.getDatabaseClient()
                .sql("SELECT status FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("status", String.class)).one().block());
        // The task row keeps the first outcome too, rather than being overwritten by the duplicate.
        // The livelock this guard used to prevent by writing unconditionally is now handled by the
        // other half of it -- "or the task is still non-terminal" -- which a duplicate finish does not
        // satisfy but an orphaned task does. See DownloadService.FINISH_DOWNLOAD_SQL.
        assertEquals("SUCCEEDED", template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).one().block(),
                "a duplicate finish must not rewrite a settled outcome");
    }

    /**
     * The upgrade case. A task row created before the loop knew about {@code songs} was never
     * inserted by today's {@code ADMIT_SQL}: it was written with a {@code song_name} of its own and
     * had its {@code song_id} filled in afterwards by {@code V5__song_metadata.sql}'s backfill. That
     * row must keep being claimable and keep reporting its name -- read off the join now, not off the
     * column it was originally written with. This is what a self-hoster's in-flight downloads look
     * like the moment they restart onto this version, so if the rewrite only worked for rows the new
     * ADMIT_SQL created, every download in flight during the upgrade would stall silently.
     */
    @Test
    void aTaskRowBackfilledByTheMigrationStillResumes_readingItsNameFromTheJoin() {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, song_name, status, created_at) "
                        + "VALUES (:id, 'pre-migration name', 'IN_PROGRESS', now())")
                .bind("id", id).fetch().rowsUpdated().block();
        UUID songId = template.getDatabaseClient()
                .sql("INSERT INTO songs (song_id, download_id, name, artists) "
                        + "VALUES (gen_random_uuid(), :id, 'pre-migration name', '{}') "
                        + "RETURNING song_id")
                .bind("id", id)
                .map((row, meta) -> row.get("song_id", UUID.class)).one().block();
        // song_name still populated, exactly as the pre-V5 writer left it, alongside the song_id the
        // backfill added. Nothing may read that column any more.
        template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (download_id, song_id, song_name, phase, "
                        + "phase_entered_at, next_attempt_at) "
                        + "VALUES (:id, :songId, 'pre-migration name', 'SEARCH_POLL', :now, :now)")
                .bind("id", id).bind("songId", songId).bind("now", NOW)
                .fetch().rowsUpdated().block();

        DownloadTask resumed = repository.claimDueTasks(10, "alive", NOW, LEASE, true).blockFirst();

        assertNotNull(resumed, "a backfilled task row must still be claimable");
        assertEquals(id, resumed.downloadId());
        assertEquals(DownloadPhase.SEARCH_POLL, resumed.phase(), "it resumes where it was, not from scratch");
        assertEquals("pre-migration name", resumed.songName());
        assertEquals(List.of(), resumed.query().artists(), "the backfill knows no artists");
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
