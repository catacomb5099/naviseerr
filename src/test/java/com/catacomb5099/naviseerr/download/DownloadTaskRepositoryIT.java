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
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.catacomb5099.naviseerr.support.TaskFinishing.finish;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "download-task.loop-interval-ms=3600000")
class DownloadTaskRepositoryIT {

    @Autowired R2dbcEntityTemplate template;
    @Autowired DownloadTaskRepository repository;
    @Autowired DownloadService downloadService;

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @BeforeEach
    void clean() {
        template.getDatabaseClient().sql("DELETE FROM download_tasks").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM downloads").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM media_items").fetch().rowsUpdated().block();
        template.getDatabaseClient().sql("DELETE FROM song_albums").fetch().rowsUpdated().block();
    }

    private UUID insertDownload(String status) {
        return insertDownload(status, "SONG");
    }

    private UUID insertDownload(String status, String type) {
        UUID id = UUID.randomUUID();
        template.getDatabaseClient()
                .sql("INSERT INTO downloads (download_id, youtube_id, download_type, status, created_at) "
                        + "VALUES (:id, :ytId, :type, :status, now())")
                .bind("id", id).bind("ytId", "yt-" + id).bind("type", type).bind("status", status)
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

        Long rows = repository.retry(id, NOW.plusSeconds(60)).block();

        assertEquals(1L, rows);
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
        assertEquals(1L, repository.retry(id, NOW).block());
        assertEquals(0L, repository.retry(id, NOW).block(), "the second click finds no failed song left");
    }

    @Test
    void retry_twoConcurrentCalls_exactlyOneWins() {
        UUID id = failedSong();
        Tuple2<Long, Long> both = Mono.zip(repository.retry(id, NOW), repository.retry(id, NOW)).block();
        assertEquals(1L, both.getT1() + both.getT2());
    }

    @Test
    void retry_whileInProgress_isANoOp() {
        UUID id = admitOneSong("PENDING");
        assertEquals(0L, repository.retry(id, NOW).block());
        assertEquals("SEARCH_INIT", taskField(taskIdsOf(id).getFirst(), "phase"));
    }

    @Test
    void retry_ofAFullyDownloadedDownload_isANoOp() {
        UUID id = admitOneSong("PENDING");
        finish(template, downloadService, taskIdsOf(id).getFirst(), DownloadStatus.SUCCEEDED, null, NOW);
        repository.concludeDownloads().block();
        assertEquals(0L, repository.retry(id, NOW).block());
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
        repository.retry(id, NOW).block();
        assertEquals(0L, repository.setOrganisedAt(id, NOW).block(), "a stamp in flight when the user clicked Retry must not land");
    }

    @Test
    void finishTask_afterTheRowWasCancelledAndRetried_isANoOp() {
        UUID id = admitOneSong("PENDING");
        DownloadTask claimed = repository.claimDueTasks(10, "owner-a", NOW, Duration.ofMinutes(1), true, 2).blockFirst();
        repository.cancelTasks(id, null, NOW).blockLast();
        repository.concludeDownloads().block();
        repository.retry(id, NOW.plusSeconds(2)).block();

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
    void countActiveTransfers_doesNotCountDownloadInitRows_thisIsTheDeadlockRegressionGuard() {
        // Reproduces the durable deadlock: if DOWNLOAD_INIT counted against the transfer cap, then
        // once max-concurrent-transfers worth of downloads landed in DOWNLOAD_INIT together, the cap
        // would be "full" of rows that CLAIM_DUE_SQL simultaneously refuses to claim (since
        // transferSlotsFree would be false) -- those rows could then never advance out of
        // DOWNLOAD_INIT, so the count could never drop, and the gate would stay closed forever, even
        // across a restart, because it is backed by the DB. This wires the REAL countActiveTransfers()
        // to prove DOWNLOAD_INIT rows never contribute to that count in the first place.
        int maxConcurrentTransfers = 20;
        for (int i = 0; i < maxConcurrentTransfers; i++) {
            moveToPhase(admitOneSong("PENDING"), DownloadPhase.DOWNLOAD_INIT);
        }

        assertEquals(maxConcurrentTransfers, countTaskRowsInPhase("DOWNLOAD_INIT"),
                "sanity check: every row really is sitting in DOWNLOAD_INIT");
        assertEquals(0L, repository.countActiveTransfers().block(),
                "DOWNLOAD_INIT rows must never count against the transfer cap -- otherwise the cap "
                        + "closes on rows that can never be claimed while it is closed, and never reopens");
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
