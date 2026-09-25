package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * progress_percent column: round-trip, the save() guard, and the terminal write. Every read is
 * compared with compareTo -- Postgres returns NUMERIC(5,2) as e.g. "43.00", and
 * BigDecimal.valueOf(43).equals(new BigDecimal("43.00")) is false.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadTaskProgressIT {

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
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                        + "VALUES (:id, 'yt-1', 'SONG', :status, now())")
                .bind("id", id).bind("status", status)
                .fetch().rowsUpdated().block();
        return id;
    }

    /**
     * The other half of admission: the runner's ytmusic-adapter call sits between
     * {@code admitDownloads} and {@code createTasks}, so fixtures do what the runner does.
     */
    private void admit(UUID downloadId) {
        repository.createTasks(downloadId,
                List.of(DownloadTask.initial(downloadId, "yt-1", "song", NOW)), NOW).block();
    }

    private UUID taskIdOf(UUID downloadId) {
        return template.getDatabaseClient()
                .sql("SELECT task_id FROM download_tasks WHERE download_id = :id")
                .bind("id", downloadId)
                .map((row, meta) -> row.get("task_id", UUID.class)).one().block();
    }

    @Test
    void newTask_startsAtZeroProgress() {
        UUID id = insertDownload("PENDING");
        admit(id);

        assertEquals(0, progressOf(id).compareTo(BigDecimal.ZERO));
    }

    @Test
    void save_roundTripsProgress() {
        UUID id = insertDownload("PENDING");
        admit(id);
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();

        DownloadTask updated = claimed.withPhase(DownloadPhase.DOWNLOAD_POLL, NOW)
                .withProgress(new BigDecimal("43.21"));
        repository.save(updated, "a").block();

        assertEquals(0, progressOf(id).compareTo(new BigDecimal("43.21")));
    }

    @Test
    void save_onlyAppliesWhileTheCallerHoldsTheLease() {
        UUID id = insertDownload("PENDING");
        admit(id);
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();

        DownloadTask updated = claimed.withProgress(new BigDecimal("90.00"));
        Long rowsUpdated = repository.save(updated, "someone-else").block();

        assertEquals(0L, rowsUpdated, "a save from a non-owning caller must affect no rows");
        assertEquals(0, progressOf(id).compareTo(BigDecimal.ZERO));
    }

    @Test
    void save_neverAppliesToATerminalRow() {
        UUID id = insertDownload("PENDING");
        admit(id);
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();
        downloadService.finishTask(taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW).block();

        Long rowsUpdated = repository.save(claimed.withProgress(new BigDecimal("50.00")), "a").block();

        assertEquals(0L, rowsUpdated, "a duplicated step must never resurrect a finished download");
        assertEquals(0, progressOf(id).compareTo(new BigDecimal("100.00")));
    }

    @Test
    void finishTask_succeeded_normalisesProgressToOneHundred() {
        UUID id = insertDownload("PENDING");
        admit(id);
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();
        repository.save(claimed.withProgress(new BigDecimal("87.00")), "a").block();

        downloadService.finishTask(taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW).block();

        assertEquals(0, progressOf(id).compareTo(new BigDecimal("100.00")),
                "a succeeded download reads 100%, regardless of the last observed transfer percentage");
    }

    @Test
    void finishTask_failed_keepsTheLastObservedProgress() {
        UUID id = insertDownload("PENDING");
        admit(id);
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();
        repository.save(claimed.withProgress(new BigDecimal("62.00")), "a").block();

        downloadService.finishTask(taskIdOf(id), DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED, NOW).block();

        assertEquals(0, progressOf(id).compareTo(new BigDecimal("62.00")),
                "unsettled per the ADR: FAILED is deliberately not forced to 100");
    }

    private BigDecimal progressOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT progress_percent FROM download_tasks WHERE download_id = :id")
                .bind("id", id)
                .map((row, meta) -> row.get("progress_percent", BigDecimal.class))
                .one().block();
    }
}
