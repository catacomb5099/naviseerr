package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadServiceClaimIT {

    @Autowired
    DownloadService downloadService;

    @Autowired
    R2dbcEntityTemplate template;

    @BeforeEach
    void clean() {
        // songs.download_id has no ON DELETE CASCADE, so it must go before downloads -- otherwise a
        // songs row left behind by another test class in this shared Testcontainers instance (e.g.
        // one exercising DownloadService.requestDownload) blocks this delete with a FK violation.
        template.getDatabaseClient().sql("DELETE FROM songs").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    @Test
    void claimsOldestPendingBatchAndFlipsToInProgress() {
        Instant base = Instant.now().minusSeconds(60);
        insertPending("song-1", base);
        insertPending("song-2", base.plusSeconds(10));
        insertPending("song-3", base.plusSeconds(20));

        List<Download> claimed = downloadService.claimPendingDownloads(2).collectList().block();

        assertEquals(2, claimed.size());
        assertTrue(claimed.stream().allMatch(d -> d.getStatus() == DownloadStatus.IN_PROGRESS));
        Set<String> claimedNames = claimed.stream().map(Download::getSongName).collect(Collectors.toSet());
        assertEquals(Set.of("song-1", "song-2"), claimedNames);

        List<Download> all = findAllOrderedByCreatedAt();
        assertEquals(3, all.size());
        assertEquals("song-1", all.get(0).getSongName());
        assertEquals(DownloadStatus.IN_PROGRESS, all.get(0).getStatus());
        assertEquals("song-2", all.get(1).getSongName());
        assertEquals(DownloadStatus.IN_PROGRESS, all.get(1).getStatus());
        assertEquals("song-3", all.get(2).getSongName());
        assertEquals(DownloadStatus.PENDING, all.get(2).getStatus());
    }

    @Test
    void doesNotReclaimAlreadyClaimedRows() {
        insertPending("a", Instant.now().minusSeconds(5));
        insertPending("b", Instant.now());

        List<Download> first = downloadService.claimPendingDownloads(10).collectList().block();
        assertEquals(2, first.size());

        List<Download> second = downloadService.claimPendingDownloads(10).collectList().block();
        assertTrue(second.isEmpty());
    }

    @Test
    void returnsEmptyWhenNothingPending() {
        List<Download> claimed = downloadService.claimPendingDownloads(10).collectList().block();
        assertTrue(claimed.isEmpty());
    }

    @Test
    void markStatusIfInProgress_flipsInProgressRowToTerminalStatus() {
        UUID id = UUID.randomUUID();
        insertWithStatus(id, "song-x", DownloadStatus.IN_PROGRESS);

        Long updated = downloadService.markStatusIfInProgress(id, DownloadStatus.SUCCEEDED).block();

        assertEquals(1L, updated.longValue());
        List<Download> all = findAllOrderedByCreatedAt();
        assertEquals(1, all.size());
        assertEquals(DownloadStatus.SUCCEEDED, all.get(0).getStatus());
    }

    @Test
    void markStatusIfInProgress_doesNotTouchRowsThatAreNotInProgress() {
        UUID id = UUID.randomUUID();
        insertWithStatus(id, "song-y", DownloadStatus.PENDING);

        Long updated = downloadService.markStatusIfInProgress(id, DownloadStatus.SUCCEEDED).block();

        assertEquals(0L, updated.longValue());
        List<Download> all = findAllOrderedByCreatedAt();
        assertEquals(DownloadStatus.PENDING, all.get(0).getStatus());
    }

    private void insertWithStatus(UUID id, String songName, DownloadStatus status) {
        Download download = Download.builder()
                .downloadId(id)
                .songName(songName)
                .status(status)
                .createdAt(Instant.now())
                .build();
        template.insert(download).block();
    }

    private void insertPending(String songName, Instant createdAt) {
        Download download = Download.builder()
                .downloadId(UUID.randomUUID())
                .songName(songName)
                .status(DownloadStatus.PENDING)
                .createdAt(createdAt)
                .build();
        template.insert(download).block();
    }

    private List<Download> findAllOrderedByCreatedAt() {
        return template.getDatabaseClient()
                .sql("SELECT download_id, song_name, status, created_at FROM downloads ORDER BY created_at")
                .map((row, meta) -> template.getConverter().read(Download.class, row, meta))
                .all()
                .collectList()
                .block();
    }
}
