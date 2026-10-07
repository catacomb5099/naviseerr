package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.TestcontainersConfiguration;
import com.catacomb5099.naviseerr.support.DownloadTaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.catacomb5099.naviseerr.support.TaskFinishing.finish;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadTaskRepositoryIT {

    @Autowired R2dbcEntityTemplate template;
    @Autowired DownloadTaskRepository repository;
    @Autowired DownloadService downloadService;
    @Autowired PostgreSQLContainer<?> postgres;

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @BeforeEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM album_searches").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM media_items").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM song_albums").fetch().rowsUpdated().block();
    }

    private UUID insertDownload(String status) {
        return insertDownload(status, "SONG");
    }

    private UUID insertDownload(String status, String type) {
        return insertDownload(status, type, null);
    }

    /** @param youtubeId the requested id, e.g. an album's; null for a made-up one */
    private UUID insertDownload(String status, String type, String youtubeId) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                        + "VALUES (:id, :ytId, :type, :status, now())")
                .bind("id", id).bind("ytId", youtubeId == null ? "yt-" + id : youtubeId).bind("type", type)
                .bind("status", status)
                .fetch().rowsUpdated().block();
        return id;
    }

    /**
     * The other half of admission. The runner's metadata call sits between {@code admitDownloads}
     * and this, so every test that needs an admitted download does what the runner does.
     */
    private Long admit(UUID downloadId, String... songNames) {
        List<DownloadTask> tasks = Arrays.stream(songNames)
                .map(name -> DownloadTask.initial(downloadId, "yt-" + name, name, NOW))
                .toList();
        return repository.createTasks(downloadId, tasks, NOW).block();
    }

    private UUID admitOneSong(String status) {
        UUID id = insertDownload(status);
        admit(id, "song");
        return id;
    }

    // ---- Soulseek offline: pauseDueWork (07-10-2026) ---------------------------------------------

    /** Each row's [phase_entered_at, next_attempt_at] for one download, in one table. */
    private List<Instant[]> clocksOf(String table, UUID downloadId) {
        return template.getDatabaseClient()
                .sql("SELECT phase_entered_at, next_attempt_at FROM " + table + " WHERE download_id = :id ORDER BY next_attempt_at")
                .bind("id", downloadId)
                .map(row -> new Instant[] { row.get("phase_entered_at", Instant.class), row.get("next_attempt_at", Instant.class) })
                .all().collectList().block();
    }

    @Test
    void pauseDueWork_movesEveryUnfinishedSongsAndAlbumSearchsClocksForward_andLeavesFinishedRowsAlone() {
        UUID due = admitOneSong("PENDING");                     // SEARCH_INIT, due at NOW
        UUID finished = admitOneSong("PENDING");
        template.getDatabaseClient().sql("UPDATE download_tasks SET phase = 'SUCCEEDED', finished_at = :now WHERE download_id = :id")
                .bind("now", NOW).bind("id", finished).fetch().rowsUpdated().block();
        UUID album = insertDownload("PENDING", "ALBUM", "MPREb_pause");
        repository.createTasks(album, List.of(DownloadTask.initial(album, "yt-a1", "a1", NOW),
                DownloadTask.initial(album, "yt-a2", "a2", NOW)), NOW, NOW.plusSeconds(240)).block();   // songs held, album search due

        Long paused = repository.pauseDueWork(NOW.plusSeconds(10), Duration.ofSeconds(2)).block();

        assertEquals(4L, paused, "the due song, the two held album songs and the album search");
        // Overdue (due at NOW, it is NOW+10): due again one pause from now; the budget clock moved by the pause.
        assertArrayEquals(new Instant[] { NOW.plusSeconds(2), NOW.plusSeconds(12) }, clocksOf("download_tasks", due).getFirst());
        assertArrayEquals(new Instant[] { NOW.plusSeconds(2), NOW.plusSeconds(12) }, clocksOf("album_searches", album).getFirst());
        // Held until NOW+240: the hold keeps its distance.
        for (Instant[] clocks : clocksOf("download_tasks", album)) {
            assertArrayEquals(new Instant[] { NOW.plusSeconds(2), NOW.plusSeconds(242) }, clocks);
        }
        // History is left alone.
        assertArrayEquals(new Instant[] { NOW, NOW }, clocksOf("download_tasks", finished).getFirst());
    }

    @Test
    void admitDownloads_returnsPendingRequestsWithTheirTypeAndId() {
        UUID id = insertDownload("PENDING", "ALBUM");

        List<Download> admissible = repository.admitDownloads(10).collectList().block();

        assertEquals(1, admissible.size());
        assertEquals(id, admissible.getFirst().getDownloadId());
        // Both are what the runner needs to pick an endpoint and call it. A download whose type did
        // not survive the read would be sent to the wrong ytmusic-adapter route.
        assertEquals(DownloadType.ALBUM, admissible.getFirst().getDownloadType());
        assertEquals("yt-" + id, admissible.getFirst().getYoutubeId());
    }

    @Test
    void admitDownloads_writesNothing() {
        UUID id = insertDownload("PENDING");

        repository.admitDownloads(10).collectList().block();

        // The metadata call happens after this and can fail, so leaving the row untouched is what
        // makes the next pass able to retry it.
        assertEquals("PENDING", statusOf(id));
        assertEquals(0L, countTaskRows());
    }

    @Test
    void admitDownloads_ignoresDownloadsThatAlreadyHaveTasks() {
        admitOneSong("PENDING");

        assertTrue(repository.admitDownloads(10).collectList().block().isEmpty());
    }

    @Test
    void admitDownloads_ignoresInProgressAndTerminalDownloads() {
        // IN_PROGRESS is deliberately NOT covered any more. The old statement had to cover it to be
        // crash-safe across its own two halves; now the task inserts and the status flip are one
        // statement, so an IN_PROGRESS download always has task rows and nothing needs recovering.
        insertDownload("IN_PROGRESS");
        insertDownload("SUCCEEDED");
        insertDownload("FAILED");
        insertDownload("PARTIAL_SUCCESS");

        assertTrue(repository.admitDownloads(10).collectList().block().isEmpty());
    }

    @Test
    void createTasks_createsOneRowPerSongAndAdmitsTheDownloadOnce() {
        UUID id = insertDownload("PENDING", "ALBUM");

        Long admitted = admit(id, "track one", "track two", "track three");

        assertEquals(3L, admitted, "one row per song was created");
        assertEquals(3L, countTaskRows());
        assertEquals("IN_PROGRESS", statusOf(id));
        assertEquals(NOW, admittedAtOf(id), "admission is the first lifecycle timestamp after the request");
        assertEquals(List.of("SEARCH_INIT", "SEARCH_INIT", "SEARCH_INIT"), phasesOf(id));
    }

    @Test
    void createTasks_afterTheDownloadWasFailed_insertsNothing() {
        UUID id = insertDownload("PENDING", "ALBUM");
        // Admission selected the row, then the request was failed (or cancelled) before the song rows were written.
        repository.failUnadmitted(id, DownloadFailureCode.METADATA_UNAVAILABLE, NOW).block();

        Long created = admit(id, "a", "b", "c");

        assertEquals(0L, created, "a download that is no longer pending must not get songs");
        assertEquals(0L, countTaskRows());
        assertEquals("FAILED", statusOf(id));
    }

    @Test
    void createTasks_recordsEachSongsPositionInTheOrderGiven() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "zeta", "alpha", "mid");

        // Track order, not alphabetical: the per-song view must list an album the way the album is.
        List<String> byPosition = template.getDatabaseClient()
                .sql("SELECT song_name FROM download_tasks WHERE download_id = :id ORDER BY position")
                .bind("id", id)
                .map((row, meta) -> row.get("song_name", String.class)).all().collectList().block();
        assertEquals(List.of("zeta", "alpha", "mid"), byPosition);
    }

    @Test
    void createTasks_storesEachRowsOwnTitleNumberAndLength_evenWhenTwoRowsShareAnId() {
        UUID id = insertDownload("PENDING", "ALBUM");
        // Tracks 21 and 24 of the live 30th Anniversary page share one video id; a playlist row has
        // no album number.
        repository.createTasks(id, List.of(
                track(id, "h7-BHdjeEY0", "Up In The Sky (Sawmills Outtake)", 21, 273),
                track(id, "h7-BHdjeEY0", "Cigarettes & Alcohol (Sawmills Outtake)", 24, 307),
                track(id, "v-pl", "Wonderwall", null, null)), NOW).block();

        assertEquals(List.of(
                        List.of("Up In The Sky (Sawmills Outtake)", "21", "273", "1"),
                        List.of("Cigarettes & Alcohol (Sawmills Outtake)", "24", "307", "2"),
                        Arrays.asList("Wonderwall", null, null, "3")),
                trackColumnsOf(id), "position stays list order beside YouTube's own number");
        // The claim hands them back, so the task in memory is the whole row.
        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "x", NOW, Duration.ofSeconds(60), true, 10).collectList().block();
        DownloadTask outtake = claimed.stream()
                .filter(t -> "Cigarettes & Alcohol (Sawmills Outtake)".equals(t.trackTitle())).findFirst().orElseThrow();
        assertEquals(24, outtake.trackNumber());
        assertEquals(307, outtake.durationSeconds());
    }

    @Test
    void aTaskRowWrittenWithoutTheV12Columns_keepsThemNull_andIsStillClaimed() {
        UUID id = insertDownload("IN_PROGRESS", "ALBUM");
        // What every row from before V12 looks like, and what an older instance still writes.
        template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (task_id, download_id, youtube_id, song_name, position, "
                        + "phase, phase_entered_at, next_attempt_at) "
                        + "VALUES (gen_random_uuid(), :id, 'v-old', 'Old - Band', 1, 'SEARCH_INIT', :now, :now)")
                .bind("id", id).bind("now", NOW).fetch().rowsUpdated().block();

        assertEquals(List.of(Arrays.asList(null, null, null, "1")), trackColumnsOf(id));
        DownloadTask claimed = repository
                .claimDueTasks(10, "x", NOW, Duration.ofSeconds(60), true, 10).blockFirst();
        assertEquals("Old - Band", claimed.songName());
        assertNull(claimed.trackTitle());
        assertNull(claimed.trackNumber());
        assertNull(claimed.durationSeconds());
    }

    private static DownloadTask track(UUID downloadId, String youtubeId, String title, Integer number,
                                      Integer seconds) {
        return DownloadTask.initial(downloadId, youtubeId, title + " - Oasis", NOW).toBuilder()
                .trackTitle(title).trackNumber(number).durationSeconds(seconds).build();
    }

    /** title, number, length and position of every row of one download, in position order, as text. */
    private List<List<String>> trackColumnsOf(UUID downloadId) {
        return template.getDatabaseClient()
                .sql("SELECT track_title, track_number::text AS n, duration_seconds::text AS d, position::text AS p "
                        + "FROM download_tasks WHERE download_id = :id ORDER BY position")
                .bind("id", downloadId)
                .map((row, meta) -> Arrays.asList(row.get("track_title", String.class), row.get("n", String.class),
                        row.get("d", String.class), row.get("p", String.class)))
                .all().collectList().block();
    }

    // ---- media_items ---------------------------------------------------------------------------

    @Test
    void upsertMedia_roundTripsEveryFieldIncludingTheArtistArray() {
        repository.upsertMedia(List.of(new MediaItem("v1", "Wonderwall", List.of("Oasis", "Noel"),
                List.of("UC-oasis", ""), "https://img/1.jpg", 259, null))).block();

        assertEquals("Wonderwall", mediaField("v1", "title"));
        assertEquals("https://img/1.jpg", mediaField("v1", "image_url"));
        assertEquals(List.of("Oasis", "Noel"), mediaArtists("v1"));
        // Stored as given, '' included: the array has to stay the same length as the names.
        assertEquals(List.of("UC-oasis", ""), mediaArtistIds("v1"));
    }

    @Test
    void upsertMedia_refreshesARow_butNeverReplacesAValueWithNull() {
        repository.upsertMedia(List.of(new MediaItem("v1", "Wonderwall", List.of("Oasis"),
                List.of("UC-oasis"), "https://img/1.jpg", 259, null))).block();

        // A playlist listing the same track knows its title but not its artwork or length.
        repository.upsertMedia(List.of(new MediaItem("v1", "Wonderwall (Remastered)", List.of(),
                List.of(), null, null, null))).block();

        assertEquals("Wonderwall (Remastered)", mediaField("v1", "title"));
        assertEquals("https://img/1.jpg", mediaField("v1", "image_url"),
                "a less complete answer must not blank a picture we already had");
        assertEquals(List.of("Oasis"), mediaArtists("v1"));
        assertEquals(List.of("UC-oasis"), mediaArtistIds("v1"), "no names, so the ids stay with the old ones");
    }

    @Test
    void upsertMedia_replacesTheIdsTogetherWithTheNames() {
        repository.upsertMedia(List.of(new MediaItem("v1", "Wonderwall", List.of("Oasis"),
                List.of("UC-oasis"), null, null, null))).block();

        // A fuller answer names two artists: its ids come along, even where one of them is unknown.
        repository.upsertMedia(List.of(new MediaItem("v1", "Wonderwall", List.of("Oasis", "Noel"),
                List.of("UC-oasis", ""), null, null, null))).block();
        assertEquals(List.of("UC-oasis", ""), mediaArtistIds("v1"));

        // An answer that names artists but knows no ids replaces them with nothing, rather than
        // leaving ids that belonged to a different set of names lined up against the new ones.
        repository.upsertMedia(List.of(new MediaItem("v1", "Wonderwall", List.of("Noel Gallagher"),
                List.of(), null, null, null))).block();
        assertEquals(List.of("Noel Gallagher"), mediaArtists("v1"));
        assertEquals(List.of(), mediaArtistIds("v1"));
    }

    @Test
    void upsertMedia_toleratesTheSameIdTwiceInOneBatch() {
        // A playlist can list one track twice; ON CONFLICT DO UPDATE refuses to touch a row twice
        // in one statement, so the repository has to fold them before binding.
        assertDoesNotThrow(() -> repository.upsertMedia(List.of(
                new MediaItem("v1", "Once", List.of(), List.of(), null, null, null),
                new MediaItem("v1", "Twice", List.of(), List.of(), null, null, null))).block());

        assertEquals("Once", mediaField("v1", "title"));
    }

    @Test
    void upsertMedia_fillsAnAlbumsYearAndTypeOnAnOlderRow_andALessCompleteAnswerKeepsThem() {
        // The album row an earlier download wrote before V12: no year, no type.
        repository.upsertMedia(List.of(new MediaItem("MPREb_Hl8XJR59OrY", "Definitely Maybe", List.of("Oasis"),
                List.of("UC-oasis"), null, null, 10))).block();
        repository.upsertMedia(List.of(new MediaItem("MPREb_Hl8XJR59OrY", "Definitely Maybe", List.of("Oasis"),
                List.of("UC-oasis"), null, null, 11, 1994, "Album"))).block();
        // A row written without them (a song request for the same id, or an older instance).
        repository.upsertMedia(List.of(new MediaItem("MPREb_Hl8XJR59OrY", "Definitely Maybe", List.of("Oasis"),
                List.of("UC-oasis"), null, null, null))).block();

        assertEquals(List.of(1994, 11), template.getDatabaseClient()
                .sql("SELECT year, track_count FROM media_items WHERE youtube_id = 'MPREb_Hl8XJR59OrY'")
                .map((row, meta) -> List.of(row.get("year", Integer.class), row.get("track_count", Integer.class)))
                .one().block());
        assertEquals("Album", mediaField("MPREb_Hl8XJR59OrY", "album_type"));
    }

    @Test
    void upsertMedia_withNothingToWrite_doesNotQuery() {
        assertEquals(0L, repository.upsertMedia(List.of()).block());
    }

    @Test
    void createTasks_givesEverySongItsOwnTaskIdAndYoutubeId() {
        UUID id = insertDownload("PENDING", "PLAYLIST");
        admit(id, "a", "b");

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "x", NOW, Duration.ofSeconds(60), true, 10).collectList().block();

        assertEquals(2, claimed.size());
        assertEquals(2, claimed.stream().map(DownloadTask::taskId).distinct().count(),
                "two songs of one download must be two distinct task rows");
        assertEquals(List.of("yt-a", "yt-b"),
                claimed.stream().map(DownloadTask::youtubeId).sorted().toList());
        assertEquals(1, claimed.stream().map(DownloadTask::downloadId).distinct().count(),
                "and they must both still belong to the one download");
    }

    @Test
    void createTasks_runTwice_isANoOpRatherThanDuplicatingEverySong() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "a", "b");

        // Two overlapping passes, or a retried metadata call, must not double a collection.
        assertEquals(0L, admit(id, "a", "b"));
        assertEquals(2L, countTaskRows());
    }

    @Test
    void claim_returnsOnlyDueRowsAndStampsALease() {
        UUID id = admitOneSong("PENDING");

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "instance-a", NOW, Duration.ofSeconds(60), true, 10)
                .collectList().block();

        assertEquals(1, claimed.size());
        assertEquals(id, claimed.getFirst().downloadId());
        assertEquals("song", claimed.getFirst().songName());
        assertEquals("instance-a", leaseOwnerOf(id));
    }

    @Test
    void claim_skipsRowsThatAreNotYetDue() {
        admitOneSong("PENDING");

        assertTrue(repository.claimDueTasks(10, "a", NOW.minusSeconds(1), Duration.ofSeconds(60), true, 10)
                .collectList().block().isEmpty());
    }

    @Test
    void claim_skipsRowsHeldByALiveLease() {
        admitOneSong("PENDING");
        repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true, 10).collectList().block();

        assertTrue(repository.claimDueTasks(10, "b", NOW.plusSeconds(1), Duration.ofSeconds(60), true, 10)
                .collectList().block().isEmpty());
    }

    @Test
    void claim_reclaimsRowsWhoseLeaseHasExpired_thisIsCrashRecovery() {
        admitOneSong("PENDING");
        repository.claimDueTasks(10, "dead", NOW, Duration.ofSeconds(60), true, 10).collectList().block();

        List<DownloadTask> reclaimed = repository
                .claimDueTasks(10, "alive", NOW.plusSeconds(61), Duration.ofSeconds(60), true, 10)
                .collectList().block();

        assertEquals(1, reclaimed.size());
    }

    @Test
    void save_roundTripsEveryFieldIncludingCandidates_andClearsTheLease() {
        UUID id = admitOneSong("PENDING");
        DownloadTask claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true, 10).blockFirst();

        DownloadTask updated = claimed.toBuilder()
                .phase(DownloadPhase.DOWNLOAD_POLL)
                .nextAttemptAt(NOW.plusSeconds(5))
                .searchId("s1")
                .searchTier(1)
                .candidates(DownloadTaskFixtures.candidates("alice", "bob"))
                .candidateIndex(1).retryIndex(2)
                .slskdUsername("bob").slskdFilename("music/bob/song.flac").slskdTransferId("abc")
                .lastError("some error")
                .build();
        repository.save(updated, "a").block();

        DownloadTask reread = repository
                .claimDueTasks(10, "b", NOW.plusSeconds(10), Duration.ofSeconds(60), true, 10).blockFirst();

        assertNotNull(reread, "lease must have been cleared by save()");
        assertEquals(claimed.taskId(), reread.taskId(), "save() keys on task_id, not download_id");
        assertEquals(DownloadPhase.DOWNLOAD_POLL, reread.phase());
        assertEquals("s1", reread.searchId());
        assertEquals(1, reread.searchTier(), "a restart mid-retry must resume on the same tier");
        assertEquals(2, reread.candidates().size());
        assertEquals("music/bob/song.flac", reread.candidates().get(1).filename());
        assertEquals(1411, reread.candidates().get(1).bitRate());
        assertEquals(1, reread.candidateIndex());
        assertEquals(2, reread.retryIndex());
        assertEquals("abc", reread.slskdTransferId());
    }

    @Test
    void save_writesOnlyTheOneSongItWasGiven() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "a", "b");
        DownloadTask one = repository
                .claimDueTasks(10, "x", NOW, Duration.ofSeconds(60), true, 10).blockFirst();

        repository.save(one.withPhase(DownloadPhase.SEARCH_POLL, NOW), "x").block();

        // Keying on download_id -- which is what SAVE_SQL did before this change -- would step both
        // of an album's songs on one song's slskd response.
        assertEquals(List.of("SEARCH_INIT", "SEARCH_POLL"), phasesOf(id).stream().sorted().toList());
    }

    @Test
    void finishTask_marksTheTaskTerminalButLeavesTheDownloadAlone() {
        UUID id = admitOneSong("PENDING");

        finish(template, downloadService, taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW);

        assertEquals("SUCCEEDED", phaseOf(id));
        // The download's status is settled by concludeDownloads(), not here -- see CONCLUDE_SQL.
        assertEquals("IN_PROGRESS", statusOf(id));
        assertEquals(1L, countTaskRows(), "the task row is history now, not garbage");
    }

    @Test
    void finishTask_recordsTheFailureReasonForLaterDebugging() {
        UUID id = admitOneSong("PENDING");

        finish(template, downloadService, taskIdOf(id), DownloadStatus.FAILED,
                DownloadFailureCode.TIMED_OUT, NOW);

        assertEquals("FAILED", phaseOf(id));
        // The NAME, not prose: the client owns the wording, so copy edits never touch this column.
        assertEquals("TIMED_OUT", failureReasonOf(id));
    }

    @Test
    void finishTask_onAnAlreadyTerminalTask_changesNothingAtAll() {
        UUID id = admitOneSong("PENDING");
        UUID taskId = taskIdOf(id);
        finish(template, downloadService, taskId, DownloadStatus.SUCCEEDED, null, NOW);
        Instant firstFinishedAt = finishedAtOf(id);

        // A duplicated step reaching Terminal a second time — legal, because a lease can expire
        // while the work is still alive.
        Long rows = finish(template, downloadService, taskId, DownloadStatus.FAILED,
                DownloadFailureCode.SOURCES_EXHAUSTED, NOW.plusSeconds(3600));

        assertEquals(0L, rows, "a duplicate finish must be a no-op, not a second write");
        assertEquals("SUCCEEDED", phaseOf(id), "must not overwrite a terminal phase");
        assertNull(failureReasonOf(id), "a successful song must not acquire a failure reason");
        // The one that actually bites: re-stamping finished_at would slide this row back inside the
        // feed's retention window and resurrect a card the user dismissed hours ago.
        assertEquals(firstFinishedAt, finishedAtOf(id), "finished_at must not move");
    }

    @Test
    void finishTask_byAnotherOwner_isANoOp() {
        UUID id = admitOneSong("PENDING");
        DownloadTask claimed = repository.claimDueTasks(10, "owner-a", NOW, Duration.ofMinutes(1), true, 2)
                .blockFirst();

        Long rows = downloadService.finishTask(claimed.taskId(), DownloadStatus.SUCCEEDED, null, NOW, "owner-b").block();

        assertEquals(0L, rows, "a finish from a process that does not hold the lease must not land");
        assertEquals("SEARCH_INIT", phaseOf(id));
    }

    @Test
    void finishTask_onARowWithNoLease_isANoOp() {
        UUID id = admitOneSong("PENDING");
        UUID taskId = taskIdsOf(id).getFirst();

        Long rows = downloadService.finishTask(taskId, DownloadStatus.FAILED, DownloadFailureCode.TIMED_OUT, NOW, "anyone").block();

        assertEquals(0L, rows, "no lease means nobody is entitled to finish the row");
    }

    // ---- library organiser ---------------------------------------------------------------------

    /** A succeeded song with a peer file name, finished at {@code finishedAt}. */
    private UUID succeededSong(UUID downloadId, String youtubeId, String filename, Instant finishedAt) {
        UUID taskId = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (task_id, download_id, youtube_id, song_name, phase, "
                        + "phase_entered_at, next_attempt_at, finished_at, slskd_username, slskd_filename) "
                        + "VALUES (:task, :dl, :yt, 'q', 'SUCCEEDED', :at, :at, :at, 'bob', :file)")
                .bind("task", taskId).bind("dl", downloadId).bind("yt", youtubeId).bind("at", finishedAt)
                .bind("file", filename)
                .fetch().rowsUpdated().block();
        return taskId;
    }

    private void media(String youtubeId, String title, String... artists) {
        repository.upsertMedia(List.of(new MediaItem(youtubeId, title, List.of(artists), List.of(), null, null, null))).block();
    }

    @Test
    void tasksToOrganise_returnsRecentSucceededSongsWithTheirOwnAndTheirCollectionsNames() {
        UUID album = insertDownload("SUCCEEDED", "ALBUM");
        media("yt-" + album, "Doolittle", "Pixies");
        media("song-1", "Debaser", "Pixies", "Someone");
        // The same song also requested on its own once, and looked up: the album track must ignore that.
        media("MPREb_doolittle_deluxe", "Doolittle (Deluxe)", "Pixies");
        songAlbum("song-1", "MPREb_doolittle_deluxe", 1, NOW);
        UUID taskId = succeededSong(album, "song-1", "music\\Pixies\\Doolittle\\01 - Debaser.flac", NOW);

        List<LibraryOrganiser.Job> jobs = repository.tasksToOrganise(10, NOW.minusSeconds(600), NOW.minusSeconds(120))
                .collectList().block();

        assertEquals(1, jobs.size());
        LibraryOrganiser.Job job = jobs.getFirst();
        assertEquals(taskId, job.taskId());
        assertEquals(DownloadType.ALBUM, job.type());
        assertEquals("music\\Pixies\\Doolittle\\01 - Debaser.flac", job.slskdFilename());
        assertEquals(NOW, job.finishedAt());
        assertEquals("Debaser", job.songTitle());
        assertEquals(List.of("Pixies", "Someone"), job.songArtists());
        assertEquals("Doolittle", job.collectionTitle());
        assertEquals(List.of("Pixies"), job.collectionArtists());
        assertNull(job.albumTitle(), "an album download's track never waits for, or uses, a song album");
    }

    private void songAlbum(String youtubeId, String albumId, Integer trackNumber, Instant resolvedAt) {
        repository.saveSongAlbum(youtubeId, albumId, trackNumber, resolvedAt).block();
    }

    @Test
    void tasksToOrganise_aSongWaitsForItsAlbumLookup_withinTheGrace_withoutFillingTheBatch() {
        UUID playlist = insertDownload("IN_PROGRESS", "PLAYLIST");
        for (int i = 0; i < 3; i++) {
            succeededSong(playlist, "waiting-" + i, "x\\w" + i + ".flac", NOW.minusSeconds(30 + i));
        }
        UUID album = insertDownload("IN_PROGRESS", "ALBUM");
        UUID albumTrack = succeededSong(album, "track", "x\\t.flac", NOW);
        UUID lateSong = succeededSong(playlist, "late", "x\\late.flac", NOW.minusSeconds(121));

        // LIMIT 2 with three older songs waiting: the wait is in the WHERE, so the ready ones still come.
        List<UUID> ready = repository.tasksToOrganise(2, NOW.minusSeconds(600), NOW.minusSeconds(120))
                .map(LibraryOrganiser.Job::taskId).collectList().block();

        assertEquals(List.of(lateSong, albumTrack), ready,
                "the album track never waits; the song past its two minutes is filed by its own name");
    }

    @Test
    void tasksToOrganise_aSongWithAnAlbumAnswer_isReadyAtOnce_andCarriesItsAlbumsNames() {
        UUID playlist = insertDownload("IN_PROGRESS", "PLAYLIST");
        repository.upsertMedia(List.of(new MediaItem("MPREb_dm", "Definitely Maybe", List.of("Oasis"), List.of(),
                null, null, 11, 1994, "Album"))).block();
        songAlbum("supersonic", "MPREb_dm", 6, NOW);
        songAlbum("lose-yourself", null, null, NOW);
        UUID joined = succeededSong(playlist, "supersonic", "x\\s.flac", NOW);
        UUID none = succeededSong(playlist, "lose-yourself", "x\\l.flac", NOW);

        List<LibraryOrganiser.Job> jobs = repository.tasksToOrganise(10, NOW.minusSeconds(600), NOW.minusSeconds(120))
                .collectList().block();

        assertEquals(java.util.Set.of(joined, none),
                jobs.stream().map(LibraryOrganiser.Job::taskId).collect(java.util.stream.Collectors.toSet()));
        LibraryOrganiser.Job supersonic = jobs.stream().filter(j -> j.taskId().equals(joined)).findFirst().orElseThrow();
        assertEquals("Definitely Maybe", supersonic.albumTitle());
        assertEquals(List.of("Oasis"), supersonic.albumArtists());
        LibraryOrganiser.Job loseYourself = jobs.stream().filter(j -> j.taskId().equals(none)).findFirst().orElseThrow();
        assertNull(loseYourself.albumTitle(), "looked, nothing trusted: filed by its own name, without waiting");
    }

    @Test
    void tasksToOrganise_carriesWhatTheTagsAreWrittenFrom_theAlbumDownloadsOwnAlbum_orTheSongsTrustedOne() {
        // An album download's track: its own row's title and number (the 30th Anniversary edition lists
        // h7-BHdjeEY0 as tracks 21 and 24), the album's artists, year, count and art; never a song album.
        UUID album = insertDownload("SUCCEEDED", "ALBUM");
        repository.upsertMedia(List.of(
                new MediaItem("yt-" + album, "Definitely Maybe (30th Anniversary Deluxe Edition)", List.of("Oasis"),
                        List.of(), "https://yt3.googleusercontent.com/dm30=w544-h544-l90-rj", null, 44, 2024, "Album"),
                new MediaItem("MPREb_dm", "Definitely Maybe", List.of("Oasis"), List.of(),
                        "https://yt3.googleusercontent.com/dm=w544-h544-l90-rj", null, 11, 1994, "Album"),
                new MediaItem("h7-BHdjeEY0", "Up In The Sky", List.of("Oasis"), List.of(),
                        "https://i.ytimg.com/vi/h7-BHdjeEY0/hqdefault.jpg", 268, null),
                new MediaItem("supersonic", "Supersonic", List.of("Oasis"), List.of(), null, 283, null),
                new MediaItem("lose-yourself", "Lose Yourself", List.of("Eminem"), List.of(),
                        "https://yt3.googleusercontent.com/ly=w544-h544-l90-rj", 326, null))).block();
        songAlbum("h7-BHdjeEY0", "MPREb_dm", 4, NOW);
        UUID albumTrack = succeededSong(album, "h7-BHdjeEY0", "x\\24.flac", NOW);
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET track_title = 'Up In The Sky (Sawmills Outtake)', track_number = 24 "
                        + "WHERE task_id = :task")
                .bind("task", albumTrack).fetch().rowsUpdated().block();
        // A playlist's track with a trusted album, and one without.
        UUID playlist = insertDownload("SUCCEEDED", "PLAYLIST");
        songAlbum("supersonic", "MPREb_dm", 6, NOW);
        songAlbum("lose-yourself", null, null, NOW);
        UUID joined = succeededSong(playlist, "supersonic", "x\\s.flac", NOW);
        UUID none = succeededSong(playlist, "lose-yourself", "x\\l.flac", NOW);

        java.util.Map<UUID, SongTagger.Tags> tags = repository.tasksToOrganise(10, NOW.minusSeconds(600), NOW.minusSeconds(120))
                .collectMap(LibraryOrganiser.Job::taskId, LibraryOrganiser.Job::tags).block();

        assertEquals(new SongTagger.Tags("Up In The Sky (Sawmills Outtake)", List.of("Oasis"),
                "Definitely Maybe (30th Anniversary Deluxe Edition)", List.of("Oasis"), 2024, 24, 44,
                "https://yt3.googleusercontent.com/dm30=w544-h544-l90-rj"), tags.get(albumTrack));
        assertEquals(new SongTagger.Tags("Supersonic", List.of("Oasis"), "Definitely Maybe", List.of("Oasis"),
                1994, 6, 11, "https://yt3.googleusercontent.com/dm=w544-h544-l90-rj"), tags.get(joined));
        assertEquals(new SongTagger.Tags("Lose Yourself", List.of("Eminem"), null, List.of(), null, null, null,
                "https://yt3.googleusercontent.com/ly=w544-h544-l90-rj"), tags.get(none),
                "no trusted album: no album tags, and the song's own picture only fills a gap");
    }

    // ---- album lookup ----------------------------------------------------------------------------

    private UUID task(UUID downloadId, String youtubeId, String phase, Instant finishedAt) {
        UUID taskId = UUID.randomUUID();
        var insert = template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (task_id, download_id, youtube_id, song_name, duration_seconds, "
                        + "phase, phase_entered_at, next_attempt_at, finished_at) "
                        + "VALUES (:task, :dl, :yt, :name, 200, :phase, :now, :now, :finished)")
                .bind("task", taskId).bind("dl", downloadId).bind("yt", youtubeId).bind("name", youtubeId + " - Artist")
                .bind("phase", phase).bind("now", NOW);
        (finishedAt == null ? insert.bindNull("finished", Instant.class) : insert.bind("finished", finishedAt))
                .fetch().rowsUpdated().block();
        return taskId;
    }

    private List<String> toResolve(int limit) {
        return repository.songsToResolve(limit, NOW.minusSeconds(600), NOW.minus(Duration.ofDays(7)))
                .map(SongAlbumResolver.Song::youtubeId).collectList().block();
    }

    @Test
    void songsToResolve_isSongsAndPlaylistTracksStillDownloadingOrJustFinished_neverAlbumTracksOrHistory() {
        UUID song = insertDownload("IN_PROGRESS", "SONG");
        UUID playlist = insertDownload("IN_PROGRESS", "PLAYLIST");
        UUID curated = insertDownload("IN_PROGRESS", "CURATED");
        UUID album = insertDownload("IN_PROGRESS", "ALBUM");
        task(song, "searching", "SEARCH_INIT", null);
        task(playlist, "just-finished", "SUCCEEDED", NOW);
        task(curated, "downloading", "DOWNLOAD_POLL", null);
        task(album, "album-track", "SEARCH_INIT", null);
        task(playlist, "history", "SUCCEEDED", NOW.minusSeconds(601));
        UUID filed = task(playlist, "filed", "SUCCEEDED", NOW);
        repository.setLibraryPath(filed, "/music/a.flac").block();
        task(playlist, "failed", "FAILED", NOW);

        assertEquals(java.util.Set.of("searching", "just-finished", "downloading"), java.util.Set.copyOf(toResolve(10)));
        SongAlbumResolver.Song curatedSong = repository.songsToResolve(10, NOW.minusSeconds(600), NOW)
                .filter(s -> s.youtubeId().equals("downloading")).blockFirst();
        assertEquals(DownloadType.CURATED, curatedSong.type());
        assertEquals("yt-" + curated, curatedSong.downloadYoutubeId(), "the curator category, for its album hint");
        assertEquals("downloading - Artist", curatedSong.songName());
        assertEquals(200, curatedSong.durationSeconds());
    }

    @Test
    void songsToResolve_asksOncePerId_oldestRequestFirst_andAgainOnlyForAWeekOldNone() {
        UUID older = insertDownload("IN_PROGRESS", "PLAYLIST");
        UUID newer = insertDownload("IN_PROGRESS", "SONG");
        template.getDatabaseClient().sql("UPDATE downloads SET created_at = :at WHERE download_id = :id")
                .bind("at", NOW.minusSeconds(60)).bind("id", older).fetch().rowsUpdated().block();
        task(newer, "twice", "SEARCH_INIT", null);
        task(older, "twice", "SEARCH_INIT", null);
        task(older, "trusted", "SEARCH_INIT", null);
        task(older, "fresh-none", "SEARCH_INIT", null);
        task(older, "old-none", "SEARCH_INIT", null);
        songAlbum("trusted", "MPREb_x", 1, NOW);
        songAlbum("fresh-none", null, null, NOW.minus(Duration.ofDays(6)));
        songAlbum("old-none", null, null, NOW.minus(Duration.ofDays(8)));

        List<String> due = toResolve(10);

        assertEquals(2, due.size(), "one row per id; answered ids are not asked again");
        assertEquals(java.util.Set.of("twice", "old-none"), java.util.Set.copyOf(due));
        assertEquals(1, toResolve(1).size());

        songAlbum("old-none", "MPREb_y", 3, NOW);
        assertEquals(List.of("twice"), toResolve(10), "the upsert replaced the old answer");
    }

    @Test
    void songsToResolve_ordersByRequestTime() {
        UUID first = insertDownload("IN_PROGRESS", "SONG");
        UUID second = insertDownload("IN_PROGRESS", "SONG");
        template.getDatabaseClient().sql("UPDATE downloads SET created_at = :at WHERE download_id = :id")
                .bind("at", NOW.minusSeconds(60)).bind("id", first).fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("UPDATE downloads SET created_at = :at WHERE download_id = :id")
                .bind("at", NOW.minusSeconds(30)).bind("id", second).fetch().rowsUpdated().block();
        task(second, "b", "SEARCH_INIT", null);
        task(first, "a", "SEARCH_INIT", null);

        assertEquals(List.of("a", "b"), toResolve(10));
    }

    @Test
    void tasksToOrganise_skipsFailedSongs_alreadyFiledSongs_andSongsOlderThanTheCutoff() {
        UUID dl = insertDownload("PARTIAL_SUCCESS", "PLAYLIST");
        succeededSong(dl, "old", "a\\old.flac", NOW.minusSeconds(601));
        UUID filed = succeededSong(dl, "filed", "a\\filed.flac", NOW);
        repository.setLibraryPath(filed, "/music/x/filed.flac").block();
        UUID failedId = admitOneSong("PENDING");
        finish(template, downloadService, taskIdOf(failedId), DownloadStatus.FAILED,
                DownloadFailureCode.NO_CANDIDATES, NOW);
        UUID wanted = succeededSong(dl, "new", "a\\new.flac", NOW);

        // Album lookup grace over (album cutoff NOW): no song waits for one here.
        List<LibraryOrganiser.Job> jobs = repository.tasksToOrganise(10, NOW.minusSeconds(600), NOW)
                .collectList().block();

        assertEquals(List.of(wanted), jobs.stream().map(LibraryOrganiser.Job::taskId).toList());
        // Names are LEFT JOINed: a song with no media row still comes back, with empty names, rather
        // than being silently never filed.
        assertNull(jobs.getFirst().songTitle());
        assertEquals(List.of(), jobs.getFirst().songArtists());
    }

    // ---- songs the library already has -----------------------------------------------------------

    /** A song an earlier download filed: its row's own title and number, its pick's grade, its file. */
    private void filed(UUID downloadId, String youtubeId, String title, Integer trackNumber, String grade, String path) {
        var insert = template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (task_id, download_id, youtube_id, song_name, track_title, "
                        + "track_number, candidates, candidate_index, phase, phase_entered_at, next_attempt_at, "
                        + "finished_at, library_path) VALUES (gen_random_uuid(), :dl, :yt, 'q', :title, :n, "
                        + ":candidates, 0, 'SUCCEEDED', :at, :at, :at, :path)")
                .bind("dl", downloadId).bind("yt", youtubeId).bind("title", title)
                .bind("candidates", "[{\"grade\":\"" + grade + "\"}]").bind("at", NOW.minusSeconds(3600));
        insert = trackNumber == null ? insert.bindNull("n", Integer.class) : insert.bind("n", trackNumber);
        insert = path == null ? insert.bindNull("path", String.class) : insert.bind("path", path);
        insert.fetch().rowsUpdated().block();
    }

    private static DownloadTask wanted(String youtubeId, String title, Integer trackNumber) {
        return DownloadTask.initial(UUID.randomUUID(), youtubeId, title, NOW).toBuilder()
                .trackTitle(title).trackNumber(trackNumber).build();
    }

    private List<String> copies(DownloadType type, String downloadYoutubeId, DownloadTask... tasks) {
        return repository.filedCopies(type, downloadYoutubeId, List.of(tasks))
                .map(c -> c.position() + " " + c.libraryPath()).collectList().block();
    }

    @Test
    void filedCopies_forAnAlbum_isTheSameAlbumTrack_neverTheSameIdFromElsewhere() {
        UUID album = insertDownload("SUCCEEDED", "ALBUM", "MPREb_dm");
        filed(album, "supersonic-atv", "Supersonic", 6, "EXACT", "/music/Oasis/DM/06.flac");
        // A Deluxe edition shares the plain album's ids; its files must not be taken for this album's.
        UUID deluxe = insertDownload("SUCCEEDED", "ALBUM", "MPREb_dm_deluxe");
        filed(deluxe, "live-forever", "Live Forever", 5, "EXACT", "/music/Oasis/DMD/05.flac");

        assertEquals(List.of("1 /music/Oasis/DM/06.flac"), copies(DownloadType.ALBUM, "MPREb_dm",
                wanted("supersonic-other-id", "Supersonic", 6), wanted("live-forever", "Live Forever", 5)));
    }

    @Test
    void filedCopies_forASongOrPlaylist_isTheSameIdWithTheSameTitle_orItsTrustedAlbumTrack() {
        UUID album = insertDownload("SUCCEEDED", "ALBUM", "MPREb_dm");
        filed(album, "supersonic-atv", "Supersonic", 6, "EXACT", "/music/Oasis/DM/06.flac");
        UUID song = insertDownload("SUCCEEDED", "SONG");
        filed(song, "wonderwall", "Wonderwall", null, "EXACT", "/music/Oasis/Wonderwall/w.flac");
        songAlbum("supersonic-omv", "MPREb_dm", 6, NOW);

        assertEquals(List.of("1 /music/Oasis/DM/06.flac", "2 /music/Oasis/Wonderwall/w.flac"),
                copies(DownloadType.PLAYLIST, "VL-britpop",
                        wanted("supersonic-omv", "Supersonic (Official Video)", null),
                        wanted("wonderwall", "wonderwall", null),
                        wanted("wonderwall", "Wonderwall (Live)", null)),
                "the video's twin by its album track; the same id only with the same title");
    }

    @Test
    void filedCopies_neverCountsAStandInPick_orASongThatWasNotFiled() {
        UUID song = insertDownload("SUCCEEDED", "SONG");
        filed(song, "a", "A", null, "TITLE_ONLY", "/music/a.flac");
        filed(song, "b", "B", null, "EXACT", null);

        assertEquals(List.of(), copies(DownloadType.SONG, "a", wanted("a", "A", null), wanted("b", "B", null)));
    }

    @Test
    void filedCopies_neverTakesASongForAnAlbumTrack_byAnAlbumAnswerThatCameAfterItWasFiled() {
        // Filed by its own name (no answer within the organiser's wait), then looked up late: the file
        // is in its own folder, so the album still needs its own copy of the track.
        UUID song = insertDownload("SUCCEEDED", "SONG");
        filed(song, "supersonic", "Supersonic", null, "EXACT", "/music/Oasis/Supersonic/s.flac");
        songAlbum("supersonic", "MPREb_dm", 6, NOW);

        assertEquals(List.of(), copies(DownloadType.ALBUM, "MPREb_dm", wanted("supersonic", "Supersonic", 6)));
        assertEquals(List.of("1 /music/Oasis/Supersonic/s.flac"), copies(DownloadType.SONG, "supersonic",
                wanted("supersonic", "Supersonic", null)), "asked for as a song, it is still the same song");
    }

    @Test
    void createTasks_createsASongTheLibraryHasFinished_soNothingSearchesForIt() {
        UUID id = insertDownload("PENDING", "ALBUM");
        List<DownloadTask> tasks = List.of(
                DownloadTask.initial(id, "have", "have", NOW).toBuilder().libraryPath("/music/x/have.flac").build(),
                DownloadTask.initial(id, "want", "want", NOW));

        assertEquals(2L, repository.createTasks(id, tasks, NOW).block());

        assertEquals(List.of("SUCCEEDED", "SEARCH_INIT"), phasesOf(id));
        UUID have = taskIdsOf(id).getFirst();
        assertEquals("/music/x/have.flac", taskField(have, "library_path"));
        assertEquals(NOW, template.getDatabaseClient()
                .sql("SELECT finished_at FROM download_tasks WHERE task_id = :id").bind("id", have)
                .map((row, meta) -> row.get("finished_at", Instant.class)).one().block());
        assertEquals(0, new java.math.BigDecimal(100).compareTo(template.getDatabaseClient()
                .sql("SELECT progress_percent FROM download_tasks WHERE task_id = :id").bind("id", have)
                .map((row, meta) -> row.get("progress_percent", java.math.BigDecimal.class)).one().block()));
        assertEquals(0L, repository.concludeDownloads().block(), "the download still has a song to fetch");
    }

    @Test
    void tasksToOrganise_givesASongItsAlbumTracksFileInTheLibrary() {
        UUID album = insertDownload("SUCCEEDED", "ALBUM", "MPREb_dm");
        filed(album, "supersonic-atv", "Supersonic", 6, "EXACT", "/music/Oasis/DM/06.flac");
        UUID playlist = insertDownload("SUCCEEDED", "PLAYLIST");
        songAlbum("supersonic-omv", "MPREb_dm", 6, NOW);
        songAlbum("live-forever", "MPREb_dm", 3, NOW);
        UUID twin = succeededSong(playlist, "supersonic-omv", "x\\s.mp3", NOW);
        UUID fresh = succeededSong(playlist, "live-forever", "x\\l.mp3", NOW);

        java.util.Map<UUID, String> copies = new java.util.HashMap<>();
        repository.tasksToOrganise(10, NOW.minusSeconds(600), NOW)
                .doOnNext(job -> copies.put(job.taskId(), job.filedCopy())).blockLast();

        assertEquals("/music/Oasis/DM/06.flac", copies.get(twin));
        assertTrue(copies.containsKey(fresh));
        assertNull(copies.get(fresh), "an album track the library does not have yet is filed as usual");
    }

    @Test
    void tasksToOrganise_givesAnAlbumTrackTheFileASongRequestPutInItsFolderFirst() {
        UUID song = insertDownload("SUCCEEDED", "SONG");
        songAlbum("supersonic", "MPREb_dm", 6, NOW.minusSeconds(3600));
        filed(song, "supersonic", "Supersonic", null, "EXACT", "/music/Oasis/DM/Supersonic.flac");
        UUID album = insertDownload("SUCCEEDED", "ALBUM", "MPREb_dm");
        UUID track = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (task_id, download_id, youtube_id, song_name, track_number, phase, "
                        + "phase_entered_at, next_attempt_at, finished_at, slskd_username, slskd_filename) "
                        + "VALUES (:task, :dl, 'supersonic', 'q', 6, 'SUCCEEDED', :at, :at, :at, 'bob', 'x\\06.flac')")
                .bind("task", track).bind("dl", album).bind("at", NOW)
                .fetch().rowsUpdated().block();

        LibraryOrganiser.Job job = repository.tasksToOrganise(10, NOW.minusSeconds(600), NOW).blockFirst();

        assertEquals(track, job.taskId());
        assertEquals("/music/Oasis/DM/Supersonic.flac", job.filedCopy());
    }

    private UUID succeededPick(UUID downloadId, String youtubeId, String title, String grade) {
        UUID taskId = succeededSong(downloadId, youtubeId, "x\\" + youtubeId + ".mp3", NOW);
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET track_title = :title, candidates = :c, candidate_index = 0 WHERE task_id = :id")
                .bind("title", title).bind("c", "[{\"grade\":\"" + grade + "\"}]").bind("id", taskId)
                .fetch().rowsUpdated().block();
        return taskId;
    }

    private java.util.Map<UUID, String> filedCopies() {
        java.util.Map<UUID, String> copies = new java.util.HashMap<>();
        repository.tasksToOrganise(10, NOW.minusSeconds(600), NOW)
                .doOnNext(job -> copies.put(job.taskId(), job.filedCopy())).blockLast();
        return copies;
    }

    @Test
    void tasksToOrganise_givesASingleAskedForTwiceTheFirstOnesFile_byIdAndTitle() {
        UUID first = insertDownload("SUCCEEDED", "SONG");
        filed(first, "blinding", "Blinding Lights", null, "EXACT", "/music/The Weeknd/Blinding Lights/b.flac");
        UUID again = insertDownload("SUCCEEDED", "PLAYLIST");
        UUID twin = succeededPick(again, "blinding", "Blinding Lights", "EXACT");
        UUID other = succeededPick(again, "blinding", "Blinding Lights (Live)", "EXACT");

        java.util.Map<UUID, String> copies = filedCopies();

        assertEquals("/music/The Weeknd/Blinding Lights/b.flac", copies.get(twin));
        assertNull(copies.get(other), "the same id under another title is another song");
    }

    @Test
    void tasksToOrganise_aStandInNeverPilesUpNextToAnother_butTheRealSongIsStillFiled() {
        UUID first = insertDownload("SUCCEEDED", "SONG");
        filed(first, "w", "Wonderwall", null, "OTHER_VERSION", "/music/Oasis/Wonderwall/live.flac");
        UUID again = insertDownload("SUCCEEDED", "SONG");
        UUID standIn = succeededPick(again, "w", "Wonderwall", "OTHER_VERSION");
        UUID real = succeededPick(again, "w", "Wonderwall", "EXACT");

        java.util.Map<UUID, String> copies = filedCopies();

        assertEquals("/music/Oasis/Wonderwall/live.flac", copies.get(standIn));
        assertNull(copies.get(real), "an exact pick is filed beside a stand-in, never thrown away for it");
        assertEquals(List.of(), copies(DownloadType.SONG, "w", wanted("w", "Wonderwall", null)),
                "asking again still searches for the real song");
    }

    @Test
    void tasksToOrganise_namesTwoCopiesOfOneSongAlike_soOnlyOneIsFiledPerPass() {
        UUID album = insertDownload("SUCCEEDED", "ALBUM", "MPREb_dm");
        UUID playlist = insertDownload("SUCCEEDED", "PLAYLIST");
        songAlbum("supersonic-omv", "MPREb_dm", 6, NOW);
        UUID track = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO download_tasks (task_id, download_id, youtube_id, song_name, track_number, phase, "
                        + "phase_entered_at, next_attempt_at, finished_at, slskd_username, slskd_filename) "
                        + "VALUES (:task, :dl, 'supersonic-atv', 'q', 6, 'SUCCEEDED', :at, :at, :at, 'bob', 'x\\06.flac')")
                .bind("task", track).bind("dl", album).bind("at", NOW)
                .fetch().rowsUpdated().block();
        UUID twin = succeededSong(playlist, "supersonic-omv", "x\\s.mp3", NOW);
        UUID single = succeededSong(playlist, "blinding", "x\\b.mp3", NOW);

        java.util.Map<UUID, String> keys = new java.util.HashMap<>();
        repository.tasksToOrganise(10, NOW.minusSeconds(600), NOW)
                .doOnNext(job -> keys.put(job.taskId(), job.copyKey())).blockLast();

        assertEquals("MPREb_dm#6", keys.get(track));
        assertEquals(keys.get(track), keys.get(twin), "the album's track and its video twin are one song");
        assertEquals("blinding", keys.get(single));
    }

    @Test
    void setLibraryPath_writesOnce() {
        UUID dl = insertDownload("SUCCEEDED");
        UUID taskId = succeededSong(dl, "s", "a\\s.flac", NOW);

        assertEquals(1L, repository.setLibraryPath(taskId, "/music/A/s/s.flac").block());
        assertEquals(0L, repository.setLibraryPath(taskId, "/music/elsewhere.flac").block());

        String stored = template.getDatabaseClient()
                .sql("SELECT library_path FROM download_tasks WHERE task_id = :id").bind("id", taskId)
                .map((row, meta) -> row.get("library_path", String.class)).one().block();
        assertEquals("/music/A/s/s.flac", stored);
    }

    private List<UUID> toFinalise() {
        return repository.downloadsToFinalise(10, NOW.minusSeconds(600)).map(LibraryOrganiser.Collection::downloadId)
                .collectList().block();
    }

    @Test
    void downloadsToFinalise_returnsAFinishedDownloadOnceEveryFiledOrGivenUpSongIsSettled() {
        UUID playlist = insertDownload("PARTIAL_SUCCESS", "PLAYLIST");
        media("yt-" + playlist, "Alt Nation 1989", "Various");
        repository.setLibraryPath(succeededSong(playlist, "a", "x\\a.flac", NOW), "/music/A/a/a.flac").block();
        UUID pending = succeededSong(playlist, "b", "x\\b.flac", NOW);   // filed? not yet

        assertEquals(List.of(), toFinalise(), "a song still being looked for holds the download back");

        repository.setLibraryPath(pending, "/music/B/b/b.flac").block();
        List<LibraryOrganiser.Collection> ready = repository.downloadsToFinalise(10, NOW.minusSeconds(600))
                .collectList().block();
        assertEquals(1, ready.size());
        assertEquals(playlist, ready.getFirst().downloadId());
        assertEquals(DownloadType.PLAYLIST, ready.getFirst().type());
        assertEquals("Alt Nation 1989", ready.getFirst().title());
    }

    @Test
    void downloadsToFinalise_countsAnOldUnfiledSongAsGivenUp_butNeedsAtLeastOneFiledSong() {
        UUID playlist = insertDownload("SUCCEEDED", "PLAYLIST");
        succeededSong(playlist, "old", "x\\old.flac", NOW.minusSeconds(601));   // never turned up
        assertEquals(List.of(), toFinalise(), "history: nothing was ever filed, nothing to write");

        repository.setLibraryPath(succeededSong(playlist, "a", "x\\a.flac", NOW), "/music/A/a/a.flac").block();
        assertEquals(List.of(playlist), toFinalise());
    }

    @Test
    void downloadsToFinalise_skipsRunningAndAlreadyOrganisedDownloads() {
        UUID running = insertDownload("IN_PROGRESS", "ALBUM");
        repository.setLibraryPath(succeededSong(running, "a", "x\\a.flac", NOW), "/music/A/a/a.flac").block();
        UUID done = insertDownload("SUCCEEDED", "ALBUM");
        repository.setLibraryPath(succeededSong(done, "b", "x\\b.flac", NOW), "/music/B/b/b.flac").block();

        assertEquals(List.of(done), toFinalise());
        assertEquals(1L, repository.setOrganisedAt(done, NOW).block());
        assertEquals(0L, repository.setOrganisedAt(done, NOW.plusSeconds(1)).block(), "writes once");
        assertEquals(List.of(), toFinalise());
    }

    @Test
    void playlistEntries_listsFiledSongsInTrackOrder_withTheirNamesAndLength() {
        UUID playlist = insertDownload("SUCCEEDED", "PLAYLIST");
        media("s2", "Whip It", "Devo");
        repository.upsertMedia(List.of(new MediaItem("s1", "Debaser", List.of("Pixies"), List.of(), null, 170, null))).block();
        UUID second = succeededSong(playlist, "s2", "x\\b.mp3", NOW);
        UUID first = succeededSong(playlist, "s1", "x\\a.flac", NOW);
        UUID unfiled = succeededSong(playlist, "s3", "x\\c.flac", NOW);
        template.getDatabaseClient().sql("UPDATE download_tasks SET position = CASE task_id "
                        + "WHEN :first THEN 1 WHEN :second THEN 2 ELSE 3 END WHERE download_id = :dl")
                .bind("first", first).bind("second", second).bind("dl", playlist).fetch().rowsUpdated().block();
        repository.setLibraryPath(second, "/music/Devo/Whip It/b.mp3").block();
        repository.setLibraryPath(first, "/music/Pixies/Debaser/a.flac").block();

        List<LibraryOrganiser.Entry> entries = repository.playlistEntries(playlist).collectList().block();

        assertEquals(List.of("/music/Pixies/Debaser/a.flac", "/music/Devo/Whip It/b.mp3"),
                entries.stream().map(LibraryOrganiser.Entry::libraryPath).toList());
        assertEquals("Debaser", entries.getFirst().title());
        assertEquals(List.of("Pixies"), entries.getFirst().artists());
        assertEquals(170, entries.getFirst().durationSeconds());
        assertNull(entries.get(1).durationSeconds());
        assertNotNull(unfiled, "the unfiled third song is simply absent from the list");
    }

    @Test
    void conclude_succeedsADownloadWhoseOnlySongSucceeded() {
        UUID id = admitOneSong("PENDING");
        finish(template, downloadService, taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW);

        assertEquals(1L, repository.concludeDownloads().block());
        assertEquals("SUCCEEDED", statusOf(id));
        assertEquals(NOW, downloadFinishedAtOf(id), "a download finished when its last song did");
    }

    @Test
    void conclude_failsADownloadWhoseOnlySongFailed() {
        UUID id = admitOneSong("PENDING");
        finish(template, downloadService, taskIdOf(id), DownloadStatus.FAILED,
                DownloadFailureCode.NO_CANDIDATES, NOW);

        assertEquals(1L, repository.concludeDownloads().block());
        assertEquals("FAILED", statusOf(id));
    }

    @Test
    void conclude_reportsPartialSuccessWhenACollectionGotSomeOfItsSongs() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "found", "missing");
        List<UUID> tasks = taskIdsOf(id);
        finish(template, downloadService, tasks.get(0), DownloadStatus.SUCCEEDED, null, NOW);
        finish(template, downloadService, tasks.get(1), DownloadStatus.FAILED,
                DownloadFailureCode.NO_CANDIDATES, NOW);

        assertEquals(1L, repository.concludeDownloads().block());
        assertEquals("PARTIAL_SUCCESS", statusOf(id));
    }

    @Test
    void conclude_leavesADownloadAloneWhileAnyOfItsSongsIsStillRunning() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "done", "still going");
        finish(template, downloadService, taskIdsOf(id).getFirst(), DownloadStatus.SUCCEEDED, null, NOW);

        assertEquals(0L, repository.concludeDownloads().block());
        assertEquals("IN_PROGRESS", statusOf(id),
                "one finished song of an album does not finish the album");
    }

    @Test
    void conclude_isIdempotent_soRunningItEveryPassCostsNothing() {
        UUID id = admitOneSong("PENDING");
        finish(template, downloadService, taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW);
        repository.concludeDownloads().block();

        // It runs unconditionally on every pass, so "already concluded" must match no rows at all
        // rather than rewriting the same status forever.
        assertEquals(0L, repository.concludeDownloads().block());
        assertEquals("SUCCEEDED", statusOf(id));
    }

    @Test
    void conclude_concludesEachOfTwoConcurrentlyFinishedDownloads() {
        // The race this statement exists to remove: the last two songs of a download finishing
        // together used to be able to leave it IN_PROGRESS forever, because each read a snapshot in
        // which the other was still running. Nothing decides per-song now, so the question is only
        // ever asked of settled rows.
        UUID album = insertDownload("PENDING", "ALBUM");
        admit(album, "a", "b");
        UUID song = admitOneSong("PENDING");
        for (UUID task : taskIdsOf(album)) {
            finish(template, downloadService, task, DownloadStatus.SUCCEEDED, null, NOW);
        }
        finish(template, downloadService, taskIdOf(song), DownloadStatus.FAILED,
                DownloadFailureCode.TIMED_OUT, NOW);

        assertEquals(2L, repository.concludeDownloads().block());
        assertEquals("SUCCEEDED", statusOf(album));
        assertEquals("FAILED", statusOf(song));
    }

    @Test
    void failUnadmitted_failsARequestWhoseMetadataNeverArrived_andSaysWhy() {
        UUID id = insertDownload("PENDING");

        assertEquals(1L, repository.failUnadmitted(id, DownloadFailureCode.METADATA_UNAVAILABLE, NOW)
                .block());

        assertEquals("FAILED", statusOf(id));
        assertEquals(0L, countTaskRows(), "there was never anything to search for");
        // The one failure with no task row to carry a reason; before this it was FAILED with no why.
        assertEquals("METADATA_UNAVAILABLE", template.getDatabaseClient()
                .sql("SELECT failure_reason FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("failure_reason", String.class)).one().block());
        assertEquals(NOW, downloadFinishedAtOf(id));
    }

    @Test
    void failUnadmitted_doesNotTouchADownloadThatWasAlreadyAdmitted() {
        UUID id = admitOneSong("PENDING");

        assertEquals(0L, repository.failUnadmitted(id, DownloadFailureCode.METADATA_UNAVAILABLE, NOW)
                .block());
        assertEquals("IN_PROGRESS", statusOf(id));
    }

    @Test
    void cancelTasks_marksLiveSongsCancelled_leavesFinishedOnesAlone_andReturnsTheirTransfers() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "done", "polling", "searching");
        List<UUID> tasks = taskIdsOf(id);
        finish(template, downloadService, tasks.get(0), DownloadStatus.SUCCEEDED, null, NOW);
        template.getDatabaseClient().sql("UPDATE download_tasks SET phase = 'DOWNLOAD_POLL', slskd_username = 'alice', "
                + "slskd_transfer_id = 't-1', lease_owner = 'x' WHERE task_id = :id").bind("id", tasks.get(1)).fetch().rowsUpdated().block();

        List<DownloadTask> cancelled = repository.cancelTasks(id, null, NOW).collectList().block();

        assertEquals(2, cancelled.size());
        assertEquals("SUCCEEDED", taskField(tasks.get(0), "phase"), "a finished song is not cancelled");
        assertEquals("FAILED", taskField(tasks.get(1), "phase"));
        assertEquals("CANCELLED", taskField(tasks.get(1), "failure_reason"));
        assertNull(taskField(tasks.get(1), "lease_owner"), "the lease is released so nothing else can write the row");
        DownloadTask polling = cancelled.stream().filter(t -> t.taskId().equals(tasks.get(1))).findFirst().orElseThrow();
        assertEquals("alice", polling.slskdUsername());
        assertEquals("t-1", polling.slskdTransferId());
        assertTrue(repository.claimDueTasks(10, "me", NOW.plusSeconds(1), Duration.ofMinutes(1), true, 2)
                .collectList().block().isEmpty(), "cancelled rows are never claimed again");
    }

    @Test
    void cancelTasks_forOneSong_touchesOnlyThatSong() {
        UUID id = insertDownload("PENDING", "ALBUM");
        admit(id, "a", "b");
        List<UUID> tasks = taskIdsOf(id);

        List<DownloadTask> cancelled = repository.cancelTasks(id, tasks.get(0), NOW).collectList().block();

        assertEquals(List.of(tasks.get(0)), cancelled.stream().map(DownloadTask::taskId).toList());
        assertEquals("SEARCH_INIT", taskField(tasks.get(1), "phase"));
    }

    @Test
    void cancelTasks_twice_theSecondIsANoOp() {
        UUID id = admitOneSong("PENDING");
        assertEquals(1, repository.cancelTasks(id, null, NOW).collectList().block().size());
        assertEquals(0, repository.cancelTasks(id, null, NOW).collectList().block().size());
    }

    // ---- whole album first (P5) ------------------------------------------------------------------

    private static final Instant HOLD = NOW.plusSeconds(240);

    /** An album download admitted the way the runner admits one: its search row, its songs held. */
    private UUID admitAlbum(String... songNames) {
        UUID id = insertDownload("PENDING", "ALBUM");
        repository.createTasks(id, Arrays.stream(songNames)
                .map(name -> DownloadTask.initial(id, "yt-" + name, name, NOW)).toList(), NOW, HOLD).block();
        media("yt-" + id, "Laughing Stock", "Talk Talk");
        return id;
    }

    private String albumField(UUID downloadId, String column) {
        return template.getDatabaseClient()
                .sql("SELECT " + column + "::text AS v FROM album_searches WHERE download_id = :id").bind("id", downloadId)
                .map((row, meta) -> Optional.ofNullable(row.get("v", String.class))).one()
                .blockOptional().flatMap(v -> v).orElse(null);
    }

    private Instant nextAttemptOf(UUID taskId) {
        return template.getDatabaseClient()
                .sql("SELECT next_attempt_at FROM download_tasks WHERE task_id = :id").bind("id", taskId)
                .map((row, meta) -> row.get("next_attempt_at", Instant.class)).one().block();
    }

    private void setTask(UUID taskId, String assignments) {
        template.getDatabaseClient().sql("UPDATE download_tasks SET " + assignments + " WHERE task_id = :id")
                .bind("id", taskId).fetch().rowsUpdated().block();
    }

    @Test
    void createTasks_forAnAlbum_startsItsAlbumSearch_andHoldsItsSongs() {
        UUID id = admitAlbum("1 Myrrhman", "2 Ascension Day");

        assertEquals("SEARCH_INIT", albumField(id, "phase"));
        assertTrue(repository.claimDueTasks(10, "x", NOW, Duration.ofSeconds(60), true, 10)
                .collectList().block().isEmpty(), "the songs wait for the album search");
        assertEquals(2, repository.claimDueTasks(10, "x", HOLD, Duration.ofSeconds(60), true, 10)
                .collectList().block().size(), "and search on their own once the hold runs out");
    }

    @Test
    void createTasks_forAnAlbumWithASongTheLibraryHas_holdsOnlyTheOthers_andTheSearchLeavesItAlone() {
        UUID id = insertDownload("PENDING", "ALBUM");
        repository.createTasks(id, List.of(
                DownloadTask.initial(id, "yt-1", "1 Myrrhman", NOW).toBuilder().libraryPath("/music/T/L/1.flac").build(),
                DownloadTask.initial(id, "yt-2", "2 Ascension Day", NOW)), NOW, HOLD).block();
        List<UUID> songs = taskIdsOf(id);

        assertEquals(List.of("SUCCEEDED", "SEARCH_INIT"), phasesOf(id));
        assertEquals(HOLD, nextAttemptOf(songs.get(1)));
        assertEquals(List.of(songs.get(1)), repository.waitingAlbumSongs(id, NOW).map(DownloadTask::taskId)
                .collectList().block(), "the album search only plans the song still to fetch");
    }

    @Test
    void createTasks_forASongOrPlaylist_startsNoAlbumSearch() {
        UUID id = insertDownload("PENDING", "PLAYLIST");
        admit(id, "a", "b");

        assertNull(albumField(id, "phase"));
        assertEquals(2, repository.claimDueTasks(10, "x", NOW, Duration.ofSeconds(60), true, 10)
                .collectList().block().size());
    }

    @Test
    void claimDueAlbumSearches_startsAtMostTheFreeSlots_withALease_andTheAlbumsName() {
        admitAlbum("1 a");
        admitAlbum("1 b");

        assertTrue(repository.claimDueAlbumSearches(10, "me", NOW, Duration.ofSeconds(60), 0)
                .collectList().block().isEmpty(), "no free search slot, no album search starts");
        List<AlbumSearch> claimed = repository.claimDueAlbumSearches(10, "me", NOW, Duration.ofSeconds(60), 1)
                .collectList().block();

        assertEquals(1, claimed.size());
        assertEquals(DownloadPhase.SEARCH_INIT, claimed.getFirst().phase());
        assertEquals("Laughing Stock", claimed.getFirst().title());
        assertEquals(List.of("Talk Talk"), claimed.getFirst().artists());
        assertEquals("me", albumField(claimed.getFirst().downloadId(), "lease_owner"));
        assertEquals(1, repository.claimDueAlbumSearches(10, "other", NOW.plusSeconds(1), Duration.ofSeconds(60), 2)
                .collectList().block().size(), "a live lease is skipped: only the other album is claimed");
    }

    @Test
    void countActiveSearches_countsRunningAlbumSearchesToo() {
        UUID id = admitAlbum("1 a");
        AlbumSearch claimed = repository.claimDueAlbumSearches(10, "me", NOW, Duration.ofSeconds(60), 2).blockFirst();

        repository.saveAlbumSearch(claimed.toBuilder().phase(DownloadPhase.SEARCH_POLL).searchId("s1").build(),
                "me", NOW, HOLD.plusSeconds(60)).block();

        assertEquals(1L, repository.countActiveSearches().block());
        assertEquals("s1", albumField(id, "search_id"));
    }

    @Test
    void saveAlbumSearch_startingAWording_holdsTheWaitingSongsLonger_butNotOnesAlreadySearchingOnTheirOwn() {
        UUID id = admitAlbum("1 waiting", "2 fell due", "3 retrying its second wording");
        List<UUID> songs = taskIdsOf(id);
        setTask(songs.get(1), "next_attempt_at = '" + NOW.minusSeconds(1) + "'");
        setTask(songs.get(2), "search_tier = 1, next_attempt_at = '" + NOW.plusSeconds(2) + "'");
        AlbumSearch claimed = repository.claimDueAlbumSearches(10, "me", NOW, Duration.ofSeconds(60), 2).blockFirst();

        assertEquals(1L, repository.saveAlbumSearch(claimed.toBuilder().phase(DownloadPhase.SEARCH_POLL).searchId("s1")
                .build(), "me", NOW, HOLD.plusSeconds(60)).block());

        assertEquals(HOLD.plusSeconds(60), nextAttemptOf(songs.get(0)));
        assertEquals(NOW.minusSeconds(1), nextAttemptOf(songs.get(1)));
        assertEquals(NOW.plusSeconds(2), nextAttemptOf(songs.get(2)), "the release will not touch it, so neither may the hold");
        AlbumSearch polling = repository.claimDueAlbumSearches(10, "me", NOW, Duration.ofSeconds(60), 2).blockFirst();
        assertEquals(1L, repository.saveAlbumSearch(polling.toBuilder().nextAttemptAt(NOW.plusSeconds(2)).build(),
                "me", NOW, null).block());
        assertEquals(HOLD.plusSeconds(60), nextAttemptOf(songs.get(0)), "a poll's save holds nobody");
        assertEquals(0L, repository.saveAlbumSearch(claimed, "me", NOW, null).block(), "the save cleared the lease");
    }

    @Test
    void releaseAlbumSongs_handsTheFolderSongsTheirFiles_andLeavesEverySongThatAlreadyMovedOnAlone() {
        UUID id = admitAlbum("1 picked", "2 unpicked", "3 cancelled", "4 between wordings", "5 leased", "6 lease expired");
        List<UUID> songs = taskIdsOf(id);
        repository.cancelTasks(id, songs.get(2), NOW).blockLast();
        setTask(songs.get(3), "search_tier = 1"); // its first wording found nothing worth keeping
        setTask(songs.get(4), "lease_owner = 'busy', lease_expires_at = '" + NOW.plusSeconds(30) + "'");
        setTask(songs.get(5), "lease_owner = 'gone', lease_expires_at = '" + NOW.minusSeconds(1) + "'");
        repository.claimDueAlbumSearches(10, "me", NOW, Duration.ofSeconds(60), 2).blockFirst();

        Long released = repository.releaseAlbumSongs(id, "me", AlbumSearch.Outcome.WHOLE_FOLDER,
                java.util.Map.of(songs.get(0), DownloadTaskFixtures.albumFolderCandidates("Baron53", "Nilkse")),
                NOW.plusSeconds(10)).block();

        assertEquals(3L, released, "picked, unpicked and the one whose lease had run out");
        assertEquals("DONE", albumField(id, "phase"));
        assertEquals("WHOLE_FOLDER", albumField(id, "outcome"));
        assertEquals("DOWNLOAD_INIT", taskField(songs.get(0), "phase"));
        assertEquals("SEARCH_INIT", taskField(songs.get(1), "phase"));
        assertEquals("FAILED", taskField(songs.get(2), "phase"), "a cancelled song stays cancelled");
        assertEquals(HOLD, nextAttemptOf(songs.get(3)), "a song between wordings keeps its own clock");
        assertEquals("SEARCH_INIT", taskField(songs.get(3), "phase"));
        assertEquals("busy", taskField(songs.get(4), "lease_owner"), "a song a live lease holds is not touched");
        assertNull(taskField(songs.get(5), "lease_owner"), "a stale step can no longer save over the release");

        // Both released songs are due now, and the folder files come back with their mark.
        List<DownloadTask> due = repository.claimDueTasks(10, "x", NOW.plusSeconds(10), Duration.ofSeconds(60), true, 10)
                .collectList().block();
        DownloadTask picked = due.stream().filter(t -> t.taskId().equals(songs.get(0))).findFirst().orElseThrow();
        assertEquals(List.of("Baron53", "Nilkse"), picked.candidates().stream().map(DownloadCandidate::username).toList());
        assertEquals(DownloadCandidate.ALBUM_FOLDER, picked.candidates().getFirst().source());
        assertEquals(NOW.plusSeconds(10), picked.phaseEnteredAt());
        assertTrue(due.stream().anyMatch(t -> t.taskId().equals(songs.get(1))));
    }

    @Test
    void releaseAlbumSongs_byAStepThatLostItsLease_orASecondTime_releasesNothing() {
        UUID id = admitAlbum("1 a");
        repository.claimDueAlbumSearches(10, "dead", NOW, Duration.ofSeconds(60), 2).blockFirst();
        repository.claimDueAlbumSearches(10, "alive", NOW.plusSeconds(61), Duration.ofSeconds(60), 2).blockFirst();

        assertEquals(0L, repository.releaseAlbumSongs(id, "dead", AlbumSearch.Outcome.NO_WHOLE_FOLDER,
                java.util.Map.of(), NOW.plusSeconds(62)).block());
        assertEquals("SEARCH_INIT", albumField(id, "phase"));
        assertEquals(HOLD, repository.waitingAlbumSongs(id, NOW).blockFirst().nextAttemptAt(), "still held");

        assertEquals(1L, repository.releaseAlbumSongs(id, "alive", AlbumSearch.Outcome.NO_WHOLE_FOLDER,
                java.util.Map.of(), NOW.plusSeconds(62)).block());
        assertEquals(0L, repository.releaseAlbumSongs(id, "alive", AlbumSearch.Outcome.NO_WHOLE_FOLDER,
                java.util.Map.of(), NOW.plusSeconds(63)).block());
    }

    @Test
    void cancellingTheWholeAlbum_endsItsSearch_soItCanNoLongerHandOutFiles_butCancellingOneSongDoesNot() {
        UUID id = admitAlbum("1 a", "2 b");
        repository.claimDueAlbumSearches(10, "me", NOW, Duration.ofSeconds(60), 2).blockFirst();

        repository.cancelTasks(id, taskIdsOf(id).getFirst(), NOW).blockLast();
        assertEquals("SEARCH_INIT", albumField(id, "phase"));

        repository.cancelTasks(id, null, NOW).blockLast();
        assertEquals("DONE", albumField(id, "phase"));
        assertEquals("CANCELLED", albumField(id, "outcome"));
        // A retry reopens the songs; the album search must not hand them files: they search on their own.
        repository.concludeDownloads().block();
        repository.retry(id, null, NOW.plusSeconds(1)).block();
        assertEquals(0L, repository.releaseAlbumSongs(id, "me", AlbumSearch.Outcome.WHOLE_FOLDER,
                java.util.Map.of(taskIdsOf(id).getFirst(), DownloadTaskFixtures.albumFolderCandidates("x")), NOW.plusSeconds(2)).block());
        assertEquals(List.of("SEARCH_INIT", "SEARCH_INIT"), phasesOf(id));
    }

    @Test
    void cancellingTheWholeAlbum_locksItsSearchBeforeItsSongs_asTheReleaseDoes_soTheTwoCannotDeadlock() throws Exception {
        UUID id = admitAlbum("1 a", "2 b");
        try (Connection release = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // A release that has just marked the search done and is about to hand out the songs.
            release.setAutoCommit(false);
            release.createStatement().execute("SELECT 1 FROM album_searches WHERE download_id = '" + id + "' FOR UPDATE");
            CompletableFuture<List<DownloadTask>> cancel = repository.cancelTasks(id, null, NOW).collectList().toFuture();
            Instant deadline = Instant.now().plusSeconds(10);
            while (template.getDatabaseClient().sql("SELECT count(*) AS n FROM pg_stat_activity "
                            + "WHERE wait_event_type = 'Lock' AND query LIKE '%UPDATE album_searches%'")
                    .map((row, meta) -> row.get("n", Long.class)).one().block() == 0) {
                assertTrue(Instant.now().isBefore(deadline), "the cancel never waited for the album row");
                Thread.sleep(20);
            }
            // The cancel is waiting for the album row while holding no song, so the release can take them.
            release.createStatement().execute("SELECT 1 FROM download_tasks WHERE download_id = '" + id + "' FOR UPDATE NOWAIT");
            release.commit();
            assertEquals(2, cancel.get(10, TimeUnit.SECONDS).size());
        }
        assertEquals("CANCELLED", albumField(id, "outcome"));
    }

    @Test
    void waitingAlbumSongs_areTheUntouchedOnes_inTrackOrder() {
        UUID id = admitAlbum("b second", "a first", "c started");
        setTask(taskIdsOf(id).get(2), "search_id = 's9'");

        assertEquals(List.of("b second", "a first"), repository.waitingAlbumSongs(id, NOW)
                .map(DownloadTask::songName).collectList().block());
    }

    // ---- retry ---------------------------------------------------------------------------------

    /** A finished single-song download whose song failed. */
    private UUID failedSong() {
        UUID id = admitOneSong("PENDING");
        finish(template, downloadService, taskIdOf(id), DownloadStatus.FAILED, DownloadFailureCode.NO_CANDIDATES, NOW);
        repository.concludeDownloads().block();
        return id;
    }

    @Test
    void retry_resetsOnlyFailedSongs_reopensTheDownload_andClearsOrganisedAt() {
        UUID id = insertDownload("PENDING", "PLAYLIST");
        // Numbered because taskIdsOf orders by song name.
        admit(id, "1 ok", "2 bad", "3 stopped");
        List<UUID> tasks = taskIdsOf(id);
        finish(template, downloadService, tasks.get(0), DownloadStatus.SUCCEEDED, null, NOW);
        finish(template, downloadService, tasks.get(1), DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED, NOW);
        repository.cancelTasks(id, tasks.get(2), NOW).blockLast();
        repository.concludeDownloads().block();
        repository.setOrganisedAt(id, NOW).block();
        assertEquals("PARTIAL_SUCCESS", statusOf(id));

        Long rows = repository.retry(id, null, NOW.plusSeconds(60)).block();

        assertEquals(2L, rows, "the failed song and the cancelled one");
        assertEquals("IN_PROGRESS", statusOf(id));
        assertNull(organisedAtOf(id), "the playlist file is rewritten once the retried songs land");
        assertEquals("SUCCEEDED", taskField(tasks.get(0), "phase"), "a song with a file is left alone");
        assertEquals("SEARCH_INIT", taskField(tasks.get(1), "phase"));
        assertEquals("SEARCH_INIT", taskField(tasks.get(2), "phase"), "a cancelled song is retried too: that is how a cancel is undone");
        assertNull(taskField(tasks.get(1), "failure_reason"));
        assertEquals(2, repository.claimDueTasks(10, "me", NOW.plusSeconds(61), Duration.ofMinutes(1), true, 2)
                .collectList().block().size(), "both reset songs are due again");
    }

    @Test
    void retry_twice_theSecondIsANoOp() {
        UUID id = failedSong();
        assertEquals(1L, repository.retry(id, null, NOW).block());
        assertEquals(0L, repository.retry(id, null, NOW).block(), "the second click finds no failed song left");
    }

    @Test
    void retry_twoConcurrentCalls_exactlyOneWins() {
        UUID id = failedSong();
        Tuple2<Long, Long> both = Mono.zip(repository.retry(id, null, NOW), repository.retry(id, null, NOW)).block();
        assertEquals(1L, both.getT1() + both.getT2());
    }

    @Test
    void retry_whileInProgress_isANoOp() {
        UUID id = admitOneSong("PENDING");
        assertEquals(0L, repository.retry(id, null, NOW).block());
        assertEquals("SEARCH_INIT", taskField(taskIdsOf(id).getFirst(), "phase"));
    }

    @Test
    void retry_ofAFullyDownloadedDownload_isANoOp() {
        UUID id = admitOneSong("PENDING");
        finish(template, downloadService, taskIdsOf(id).getFirst(), DownloadStatus.SUCCEEDED, null, NOW);
        repository.concludeDownloads().block();
        assertEquals(0L, repository.retry(id, null, NOW).block());
        assertEquals(0L, repository.readmit(id).block(), "songs exist, so this is not an unadmitted failure either");
    }

    @Test
    void readmit_ofAnUnadmittedFailure_returnsItToPending() {
        UUID id = insertDownload("PENDING");
        repository.failUnadmitted(id, DownloadFailureCode.CANCELLED, NOW).block();

        assertEquals(1L, repository.readmit(id).block());
        assertEquals("PENDING", statusOf(id));
        assertNull(failureReasonOfDownload(id));
        assertEquals(1, repository.admitDownloads(10).collectList().block().size(), "admission picks it up again");
    }

    @Test
    void setOrganisedAt_afterARetry_updatesNothing() {
        UUID id = failedSong();
        repository.retry(id, null, NOW).block();
        assertEquals(0L, repository.setOrganisedAt(id, NOW).block(), "a stamp in flight when the user clicked Retry must not land");
    }

    // ---- retry of one song ---------------------------------------------------------------------

    /** A live three-song playlist: song 1 has its file, song 2 failed, song 3 is still to be searched. */
    private UUID livePlaylistWithOneFailedSong() {
        UUID id = insertDownload("PENDING", "PLAYLIST");
        admit(id, "1 ok", "2 bad", "3 live");
        List<UUID> tasks = taskIdsOf(id);
        finish(template, downloadService, tasks.get(0), DownloadStatus.SUCCEEDED, null, NOW);
        finish(template, downloadService, tasks.get(1), DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED, NOW);
        repository.concludeDownloads().block();
        assertEquals("IN_PROGRESS", statusOf(id), "one song is still live, so the download has not concluded");
        return id;
    }

    @Test
    void retry_ofOneSong_whileTheDownloadRuns_resetsOnlyThatSong() {
        UUID id = livePlaylistWithOneFailedSong();
        List<UUID> tasks = taskIdsOf(id);

        Long rows = repository.retry(id, tasks.get(1), NOW.plusSeconds(60)).block();

        assertEquals(1L, rows);
        assertEquals("IN_PROGRESS", statusOf(id), "a live parent is left as it is");
        assertEquals("SEARCH_INIT", taskField(tasks.get(1), "phase"));
        assertNull(taskField(tasks.get(1), "failure_reason"));
        assertEquals("SUCCEEDED", taskField(tasks.get(0), "phase"), "the song with a file is untouched");
        assertEquals("SEARCH_INIT", taskField(tasks.get(2), "phase"), "the live sibling is untouched");
        assertEquals(NOW, nextAttemptOf(tasks.get(2)), "and keeps its own clock");
        List<UUID> claimed = repository.claimDueTasks(10, "me", NOW.plusSeconds(61), Duration.ofMinutes(1), true, 2)
                .map(DownloadTask::taskId).collectList().block();
        assertTrue(claimed.contains(tasks.get(1)), "the reset song is due again like any new one");
    }

    @Test
    void retry_ofOneSong_twice_theSecondIsANoOp() {
        UUID id = livePlaylistWithOneFailedSong();
        UUID failed = taskIdsOf(id).get(1);
        assertEquals(1L, repository.retry(id, failed, NOW).block());
        assertEquals(0L, repository.retry(id, failed, NOW).block(), "the second click finds the song no longer FAILED");
    }

    @Test
    void retry_ofOneSong_twoConcurrentCalls_exactlyOneWins() {
        UUID id = livePlaylistWithOneFailedSong();
        UUID failed = taskIdsOf(id).get(1);
        Tuple2<Long, Long> both = Mono.zip(repository.retry(id, failed, NOW), repository.retry(id, failed, NOW)).block();
        assertEquals(1L, both.getT1() + both.getT2());
    }

    @Test
    void retry_ofOneSong_ofAConcludedDownload_resetsThatSongOnly_andReopensIt() {
        UUID id = insertDownload("PENDING", "PLAYLIST");
        admit(id, "1 ok", "2 bad", "3 also bad");
        List<UUID> tasks = taskIdsOf(id);
        finish(template, downloadService, tasks.get(0), DownloadStatus.SUCCEEDED, null, NOW);
        finish(template, downloadService, tasks.get(1), DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED, NOW);
        finish(template, downloadService, tasks.get(2), DownloadStatus.FAILED, DownloadFailureCode.NO_CANDIDATES, NOW);
        repository.concludeDownloads().block();
        repository.setOrganisedAt(id, NOW).block();
        assertEquals("PARTIAL_SUCCESS", statusOf(id));

        assertEquals(1L, repository.retry(id, tasks.get(1), NOW.plusSeconds(60)).block());

        assertEquals("IN_PROGRESS", statusOf(id), "the finished download is live again, as a whole retry would make it");
        assertNull(organisedAtOf(id), "the playlist file is rewritten once the retried song lands");
        assertEquals("SEARCH_INIT", taskField(tasks.get(1), "phase"));
        assertEquals("FAILED", taskField(tasks.get(2), "phase"), "the other failed song is left for its own Retry");
        assertEquals("SUCCEEDED", taskField(tasks.get(0), "phase"));
    }

    @Test
    void retry_ofTheWholeDownload_whileItRuns_isStillANoOp() {
        UUID id = livePlaylistWithOneFailedSong();
        assertEquals(0L, repository.retry(id, null, NOW).block(), "the 28-09 rule: never retry a running download whole");
        assertEquals("FAILED", taskField(taskIdsOf(id).get(1), "phase"));
    }

    @Test
    void retry_ofASongOfAnotherDownload_isANoOp() {
        UUID id = livePlaylistWithOneFailedSong();
        UUID other = failedSong();
        assertEquals(0L, repository.retry(id, taskIdOf(other), NOW).block(), "the task is not this download's");
        assertEquals("FAILED", taskField(taskIdOf(other), "phase"));
        assertEquals("FAILED", statusOf(other));
        assertEquals("FAILED", taskField(taskIdsOf(id).get(1), "phase"), "and nothing of this download moved either");
    }

    @Test
    void retry_ofASongThatIsNotFailed_isANoOp() {
        UUID id = livePlaylistWithOneFailedSong();
        List<UUID> tasks = taskIdsOf(id);
        assertEquals(0L, repository.retry(id, tasks.get(0), NOW).block(), "a song with a file");
        assertEquals(0L, repository.retry(id, tasks.get(2), NOW).block(), "a song still running");
        assertEquals("SUCCEEDED", taskField(tasks.get(0), "phase"));
        assertEquals("SEARCH_INIT", taskField(tasks.get(2), "phase"));
    }

    @Test
    void finishTask_afterOneSongWasCancelledAndRetried_isANoOp() {
        UUID id = insertDownload("PENDING", "PLAYLIST");
        admit(id, "1 a", "2 b");
        DownloadTask claimed = repository.claimDueTasks(10, "owner-a", NOW, Duration.ofMinutes(1), true, 2).blockFirst();
        repository.cancelTasks(id, claimed.taskId(), NOW).blockLast();
        repository.retry(id, claimed.taskId(), NOW.plusSeconds(2)).block();   // same pass: the old step is still out

        Long rows = downloadService.finishTask(claimed.taskId(), DownloadStatus.SUCCEEDED, null, NOW.plusSeconds(3), "owner-a").block();

        assertEquals(0L, rows, "the old step's outcome must not land on the new attempt");
        assertEquals("SEARCH_INIT", taskField(claimed.taskId(), "phase"));
        assertEquals("IN_PROGRESS", statusOf(id));
    }

    @Test
    void retry_ofOneAlbumTrack_leavesTheAlbumSearchDone_andKeepsItsLength() {
        UUID id = admitAlbum("1 a", "2 b");
        List<UUID> songs = taskIdsOf(id);
        setTask(songs.get(1), "track_number = 2, duration_seconds = 270");
        repository.claimDueAlbumSearches(10, "me", NOW, Duration.ofSeconds(60), 2).blockFirst();
        repository.releaseAlbumSongs(id, "me", AlbumSearch.Outcome.NO_WHOLE_FOLDER, java.util.Map.of(), NOW).block();
        assertEquals("DONE", albumField(id, "phase"));
        finish(template, downloadService, songs.get(1), DownloadStatus.FAILED, DownloadFailureCode.SOURCES_EXHAUSTED, NOW);

        assertEquals(1L, repository.retry(id, songs.get(1), NOW.plusSeconds(1)).block());

        assertEquals("SEARCH_INIT", taskField(songs.get(1), "phase"));
        assertEquals("[]", taskField(songs.get(1), "candidates"), "a solo search from the first wording");
        assertEquals("DONE", albumField(id, "phase"), "the album's folder search is not re-run");
        assertEquals("NO_WHOLE_FOLDER", albumField(id, "outcome"));
        assertEquals("2", taskField(songs.get(1), "track_number"), "so the length filter still applies");
        assertEquals("270", taskField(songs.get(1), "duration_seconds"));
        assertEquals("SEARCH_INIT", taskField(songs.get(0), "phase"), "the sibling is untouched");
        assertEquals("IN_PROGRESS", statusOf(id));
    }

    @Test
    void finishTask_afterTheRowWasCancelledAndRetried_isANoOp() {
        UUID id = admitOneSong("PENDING");
        DownloadTask claimed = repository.claimDueTasks(10, "owner-a", NOW, Duration.ofMinutes(1), true, 2).blockFirst();
        repository.cancelTasks(id, null, NOW).blockLast();
        repository.concludeDownloads().block();
        repository.retry(id, null, NOW.plusSeconds(2)).block();

        Long rows = downloadService.finishTask(claimed.taskId(), DownloadStatus.SUCCEEDED, null, NOW.plusSeconds(3), "owner-a").block();

        assertEquals(0L, rows, "the old step's outcome must not land on the new attempt");
        assertEquals("SEARCH_INIT", taskField(claimed.taskId(), "phase"));
    }

    @Test
    void save_advancesUpdatedAt() {
        UUID id = admitOneSong("PENDING");
        DownloadTask claimed = repository.claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true, 10)
                .blockFirst();
        Instant admitted = updatedAtOf(id);

        repository.save(claimed.withPhase(DownloadPhase.SEARCH_POLL, NOW), "a").block();

        // The feed's recency sort key. phase_entered_at cannot serve as one, because it deliberately
        // does not move when only progress changes.
        assertTrue(updatedAtOf(id).isAfter(admitted),
                "updated_at must move on every write, since the feed orders on it");
    }

    @Test
    void claimDueTasks_neverReturnsTerminalTasks() {
        UUID id = admitOneSong("PENDING");
        finish(template, downloadService, taskIdOf(id), DownloadStatus.SUCCEEDED, null, NOW);

        assertTrue(repository
                .claimDueTasks(10, "a", NOW.plusSeconds(86_400), Duration.ofSeconds(60), true, 10)
                .collectList().block().isEmpty(),
                "a finished download must never be stepped again");
    }

    @Test
    void claimDueTasks_withNoTransferSlots_stillReturnsSearchTasks_butNotDownloadInit() {
        UUID searching = admitOneSong("PENDING");
        UUID starting = admitOneSong("PENDING");
        // Move one task to DOWNLOAD_INIT; leave the other at SEARCH_INIT. Direct SQL, not
        // repository.save(): save() now requires a live lease, and this is fixture setup, not a
        // claimed step.
        moveToPhase(starting, DownloadPhase.DOWNLOAD_INIT);

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), false, 10)
                .collectList().block();

        assertEquals(1, claimed.size(), "polling and searching must never be starved by the cap");
        assertEquals(searching, claimed.getFirst().downloadId());
    }

    @Test
    void transfersInFlight_doesNotListDownloadInitRows_thisIsTheDeadlockRegressionGuard() {
        // Reproduces the durable deadlock: if DOWNLOAD_INIT counted against the transfer cap, then
        // once max-concurrent-transfers worth of downloads landed in DOWNLOAD_INIT together, the cap
        // would be "full" of rows that CLAIM_DUE_SQL simultaneously refuses to claim (since
        // transferSlotsFree would be false) -- those rows could then never advance out of
        // DOWNLOAD_INIT, so the count could never drop, and the gate would stay closed forever, even
        // across a restart, because it is backed by the DB. This wires the REAL transfersInFlight()
        // to prove DOWNLOAD_INIT rows never contribute to that count in the first place.
        int maxConcurrentTransfers = 20;
        for (int i = 0; i < maxConcurrentTransfers; i++) {
            moveToPhase(admitOneSong("PENDING"), DownloadPhase.DOWNLOAD_INIT);
        }

        assertEquals(maxConcurrentTransfers, countTaskRowsInPhase("DOWNLOAD_INIT"),
                "sanity check: every row really is sitting in DOWNLOAD_INIT");
        assertEquals(0L, repository.transfersInFlight().count().block(),
                "DOWNLOAD_INIT rows must never count against the transfer cap -- otherwise the cap "
                        + "closes on rows that can never be claimed while it is closed, and never reopens");
    }

    @Test
    void transfersInFlight_listsEachDownloadPollRow_withTheSharerItIsWith() {
        UUID polling = admitOneSong("PENDING");
        moveToPhase(polling, DownloadPhase.DOWNLOAD_POLL);
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET slskd_username = 'alice', slskd_transfer_id = 't1' "
                        + "WHERE download_id = :id")
                .bind("id", polling).fetch().rowsUpdated().block();
        moveToPhase(admitOneSong("PENDING"), DownloadPhase.DOWNLOAD_INIT);

        assertEquals(List.of(new DownloadTaskRepository.TransferInFlight("alice", "t1")),
                repository.transfersInFlight().collectList().block());
    }

    @Test
    void claimDueTasks_withNoSearchSlots_stillReturnsPollsAndDownloadInit_butNoSearchInit() {
        UUID starting = admitOneSong("PENDING");
        UUID polling = admitOneSong("PENDING");
        UUID enqueueing = admitOneSong("PENDING");
        moveToPhase(polling, DownloadPhase.SEARCH_POLL);
        moveToPhase(enqueueing, DownloadPhase.DOWNLOAD_INIT);

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true, 0)
                .collectList().block();

        // Polling a running search is never gated -- that is what lets the count drop again.
        assertEquals(java.util.Set.of(enqueueing, polling),
                claimed.stream().map(DownloadTask::downloadId).collect(java.util.stream.Collectors.toSet()));
        assertFalse(claimed.stream().anyMatch(t -> t.downloadId().equals(starting)),
                "a search must not be started while slskd's two slots are both taken");
    }

    @Test
    void claimDueTasks_claimsOnlyAsManySearchInitRowsAsThereAreFreeSearchSlots() {
        for (int i = 0; i < 5; i++) {
            admitOneSong("PENDING");
        }

        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true, 2)
                .collectList().block();

        // A yes/no gate here would have claimed all five (batch-size allows ten) the moment the
        // count dipped under the cap -- five searches in flight against a cap of two.
        assertEquals(2, claimed.size());
        assertTrue(claimed.stream().allMatch(t -> t.phase() == DownloadPhase.SEARCH_INIT));
    }

    @Test
    void countActiveSearches_countsSearchPollOnly_soTheSearchGateCannotDeadlock() {
        // Same shape as the transfer gate's regression guard: if SEARCH_INIT counted, then enough
        // SEARCH_INIT rows would close a gate that only SEARCH_INIT rows can be held back by, and
        // nothing could ever reopen it.
        moveToPhase(admitOneSong("PENDING"), DownloadPhase.SEARCH_POLL);
        moveToPhase(admitOneSong("PENDING"), DownloadPhase.SEARCH_POLL);
        admitOneSong("PENDING");
        moveToPhase(admitOneSong("PENDING"), DownloadPhase.DOWNLOAD_POLL);

        assertEquals(2L, repository.countActiveSearches().block());
    }

    @Test
    void claimDueTasks_toleratesARowWithCorruptCandidatesJson_andStillReturnsTheOtherValidRow() {
        UUID corrupt = admitOneSong("PENDING");
        UUID healthy = admitOneSong("PENDING");
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET candidates = 'not valid json' WHERE download_id = :id")
                .bind("id", corrupt)
                .fetch().rowsUpdated().block();

        // Before the fix, readCandidates threw on the corrupt row, which failed the whole Flux
        // returned by claimDueTasks -- discarding the healthy row too, not just the corrupt one.
        List<DownloadTask> claimed = repository
                .claimDueTasks(10, "a", NOW, Duration.ofSeconds(60), true, 10)
                .collectList().block();

        assertEquals(2, claimed.size(),
                "a row with unreadable candidates JSON must not abort the claim for the other row");
        DownloadTask corruptTask = claimed.stream()
                .filter(t -> t.downloadId().equals(corrupt)).findFirst().orElseThrow();
        DownloadTask healthyTask = claimed.stream()
                .filter(t -> t.downloadId().equals(healthy)).findFirst().orElseThrow();
        assertEquals(List.of(), corruptTask.candidates(),
                "unreadable candidates fall back to empty rather than poisoning the row");
        assertNotNull(healthyTask);
    }

    private String statusOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT status FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("status", String.class)).one().block();
    }

    private Instant admittedAtOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT admitted_at FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("admitted_at", Instant.class)).one().block();
    }

    private Instant downloadFinishedAtOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT finished_at FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("finished_at", Instant.class)).one().block();
    }

    private Instant organisedAtOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT organised_at FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> Optional.ofNullable(row.get("organised_at", Instant.class)))
                .one().block().orElse(null);
    }

    private String failureReasonOfDownload(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT failure_reason FROM downloads WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> Optional.ofNullable(row.get("failure_reason", String.class)))
                .one().block().orElse(null);
    }

    private String mediaField(String youtubeId, String column) {
        return template.getDatabaseClient()
                .sql("SELECT " + column + " FROM media_items WHERE youtube_id = :id").bind("id", youtubeId)
                .map((row, meta) -> row.get(column, String.class)).one().block();
    }

    private List<String> mediaArtists(String youtubeId) {
        return template.getDatabaseClient()
                .sql("SELECT artists FROM media_items WHERE youtube_id = :id").bind("id", youtubeId)
                .map((row, meta) -> List.of(row.get("artists", String[].class))).one().block();
    }

    private List<String> mediaArtistIds(String youtubeId) {
        return template.getDatabaseClient()
                .sql("SELECT artist_ids FROM media_items WHERE youtube_id = :id").bind("id", youtubeId)
                .map((row, meta) -> List.of(row.get("artist_ids", String[].class))).one().block();
    }

    private String phaseOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).one().block();
    }

    private List<String> phasesOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT phase FROM download_tasks WHERE download_id = :id ORDER BY song_name")
                .bind("id", id)
                .map((row, meta) -> row.get("phase", String.class)).all().collectList().block();
    }

    private UUID taskIdOf(UUID downloadId) {
        return taskIdsOf(downloadId).getFirst();
    }

    private List<UUID> taskIdsOf(UUID downloadId) {
        return template.getDatabaseClient()
                .sql("SELECT task_id FROM download_tasks WHERE download_id = :id ORDER BY song_name")
                .bind("id", downloadId)
                .map((row, meta) -> row.get("task_id", UUID.class)).all().collectList().block();
    }

    private long countTaskRows() {
        return template.getDatabaseClient()
                .sql("SELECT count(*) AS total FROM download_tasks")
                .map((row, meta) -> row.get("total", Long.class)).one().block();
    }

    private long countTaskRowsInPhase(String phase) {
        return template.getDatabaseClient()
                .sql("SELECT count(*) AS total FROM download_tasks WHERE phase = :phase")
                .bind("phase", phase)
                .map((row, meta) -> row.get("total", Long.class)).one().block();
    }

    /** Fixture setup only -- bypasses the lease guard that repository.save() enforces. */
    private void moveToPhase(UUID id, DownloadPhase phase) {
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET phase = :phase WHERE download_id = :id")
                .bind("phase", phase.name()).bind("id", id)
                .fetch().rowsUpdated().block();
    }

    private Instant finishedAtOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT finished_at FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("finished_at", Instant.class)).one().block();
    }

    private Instant updatedAtOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT updated_at FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("updated_at", Instant.class)).one().block();
    }

    private String failureReasonOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT failure_reason FROM download_tasks WHERE download_id = :id")
                .bind("id", id)
                .map((row, meta) -> Optional.ofNullable(row.get("failure_reason", String.class)))
                .one().block().orElse(null);
    }

    /** One column of one song's row, by task id; the helpers above key on the download. */
    private String taskField(UUID taskId, String column) {
        return template.getDatabaseClient()
                .sql("SELECT " + column + " FROM download_tasks WHERE task_id = :id").bind("id", taskId)
                .map((row, meta) -> Optional.ofNullable(row.get(column, String.class)))
                .one().block().orElse(null);
    }

    private String leaseOwnerOf(UUID id) {
        return template.getDatabaseClient()
                .sql("SELECT lease_owner FROM download_tasks WHERE download_id = :id").bind("id", id)
                .map((row, meta) -> row.get("lease_owner", String.class)).one().block();
    }
}
