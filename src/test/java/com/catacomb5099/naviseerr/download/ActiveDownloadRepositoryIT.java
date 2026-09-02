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
     * is no longer written by production either, so it is not written here -- the read path's
     * projection joins {@code songs} for the name instead, which is why these views still report the
     * song name (the literal {@code 'song'} inserted below) rather than a null one.
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

    /**
     * Like {@link #insertDownload}, but with real song metadata, for the tests below that assert
     * {@code artists}/{@code imageUrl} round-trip through the read path rather than merely arriving
     * as the empty-artists/null-image shape {@link #insertDownload} already produces.
     */
    private UUID insertDownloadWithSong(String status, String name, List<String> artists,
                                         String imageUrl) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, status, created_at) "
                        + "VALUES (:id, :status, now())")
                .bind("id", id).bind("status", status)
                .fetch().rowsUpdated().block();
        template.getDatabaseClient()
                .sql("INSERT INTO songs (song_id, download_id, name, artists, image_url) "
                        + "VALUES (gen_random_uuid(), :id, :name, :artists, :imageUrl)")
                .bind("id", id).bind("name", name)
                .bind("artists", artists.toArray(String[]::new))
                .bind("imageUrl", imageUrl)
                .fetch().rowsUpdated().block();
        return id;
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

    // ---- song metadata (artists / image) --------------------------------------------------------

    @Test
    void findActive_liveBranch_carriesArtistsAndImage() {
        // The live branch's FROM clause inner joins songs alongside its LEFT JOIN to download_tasks;
        // this exercises that join with a task-less (PENDING) row.
        UUID id = insertDownloadWithSong("PENDING", "Riptide", List.of("Vance Joy"),
                "https://example.com/cover.jpg");

        ActiveDownloadView view = viewOf(active(), id);

        assertEquals("Riptide", view.songName());
        assertEquals(List.of("Vance Joy"), view.artists());
        assertEquals("https://example.com/cover.jpg", view.imageUrl());
    }

    @Test
    void findActive_terminalBranch_carriesArtistsAndImage() {
        // The finished arm of the UNION ALL has its own songs join, separate from the live arm's --
        // both need covering, not just one.
        UUID id = insertDownloadWithSong("PENDING", "Riptide", List.of("Vance Joy"),
                "https://example.com/cover.jpg");
        taskRepository.admitNewDownloads(10, NOW).block();
        downloadService.finishDownload(id, DownloadStatus.SUCCEEDED, null, NOW).block();

        ActiveDownloadView view = viewOf(active(), id);

        assertEquals("Riptide", view.songName());
        assertEquals(List.of("Vance Joy"), view.artists());
        assertEquals("https://example.com/cover.jpg", view.imageUrl());
    }

    @Test
    void findByIds_carriesArtistsAndImage() {
        UUID id = insertDownloadWithSong("PENDING", "Riptide", List.of("Vance Joy", "Producer X"),
                "https://example.com/cover.jpg");

        List<ActiveDownloadView> resolved =
                activeDownloadRepository.findByIds(List.of(id)).collectList().block();

        assertEquals(1, resolved.size());
        ActiveDownloadView view = resolved.getFirst();
        assertEquals("Riptide", view.songName());
        assertEquals(List.of("Vance Joy", "Producer X"), view.artists());
        assertEquals("https://example.com/cover.jpg", view.imageUrl());
    }

    @Test
    void findAll_carriesArtistsAndImage() {
        insertDownloadWithSong("PENDING", "Riptide", List.of("Vance Joy"),
                "https://example.com/cover.jpg");

        AllDownloadsResponse response = activeDownloadRepository.findAll(10, 1).block();

        assertEquals(1, response.downloads().size());
        ActiveDownloadView view = response.downloads().getFirst();
        assertEquals("Riptide", view.songName());
        assertEquals(List.of("Vance Joy"), view.artists());
        assertEquals("https://example.com/cover.jpg", view.imageUrl());
    }

    @Test
    void findActive_backfilledStyleRow_reportsNameWithEmptyArtistsAndNullImage() {
        // What every row created through the deprecated path-based route -- and every row that
        // existed before this table did -- looks like: songs.artists defaults to '{}' rather than
        // arriving null, and image_url is genuinely absent. The read path must report that as empty,
        // not error out reading a NULL array.
        UUID id = insertDownload("PENDING");

        ActiveDownloadView view = viewOf(active(), id);

        assertEquals("song", view.songName());
        assertEquals(List.of(), view.artists());
        assertNull(view.imageUrl());
    }

    private static ActiveDownloadView viewOf(List<ActiveDownloadView> views, UUID id) {
        return views.stream().filter(v -> v.downloadId().equals(id)).findFirst().orElseThrow();
    }
}
