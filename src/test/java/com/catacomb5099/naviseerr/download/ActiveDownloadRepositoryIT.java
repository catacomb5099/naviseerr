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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class ActiveDownloadRepositoryIT {

    @Autowired R2dbcEntityTemplate template;
    @Autowired DownloadTaskRepository taskRepository;
    @Autowired DownloadService downloadService;
    @Autowired ActiveDownloadRepository activeDownloadRepository;

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @BeforeEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
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
    void findActive_returnsNonTerminalDownloadsWithTheirTaskState() {
        UUID id = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = taskRepository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true).blockFirst();
        taskRepository.save(claimed.withPhase(DownloadPhase.DOWNLOAD_POLL, NOW)
                .withProgress(new BigDecimal("43.00")), "a").block();

        List<ActiveDownloadView> active = activeDownloadRepository.findActive().collectList().block();

        assertEquals(1, active.size());
        ActiveDownloadView view = active.getFirst();
        assertEquals(id, view.downloadId());
        assertEquals(DownloadStatus.IN_PROGRESS, view.status());
        assertEquals(DownloadPhase.DOWNLOAD_POLL, view.phase());
        assertEquals(0, view.progressPercent().compareTo(new BigDecimal("43.00")));
    }

    @Test
    void findActive_excludesSucceededAndFailedDownloads() {
        UUID succeeded = insertDownload("PENDING");
        UUID failed = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(succeeded, DownloadStatus.SUCCEEDED, null, NOW).block();
        downloadService.finishDownload(failed, DownloadStatus.FAILED, "boom", NOW).block();

        assertTrue(activeDownloadRepository.findActive().collectList().block().isEmpty());
    }

    @Test
    void findActive_ordersByCreatedAt() {
        UUID first = insertDownload("PENDING");
        template.getDatabaseClient()
                .sql("UPDATE downloads SET created_at = :t WHERE download_id = :id")
                .bind("t", NOW.minusSeconds(10)).bind("id", first).fetch().rowsUpdated().block();
        UUID second = insertDownload("PENDING");
        template.getDatabaseClient()
                .sql("UPDATE downloads SET created_at = :t WHERE download_id = :id")
                .bind("t", NOW).bind("id", second).fetch().rowsUpdated().block();
        taskRepository.admitNewDownloads(10, NOW).block();

        List<ActiveDownloadView> active = activeDownloadRepository.findActive().collectList().block();

        assertEquals(List.of(first, second), active.stream().map(ActiveDownloadView::downloadId).toList());
    }
}
