package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DownloadService {

    // Marks ONE SONG's task row terminal. It used to also write the download's status, in the same
    // statement, so the two could not be split by a crash -- and that was right while a download had
    // exactly one task. It is not achievable now that a download has N: the download's status is a
    // function of all N task rows, which this statement cannot see the effect of its own write on.
    // DownloadTaskRepository.CONCLUDE_SQL took that half over; its Javadoc has the reasoning.
    //
    // The idempotence guard stays, and is still load-bearing. Writing unconditionally would let a
    // second finish for an already-terminal task re-stamp finished_at, sliding a long-finished row
    // back inside the feed's retention window and resurrecting a card the user dismissed hours ago.
    private static final String FINISH_TASK_SQL = """
            UPDATE download_tasks
               SET phase = :status,
                   phase_entered_at = :now,
                   -- now(), not :now -- see the note in DownloadTaskRepository.SAVE_SQL. finished_at
                   -- stays on :now because the retention window is a rule tests must be able to steer.
                   updated_at = now(),
                   finished_at = :now,
                   failure_reason = :reason,
                   progress_percent = CASE WHEN :status = 'SUCCEEDED' THEN 100 ELSE progress_percent END,
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE task_id = :id
               AND phase NOT IN ('SUCCEEDED', 'FAILED')
               -- Same guard as DownloadTaskRepository.SAVE_SQL. A cancel clears the lease and a retry
               -- reopens the row with none, so a step that was mid-flight when its song was cancelled
               -- must not land its stale outcome on the fresh attempt.
               AND lease_owner = :owner
            """;

    private final R2dbcEntityTemplate entityTemplate;
    private final DownloadTaskRepository repository;
    private final SlskdService slskdService;
    private final LibraryOrganiser organiser;
    private final AlbumSearchStep albumSearches;

    public DownloadService(R2dbcEntityTemplate entityTemplate, DownloadTaskRepository repository,
                           SlskdService slskdService, LibraryOrganiser organiser, AlbumSearchStep albumSearches) {
        this.entityTemplate = entityTemplate;
        this.repository = repository;
        this.slskdService = slskdService;
        this.organiser = organiser;
        this.albumSearches = albumSearches;
    }

    /**
     * Records the request and nothing else — no name, no track list, no provider call. What the id
     * resolves to is fetched by the loop at admission, so a request costs one INSERT however large
     * the collection behind it turns out to be, and the 202 is never behind a YouTube round trip.
     */
    public Mono<Download> requestDownload(String youtubeId, DownloadType type) {
        Download download = Download.builder()
                .downloadId(UUID.randomUUID())
                .youtubeId(youtubeId)
                .downloadType(type)
                .status(DownloadStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        // insert() forces an INSERT; save() would treat the pre-set @Id as an UPDATE.
        return entityTemplate.insert(download);
    }

    /**
     * Marks one song's task row terminal. Idempotent, and owner-checked: a call for an
     * already-terminal task, or from a caller that does not hold the row's lease, updates nothing
     * and returns 0. The download's own status follows from
     * {@link DownloadTaskRepository#concludeDownloads()} at the end of the pass.
     */
    public Mono<Long> finishTask(UUID taskId, DownloadStatus status,
                                 DownloadFailureCode failureCode, Instant now, String owner) {
        DatabaseClient.GenericExecuteSpec spec = entityTemplate.getDatabaseClient()
                .sql(FINISH_TASK_SQL)
                .bind("status", status.name())
                .bind("id", taskId)
                .bind("now", now)
                .bind("owner", owner);
        // Stored by NAME, not prose: the client words it, so copy changes never touch this table.
        spec = failureCode == null
                ? spec.bindNull("reason", String.class)
                : spec.bind("reason", failureCode.name());
        return spec.fetch()
                .rowsUpdated()
                .doOnError(error -> log.error("Could not finish task {} as {}",
                        taskId, status, error));
    }

    /**
     * Cancels a whole download ({@code taskId} null) or one of its songs. Three statements, in an
     * order that matters:
     * <ol>
     *   <li>Whole download only: fail it as unadmitted if it is still PENDING (guarded on that status,
     *       so admission and cancel serialise on the row: whichever commits first wins).</li>
     *   <li>Cancel every unfinished song row; for each, best-effort stop its slskd transfer and remove
     *       partial files. Fire-and-forget: the row is cancelled whether or not slskd hears this.</li>
     *   <li>Derive the download's status NOW rather than at the end of the next pass, so the response
     *       body is never the two-second window in which every song is finished but the download is
     *       still IN_PROGRESS (which the feed renders as QUEUED). Idempotent; runs even when nothing
     *       was cancelled, because the step that finished the last song may have beaten us to it.</li>
     * </ol>
     * Not one CTE: a single statement sees one snapshot, so when admission wins the row lock it could
     * not see the song rows admission just committed and would report "nothing to cancel".
     *
     * @return rows cancelled: the unadmitted download counts as one; 0 means nothing was left to cancel
     */
    public Mono<Long> cancel(UUID downloadId, UUID taskId, Instant now) {
        Mono<Long> unadmitted = taskId == null
                ? repository.failUnadmitted(downloadId, DownloadFailureCode.CANCELLED, now)
                : Mono.just(0L);
        return unadmitted
                .flatMap(rows -> rows > 0
                        ? Mono.just(rows)
                        : repository.cancelTasks(downloadId, taskId, now)
                                .doOnNext(this::stopInSlskd)
                                .count())
                .doOnNext(rows -> {
                    if (rows > 0) log.info("Cancelled {} song(s) of download {}", rows, downloadId);
                })
                .flatMap(rows -> repository.concludeDownloads().thenReturn(rows));
    }

    /**
     * Retries a finished download, or one song of it when {@code taskId} is given. Songs that failed
     * or were cancelled start again; songs with a file are left alone. A download that never got songs
     * is re-queued for admission instead -- only for a whole retry: a download with no songs has no
     * song to retry, and a bogus taskId must not re-queue it. 0 means nothing to retry: still running
     * (whole retry), fully downloaded, the song is not FAILED, or a concurrent retry got there first.
     * A whole retry of an album looks for one sharer with every missing song again first, its songs
     * held for that search as a fresh album's are ({@link AlbumSearchStep#holdUntil}).
     */
    public Mono<Long> retry(UUID downloadId, UUID taskId, Instant now) {
        return repository.retry(downloadId, taskId, now, albumSearches.holdUntil(now))
                .flatMap(rows -> rows > 0 || taskId != null ? Mono.just(rows) : repository.readmit(downloadId))
                .doOnNext(rows -> {
                    if (rows > 0) log.info("Retrying {} song(s) of download {}", rows, downloadId);
                });
    }

    // ---- manual pick: the lists a person chooses from -----------------------------------------------

    /** The album picker was asked about a download that is not an album; the controller answers 409. */
    public static final class NotAnAlbumException extends RuntimeException {
        NotAnAlbumException(UUID downloadId) {
            super("Download " + downloadId + " is not an album");
        }
    }

    /**
     * Every file one song's search found, best first, for a person to choose from. The song's own
     * remembered list when it has one; else, for an album's song that got its file from the album
     * search and never searched on its own, one entry per remembered folder that holds it. Empty for an
     * unknown download or song.
     */
    public Mono<TaskCandidatesView> candidates(UUID downloadId, UUID taskId) {
        return repository.cachedSearch(downloadId, taskId).flatMap(song -> listFor(downloadId, taskId, song)
                .map(files -> files.isEmpty()
                        ? view(song, files, statusOf(song), reasonOf(song))
                        : view(song, files, PickListStatus.READY, null)));
    }

    /** The song's own remembered files, else one file per remembered album folder that holds the song. */
    private Mono<List<DownloadCandidate>> listFor(UUID downloadId, UUID taskId, DownloadTaskRepository.CachedSearch song) {
        if (!song.files().isEmpty()) {
            return Mono.just(song.files());
        }
        return repository.cachedAlbumSearch(downloadId)
                .map(album -> album.holding(taskId).stream().map(folder -> folder.files().get(taskId)).toList())
                .defaultIfEmpty(List.of());
    }

    private static TaskCandidatesView view(DownloadTaskRepository.CachedSearch song, List<DownloadCandidate> files,
                                           PickListStatus status, String reason) {
        List<String> wordings = SearchQueryTiers.of(song.songName());
        DownloadCandidate current = song.current();
        return new TaskCandidatesView(song.taskId(), status, reason,
                wordings.get(Math.clamp(song.searchTier(), 0, wordings.size() - 1)), song.searchId(), song.searchedAt(),
                ActiveDownloadRepository.toSongStage(song.phase()), TaskCandidatesView.Current.of(current),
                files.stream().map(file -> TaskCandidatesView.Candidate.of(file, current)).toList());
    }

    private static PickListStatus statusOf(DownloadTaskRepository.CachedSearch song) {
        return "SEARCH_INIT".equals(song.phase()) || "SEARCH_POLL".equals(song.phase())
                ? PickListStatus.SEARCHING : PickListStatus.NONE;
    }

    /** A search that ended before completing: the row's failure code says so, and no file was ever chosen. */
    private static final List<String> SEARCH_NEVER_COMPLETED = List.of(
            DownloadFailureCode.SEARCH_FAILED.name(), DownloadFailureCode.SOULSEEK_OFFLINE.name(),
            DownloadFailureCode.TIMED_OUT.name(), DownloadFailureCode.CANCELLED.name());

    /** The never-completed codes reported under their own name; the rest read {@code SEARCH_FAILED}. */
    private static final List<String> OWN_WORD = List.of(
            DownloadFailureCode.SOULSEEK_OFFLINE.name(), DownloadFailureCode.CANCELLED.name());

    /**
     * Why a song past searching has no list, from what the row already records; see
     * {@link TaskCandidatesView#reason()}. A song whose file came from the album search's folder or a
     * person, with no search id of its own, never searched (a file from its own search with no id is a
     * pre-V15 row that kept a first wording's files when the next was refused: it did search); a failed
     * row with no completed search reports the failure code itself ({@code SOULSEEK_OFFLINE} and
     * {@code CANCELLED} under their own names, so a song cancelled while searching matches the album
     * view); a stamped row searched and found nothing; anything else searched before lists were kept.
     */
    private static String reasonOf(DownloadTaskRepository.CachedSearch song) {
        if (statusOf(song) != PickListStatus.NONE) {
            return null;
        }
        if (song.libraryPath() != null && song.slskdFilename() == null) {
            return "ALREADY_IN_LIBRARY";
        }
        DownloadCandidate current = song.current();
        if (song.searchId() == null && current != null && (DownloadCandidate.ALBUM_FOLDER.equals(current.source())
                || DownloadCandidate.MANUAL.equals(current.source()))) {
            return "NO_OWN_SEARCH";
        }
        if (song.searchedAt() == null && current == null && song.failureReason() != null
                && SEARCH_NEVER_COMPLETED.contains(song.failureReason())) {
            return OWN_WORD.contains(song.failureReason()) ? song.failureReason() : "SEARCH_FAILED";
        }
        return song.searchedAt() != null ? "NO_RESULTS" : "BEFORE_CACHE";
    }

    /**
     * The folders an album's search judged, best first, each with the file it holds for every song, and
     * which folder the songs currently download from. Empty for an unknown download; an error for one
     * that is not an album (playlists get no album picker).
     */
    public Mono<AlbumCandidatesView> albumCandidates(UUID downloadId) {
        return repository.cachedAlbumSearch(downloadId).flatMap(album -> {
            if (album.type() != DownloadType.ALBUM) {
                return Mono.error(new NotAnAlbumException(downloadId));
            }
            return repository.songPicks(downloadId).collectList().map(songs -> albumView(downloadId, album, songs));
        });
    }

    private static AlbumCandidatesView albumView(UUID downloadId, DownloadTaskRepository.CachedAlbumSearch album,
                                                 List<DownloadTaskRepository.SongPick> songs) {
        Map<UUID, DownloadTaskRepository.SongPick> byTask = songs.stream()
                .collect(Collectors.toMap(DownloadTaskRepository.SongPick::taskId, song -> song));
        List<AlbumCandidatesView.Folder> folders = album.folders().stream().map(folder -> {
            List<AlbumCandidatesView.File> files = folder.files().entrySet().stream()
                    .map(entry -> {
                        DownloadTaskRepository.SongPick song = byTask.get(entry.getKey());
                        DownloadCandidate file = entry.getValue();
                        return new AlbumCandidatesView.File(song == null ? null : song.position(), entry.getKey(),
                                LibraryOrganiser.baseName(file.filename()), song == null ? null : song.title(),
                                file.size(), file.bitRate(), file.length(),
                                com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor
                                        .format(file.filename(), file.extension()));
                    })
                    .sorted(Comparator.comparing(AlbumCandidatesView.File::index,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            int songsCurrent = (int) songs.stream()
                    .filter(song -> folder.files().values().stream()
                            .anyMatch(file -> TaskCandidatesView.sameFile(file, song.current())))
                    .count();
            return new AlbumCandidatesView.Folder(folder.username(), folder.path(), files.size(),
                    folder.files().values().stream().mapToLong(DownloadCandidate::size).sum(), folder.uploadSpeed(),
                    folder.hasFreeUploadSlot(), folder.queueLength(), folder.extras(), songsCurrent, songsCurrent > 0,
                    folder.judged() == null || folder.judged(), files);
        }).toList();
        String query = album.title() == null ? null : AlbumSearch.builder().title(album.title()).artists(album.artists())
                .searchTier(album.searchTier() == null ? 0 : album.searchTier()).build().searchQuery();
        PickListStatus status;
        String reason = null;
        if (album.phase() == null) {
            status = PickListStatus.NONE;
            reason = "NO_ALBUM_SEARCH";
        } else if (!folders.isEmpty()) {
            status = PickListStatus.READY;
        } else if (!"DONE".equals(album.phase())) {
            status = PickListStatus.SEARCHING;
        } else {
            // The outcome as it is: a refused search is not "nobody shared enough" (09-10-2026). A folder
            // found with nothing remembered is a row from before lists were kept.
            status = PickListStatus.NONE;
            reason = album.outcome() == null || List.of("WHOLE_FOLDER", "PART_FOLDER").contains(album.outcome())
                    ? "BEFORE_CACHE" : album.outcome();
        }
        Instant searchedAt = album.foldersAt() != null ? album.foldersAt() : album.finishedAt();
        return new AlbumCandidatesView(downloadId, status, reason, query, album.searchId(), searchedAt, songs.size(),
                folders);
    }

    // ---- manual pick: re-pointing songs at a chosen file ----------------------------------------------

    /**
     * A person chose the file one song downloads from. The file must be in the song's own list
     * ({@link #candidates}); the song is then reset in place to the enqueue step with that file as its
     * only candidate (PICK_SQL), its old transfer cancelled in slskd and its partial files removed, and
     * a finished download reopened. The ordinary loop takes it from there.
     *
     * @return empty for an unknown download or song; 0 when nothing changed (the file is not in the
     *         list -- a stale dialog -- or the song already succeeded); 1 when the song was re-pointed
     */
    public Mono<Long> pick(UUID downloadId, UUID taskId, String username, String filename, Instant now) {
        return repository.cachedSearch(downloadId, taskId)
                .flatMap(song -> listFor(downloadId, taskId, song))
                .flatMap(files -> files.stream()
                        .filter(file -> file.username().equals(username) && file.filename().equals(filename))
                        .findFirst()
                        .map(chosen -> repoint(downloadId, Map.of(taskId, chosen.asManual()), now))
                        .orElse(Mono.just(0L)));
    }

    /**
     * A person chose the folder an album downloads from: every song the folder holds that has not
     * succeeded is re-pointed at that folder's file for it, as {@link #pick} does one song. Songs the
     * folder lacks, and finished songs, are left exactly as they are.
     *
     * @return empty for an unknown download; an error for one that is not an album; 0 when nothing
     *         changed (the folder is not remembered, or every song it holds already succeeded); else the
     *         number of songs re-pointed
     */
    public Mono<Long> albumPick(UUID downloadId, String username, String folder, Instant now) {
        return repository.cachedAlbumSearch(downloadId).flatMap(album -> {
            if (album.type() != DownloadType.ALBUM) {
                return Mono.error(new NotAnAlbumException(downloadId));
            }
            return album.folders().stream()
                    .filter(f -> f.username().equals(username) && f.path().equals(folder))
                    .findFirst()
                    .map(f -> repoint(downloadId, f.files().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().asManual())), now))
                    .orElse(Mono.just(0L));
        });
    }

    /** Re-points the songs, then stops what each was doing before -- the same two steps as cancel. */
    private Mono<Long> repoint(UUID downloadId, Map<UUID, DownloadCandidate> picks, Instant now) {
        return repository.pick(downloadId, picks, now)
                .doOnNext(this::stopInSlskd)
                .count()
                .doOnNext(rows -> {
                    if (rows > 0) log.info("Re-pointed {} song(s) of download {} at a file a person chose", rows, downloadId);
                });
    }

    /** Same shape as DownloadStepExecutor.cancelIfAbandoned: the decision is written; slskd is told after, best effort. */
    private void stopInSlskd(DownloadTask task) {
        if (task.slskdTransferId() != null) {
            Mono.defer(() -> slskdService.cancelDownload(task.slskdUsername(), task.slskdTransferId()))
                    .subscribe(ignored -> { },
                            error -> log.warn("Could not cancel transfer {} from '{}' for song {}; it stays in slskd's list",
                                    task.slskdTransferId(), task.slskdUsername(), task.taskId(), error),
                            () -> log.info("Cancelled transfer {} from '{}' for song {}",
                                    task.slskdTransferId(), task.slskdUsername(), task.taskId()));
        }
        organiser.deletePartials(task).subscribe();
    }
}
