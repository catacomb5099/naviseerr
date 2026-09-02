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
import java.util.Set;
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
    /** Wide enough that anything finished at NOW is inside it. */
    private static final Instant ANCIENT_CUTOFF = NOW.minus(Duration.ofDays(1));

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
     * A download and its song. The song row is what {@code ADMIT_SQL} joins for {@code song_id}, so
     * without it none of the tests below that admit a task would get one. {@code downloads.song_name}
     * is no longer written by production either, so it is not written here -- this repository's
     * projection still selects it, which is why these views report a null song name until the read
     * path joins {@code songs} too.
     */
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

    private List<ActiveDownloadView> active() {
        return activeDownloadRepository.findActive(ANCIENT_CUTOFF).collectList().block();
    }

    // ---- stage mapping -------------------------------------------------------------------------

    @Test
    void findActive_reportsADownloadWithNoTaskRowAsQueued() {
        // The whole point of the LEFT JOIN. This is the window between the user clicking and the
        // runner admitting the download -- possibly a long one, if it is at the concurrency limit --
        // and an inner join made every download in it invisible to the client.
        UUID id = insertDownload("PENDING");

        List<ActiveDownloadView> active = active();

        assertEquals(1, active.size());
        ActiveDownloadView view = active.getFirst();
        assertEquals(id, view.downloadId());
        assertEquals(DownloadStage.QUEUED, view.stage());
        assertNull(view.progressPercent(), "nothing has observed a transfer yet");
        assertNotNull(view.updatedAt(), "must still sort, so it falls back to created_at");
    }

    @Test
    void findActive_mapsEachWorkingPhaseToItsOwnStage() {
        assertEquals(DownloadStage.STARTING,
                stageOfAdmittedTaskIn(DownloadPhase.SEARCH_INIT));
        assertEquals(DownloadStage.SEARCHING,
                stageOfAdmittedTaskIn(DownloadPhase.SEARCH_POLL));
        assertEquals(DownloadStage.READY_TO_DOWNLOAD,
                stageOfAdmittedTaskIn(DownloadPhase.DOWNLOAD_INIT));
        assertEquals(DownloadStage.DOWNLOADING,
                stageOfAdmittedTaskIn(DownloadPhase.DOWNLOAD_POLL));
    }

    private DownloadStage stageOfAdmittedTaskIn(DownloadPhase phase) {
        clean();
        insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET phase = :phase")
                .bind("phase", phase.name()).fetch().rowsUpdated().block();
        return active().getFirst().stage();
    }

    @Test
    void findActive_carriesProgressForALiveTransfer() {
        UUID id = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        DownloadTask claimed = taskRepository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true).blockFirst();
        taskRepository.save(claimed.withPhase(DownloadPhase.DOWNLOAD_POLL, NOW)
                .withProgress(new BigDecimal("43.00")), "a").block();

        ActiveDownloadView view = active().getFirst();

        assertEquals(id, view.downloadId());
        assertEquals(DownloadStage.DOWNLOADING, view.stage());
        assertEquals(0, view.progressPercent().compareTo(new BigDecimal("43.00")));
        assertEquals(NOW, view.stageEnteredAt());
    }

    // ---- the retention window ------------------------------------------------------------------

    @Test
    void findActive_stillReportsADownloadThatFinishedInsideTheWindow() {
        // The defect this whole endpoint change exists for: a finished download used to vanish
        // instantly, so the one update the user was waiting for was the one never delivered.
        UUID succeeded = insertDownload("PENDING");
        UUID failed = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(succeeded, DownloadStatus.SUCCEEDED, null, NOW).block();
        downloadService.finishDownload(failed, DownloadStatus.FAILED,
                DownloadFailureCode.NO_CANDIDATES, NOW).block();

        List<ActiveDownloadView> active = active();

        assertEquals(2, active.size());
        ActiveDownloadView succeededView = viewOf(active, succeeded);
        assertEquals(DownloadStage.SUCCEEDED, succeededView.stage());
        assertNull(succeededView.failureCode());
        ActiveDownloadView failedView = viewOf(active, failed);
        assertEquals(DownloadStage.FAILED, failedView.stage());
        assertEquals("NO_CANDIDATES", failedView.failureCode(),
                "the client needs a code it can word for a non-technical user");
    }

    @Test
    void findActive_dropsADownloadThatFinishedBeforeTheWindow() {
        UUID id = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        List<ActiveDownloadView> active = activeDownloadRepository
                .findActive(NOW.plusSeconds(1)).collectList().block();

        assertTrue(active.isEmpty(), "past the window the client is on its own, via findByIds");
    }

    @Test
    void findActive_neverReportsTheSameDownloadTwice() {
        // The two UNION ALL branches are kept disjoint by predicate, not by luck.
        UUID pending = insertDownload("PENDING");
        UUID inFlight = insertDownload("PENDING");
        UUID finished = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(finished, DownloadStatus.SUCCEEDED, null, NOW).block();

        List<UUID> ids = active().stream().map(ActiveDownloadView::downloadId).toList();

        assertEquals(3, ids.size());
        assertEquals(Set.of(pending, inFlight, finished), Set.copyOf(ids));
    }

    // ---- ordering ------------------------------------------------------------------------------

    @Test
    void findActive_ordersMostRecentlyUpdatedFirst_acrossBothBranches() {
        // Written oldest-first; every one of them is touched after the one before, so the expected
        // order is the exact reverse of the insertion order.
        UUID first = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        UUID second = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        UUID third = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        // A terminal row is the most recently touched of the three, and must sort as such rather
        // than being segregated by which branch found it.
        downloadService.finishDownload(third, DownloadStatus.SUCCEEDED, null, NOW).block();

        List<UUID> ids = active().stream().map(ActiveDownloadView::downloadId).toList();

        assertEquals(List.of(third, second, first), ids);
    }

    // ---- resolve by id -------------------------------------------------------------------------

    @Test
    void findByIds_resolvesADownloadThatAgedOutOfTheWindow() {
        // What makes a client's stored cards honest across a restart rather than merely persistent.
        UUID id = insertDownload("PENDING");
        taskRepository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.FAILED, DownloadFailureCode.TIMED_OUT, NOW)
                .block();
        assertTrue(activeDownloadRepository.findActive(NOW.plusSeconds(1)).collectList().block()
                .isEmpty(), "precondition: aged out of the feed");

        List<ActiveDownloadView> resolved =
                activeDownloadRepository.findByIds(List.of(id)).collectList().block();

        assertEquals(1, resolved.size());
        assertEquals(DownloadStage.FAILED, resolved.getFirst().stage());
        assertEquals("TIMED_OUT", resolved.getFirst().failureCode());
    }

    @Test
    void findByIds_omitsIdsItHasNoRowFor() {
        // Absence here -- and only here -- is what the client is allowed to read as "gone for good".
        UUID known = insertDownload("PENDING");
        UUID unknown = UUID.randomUUID();

        List<ActiveDownloadView> resolved = activeDownloadRepository
                .findByIds(List.of(known, unknown)).collectList().block();

        assertEquals(List.of(known),
                resolved.stream().map(ActiveDownloadView::downloadId).toList());
    }

    @Test
    void findByIds_withNoIds_doesNotQuery() {
        assertTrue(activeDownloadRepository.findByIds(List.of()).collectList().block().isEmpty());
    }

    private static ActiveDownloadView viewOf(List<ActiveDownloadView> views, UUID id) {
        return views.stream().filter(v -> v.downloadId().equals(id)).findFirst().orElseThrow();
    }
}
