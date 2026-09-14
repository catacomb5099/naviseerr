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
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
    }

    private UUID insertDownload(String status) {
        return insertDownload(status, "SONG");
    }

    private UUID insertDownload(String status, String type) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                        + "VALUES (:id, 'yt-1', :type, :status, now())")
                .bind("id", id).bind("type", type).bind("status", status)
                .fetch().rowsUpdated().block();
        return id;
    }

    /**
     * The other half of admission -- the runner's ytmusic-adapter call sits between
     * {@code admitDownloads} and this. One task row per name, so a multi-name call is a collection.
     */
    private void admit(UUID downloadId, String... songNames) {
        taskRepository.createTasks(downloadId, songNames[0],
                java.util.Arrays.stream(songNames)
                        .map(name -> DownloadTask.initial(downloadId, "yt-" + name, name, NOW))
                        .toList(),
                NOW).block();
    }

    /** Finishes a song and settles its download, which is what one runner pass does. */
    private void finish(UUID downloadId, DownloadStatus status, DownloadFailureCode code) {
        for (UUID taskId : taskIdsOf(downloadId)) {
            downloadService.finishTask(taskId, status, code, NOW).block();
        }
        taskRepository.concludeDownloads().block();
    }

    private List<UUID> taskIdsOf(UUID downloadId) {
        return template.getDatabaseClient()
                .sql("SELECT task_id FROM download_tasks WHERE download_id = :id ORDER BY song_name")
                .bind("id", downloadId)
                .map((row, meta) -> row.get("task_id", UUID.class)).all().collectList().block();
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
        admit(insertDownload("PENDING"), "song");
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET phase = :phase")
                .bind("phase", phase.name()).fetch().rowsUpdated().block();
        return active().getFirst().stage();
    }

    @Test
    void findActive_carriesProgressForALiveTransfer() {
        UUID id = insertDownload("PENDING");
        admit(id, "song");
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
        admit(succeeded, "song");
        admit(failed, "song");
        finish(succeeded, DownloadStatus.SUCCEEDED, null);
        finish(failed, DownloadStatus.FAILED, DownloadFailureCode.NO_CANDIDATES);

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
        admit(id, "song");
        finish(id, DownloadStatus.SUCCEEDED, null);

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
        admit(pending, "song");
        admit(inFlight, "song");
        admit(finished, "song");
        finish(finished, DownloadStatus.SUCCEEDED, null);

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
        admit(first, "song");
        UUID second = insertDownload("PENDING");
        admit(second, "song");
        UUID third = insertDownload("PENDING");
        admit(third, "song");
        // A terminal row is the most recently touched of the three, and must sort as such rather
        // than being segregated by which branch found it.
        finish(third, DownloadStatus.SUCCEEDED, null);

        List<UUID> ids = active().stream().map(ActiveDownloadView::downloadId).toList();

        assertEquals(List.of(third, second, first), ids);
    }

    // ---- resolve by id -------------------------------------------------------------------------

    @Test
    void findByIds_resolvesADownloadThatAgedOutOfTheWindow() {
        // What makes a client's stored cards honest across a restart rather than merely persistent.
        UUID id = insertDownload("PENDING");
        admit(id, "song");
        finish(id, DownloadStatus.FAILED, DownloadFailureCode.TIMED_OUT);
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

    // ---- collections: N task rows, still one card ----------------------------------------------

    @Test
    void findActive_reportsAnAlbumAsOneRowNotOnePerSong() {
        // The regression 1:N task rows would otherwise introduce: a plain join emits a ten-track
        // album as ten cards sharing one downloadId, and the client has no way to fold them back.
        UUID album = insertDownload("PENDING", "ALBUM");
        admit(album, "a", "b", "c");

        List<ActiveDownloadView> active = active();

        assertEquals(1, active.size());
        assertEquals(album, active.getFirst().downloadId());
    }

    @Test
    void findActive_reportsTheLeastAdvancedSongsStage() {
        UUID album = insertDownload("PENDING", "ALBUM");
        admit(album, "ahead", "behind");
        // One song is transferring, the other has not started searching. The album is not
        // "downloading" -- the honest summary of mixed progress is the part that is not done.
        moveOneSongTo(album, "ahead", DownloadPhase.DOWNLOAD_POLL);

        assertEquals(DownloadStage.STARTING, active().getFirst().stage());
    }

    @Test
    void findActive_averagesProgressAcrossAnAlbumsSongs() {
        UUID album = insertDownload("PENDING", "ALBUM");
        admit(album, "one", "two");
        setProgress(album, "one", "100.00");
        setProgress(album, "two", "50.00");

        assertEquals(0, active().getFirst().progressPercent().compareTo(new BigDecimal("75.00")),
                "a collection's bar tracks the collection, not whichever song is transferring");
    }

    @Test
    void findActive_reportsPartialSuccessForAnAlbumThatGotSomeOfItsSongs() {
        UUID album = insertDownload("PENDING", "ALBUM");
        admit(album, "found", "missing");
        List<UUID> tasks = taskIdsOf(album);
        downloadService.finishTask(tasks.get(0), DownloadStatus.SUCCEEDED, null, NOW).block();
        downloadService.finishTask(tasks.get(1), DownloadStatus.FAILED,
                DownloadFailureCode.NO_CANDIDATES, NOW).block();
        taskRepository.concludeDownloads().block();

        ActiveDownloadView view = active().getFirst();

        assertEquals(DownloadStage.PARTIAL_SUCCESS, view.stage());
        // Without PARTIAL_SUCCESS in the terminal branch's status filter, a half-succeeded
        // collection would finish and then never be reported at all.
        assertEquals("NO_CANDIDATES", view.failureCode(),
                "a collection reports a reason as soon as one of its songs has one");
    }

    // ---- the history endpoint ------------------------------------------------------------------
    //
    // findAll had no test at all before the collections change. Its query is the one the aggregate
    // could break most quietly: COUNT(*) OVER () has to count DOWNLOADS, and if the aggregate ever
    // stops folding an album to one row, totalPages starts counting songs while claiming to count
    // downloads -- and the client's pager silently reports the wrong number of pages.

    @Test
    void findAll_countsDownloadsNotSongs() {
        UUID album = insertDownload("PENDING", "ALBUM");
        admit(album, "a", "b", "c", "d", "e");
        UUID song = insertDownload("PENDING");
        admit(song, "just one");

        AllDownloadsResponse page = activeDownloadRepository.findAll(10, 1).block();

        assertEquals(2, page.downloads().size(), "five tracks and a single are two downloads");
        assertEquals(1, page.totalPages());
    }

    @Test
    void findAll_pagesOverDownloads() {
        for (int i = 0; i < 5; i++) {
            admit(insertDownload("PENDING"), "song " + i);
        }

        AllDownloadsResponse first = activeDownloadRepository.findAll(2, 1).block();
        AllDownloadsResponse last = activeDownloadRepository.findAll(2, 3).block();

        assertEquals(2, first.downloads().size());
        assertEquals(3, first.totalPages(), "5 downloads at 2 per page");
        assertEquals(1, last.downloads().size());
    }

    @Test
    void findAll_pastTheEnd_reportsZeroPages_soTheClientGoesBackToPageOne() {
        admit(insertDownload("PENDING"), "song");

        AllDownloadsResponse beyond = activeDownloadRepository.findAll(20, 5).block();

        // No rows means the window function has nothing to report, so the true total is unknowable
        // from this query alone -- 0 is the agreed signal rather than a second round trip.
        assertTrue(beyond.downloads().isEmpty());
        assertEquals(0, beyond.totalPages());
    }

    @Test
    void findAll_includesTerminalDownloadsHoweverOldTheyAre() {
        UUID id = insertDownload("PENDING");
        admit(id, "song");
        finish(id, DownloadStatus.SUCCEEDED, null);

        // This is the history endpoint: unlike findActive it has no retention window at all.
        AllDownloadsResponse page = activeDownloadRepository.findAll(10, 1).block();

        assertEquals(1, page.downloads().size());
        assertEquals(DownloadStage.SUCCEEDED, page.downloads().getFirst().stage());
    }

    private void moveOneSongTo(UUID downloadId, String songName, DownloadPhase phase) {
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET phase = :phase "
                        + "WHERE download_id = :id AND song_name = :song")
                .bind("phase", phase.name()).bind("id", downloadId).bind("song", songName)
                .fetch().rowsUpdated().block();
    }

    private void setProgress(UUID downloadId, String songName, String percent) {
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET progress_percent = :percent::numeric "
                        + "WHERE download_id = :id AND song_name = :song")
                .bind("percent", percent).bind("id", downloadId).bind("song", songName)
                .fetch().rowsUpdated().block();
    }

    private static ActiveDownloadView viewOf(List<ActiveDownloadView> views, UUID id) {
        return views.stream().filter(v -> v.downloadId().equals(id)).findFirst().orElseThrow();
    }
}
