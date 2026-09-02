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
        // songs.download_id has no ON DELETE CASCADE, so it must go before downloads -- otherwise a
        // songs row left behind by another test class in this shared Testcontainers instance (e.g.
        // one exercising DownloadService.requestDownload) blocks this delete with a FK violation.
        template.getDatabaseClient().sql("DELETE FROM songs").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    /** A download and its song: ADMIT_SQL joins `songs`, so a download without one is never admitted. */
    private UUID insertDownload(String status) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, status, created_at) "
                        + "VALUES (:id, :status, now())")
                .bind("id", id).bind("status", status)
                .fetch().rowsUpdated().block();
        template.getDatabaseClient()
                .sql("INSERT INTO songs (song_id, download_id, name) "
                        + "VALUES (gen_random_uuid(), :id, 'song')")
                .bind("id", id)
                .fetch().rowsUpdated().block();
        return id;
    }

    @Test
    void newTask_startsAtZeroProgress() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();

        assertEquals(0, progressOf(id).compareTo(BigDecimal.ZERO));
    }

    @Test
    void save_roundTripsProgress() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
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
        repository.admitNewDownloads(10, NOW).block();
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
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        Long rowsUpdated = repository.save(claimed.withProgress(new BigDecimal("50.00")), "a").block();

        assertEquals(0L, rowsUpdated, "a duplicated step must never resurrect a finished download");
        assertEquals(0, progressOf(id).compareTo(new BigDecimal("100.00")));
    }

    @Test
    void finishDownload_succeeded_normalisesProgressToOneHundred() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();
        repository.save(claimed.withProgress(new BigDecimal("87.00")), "a").block();

        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        assertEquals(0, progressOf(id).compareTo(new BigDecimal("100.00")),
                "a succeeded download reads 100%, regardless of the last observed transfer percentage");
    }

    @Test
    void finishDownload_failed_keepsTheLastObservedProgress() {
        UUID id = insertDownload("PENDING");
        repository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true)
                .blockFirst();
        repository.save(claimed.withProgress(new BigDecimal("62.00")), "a").block();

        downloadService.finishDownload(id, DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED, NOW).block();

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
