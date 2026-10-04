package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All {@code download_tasks} SQL. Raw statements rather than Spring Data derived queries because
 * every one of them needs something the mapping layer cannot express: {@code FOR UPDATE SKIP LOCKED},
 * {@code RETURNING}, {@code unnest}, or a data-modifying CTE.
 */
@Slf4j
@Repository
public class DownloadTaskRepository {

    /**
     * Downloads that are ready to be admitted. A SELECT only — it used to also create the task rows
     * and flip the status in the same statement, and it cannot any more: what task rows a download
     * needs is now a question only ytmusic-adapter can answer, so an HTTP call has to happen between
     * finding the row and writing its tasks. {@link #createTasks} is the other half.
     *
     * <p>{@code PENDING} alone is enough, where the old statement had to cover {@code IN_PROGRESS}
     * too. That breadth existed to make its own two halves crash-safe; now the flip to
     * {@code IN_PROGRESS} happens in the same statement as the task inserts, so an
     * {@code IN_PROGRESS} download always has task rows. A crash between this select and
     * {@code createTasks} leaves the row exactly as it was, and the next pass picks it up — which is
     * what the loop is for.
     *
     * <p>The {@code NOT EXISTS} is still here even though {@code PENDING} implies it, as the thing
     * that makes a repeat gather (two passes overlapping, a retried metadata call) idempotent rather
     * than duplicating every song.
     */
    private static final String ADMISSIBLE_SQL = """
            SELECT d.download_id, d.youtube_id, d.download_type, d.status, d.failure_reason,
                   d.created_at, d.admitted_at, d.finished_at
              FROM downloads d
             WHERE d.status = 'PENDING'
               AND NOT EXISTS (SELECT 1 FROM download_tasks t
                                WHERE t.download_id = d.download_id)
             ORDER BY d.created_at
               FOR UPDATE SKIP LOCKED
             LIMIT :limit
            """;

    /**
     * Creates every task row for one download and admits it, in one statement. One row per song.
     *
     * <p>The status flip comes FIRST and the insert depends on it. The {@code UPDATE} takes the row
     * lock, so a cancel (or admission failure) that lands in the gap between selecting the download
     * and running this statement wins or loses cleanly: under READ COMMITTED an {@code UPDATE} that
     * waited on the row re-checks {@code status = 'PENDING'} against the committed version, finds it
     * false, and the insert then has nothing to depend on. The old order (insert first, then flip)
     * left a finished download with live song rows the loop would search for.
     *
     * <p>{@code unnest} of parallel arrays rather than a multi-row VALUES list, because the song
     * count is only known at runtime. {@code WITH ORDINALITY} numbers the songs in the provider's order.
     * {@code song_name} is the Soulseek query wording, not a display title; see V6. {@code track_title},
     * {@code track_number} and {@code duration_seconds} are the album or playlist row's own values
     * (V12): one id can sit on two rows of one album, so {@code media_items} cannot hold them.
     *
     * <p>A song with a {@code library_path} is one the library already has ({@link #FILED_COPIES_SQL}):
     * it is created SUCCEEDED and finished, pointing at that file. Terminal from birth, so no claim,
     * lease or later step can race it, and nothing downloads it.
     *
     * <p>An album download (P5) also gets its {@code album_searches} row here, in the same statement, and
     * its songs are written held: due at {@code :holdUntil} rather than now, so they wait for the album
     * search instead of starting their own. A crash cannot leave one without the other. A song the
     * library already has is not held and the album search never plans it (it is not SEARCH_INIT); when
     * every song is already there the search ends without searching.
     *
     * @return task rows created: N for an admitted N-song download, 0 when it was no longer PENDING
     *         or already had songs
     */
    private static final String CREATE_TASKS_SQL = """
            WITH admitted AS (
                UPDATE downloads
                   SET status = 'IN_PROGRESS',
                       admitted_at = :now
                 WHERE download_id = :downloadId
                   AND status = 'PENDING'
                   AND NOT EXISTS (SELECT 1 FROM download_tasks t
                                    WHERE t.download_id = :downloadId)
                RETURNING download_id
            ), album AS (
                INSERT INTO album_searches (download_id, phase, phase_entered_at, next_attempt_at)
                SELECT download_id, 'SEARCH_INIT', :now, :now FROM admitted WHERE :albumSearch
            )
            INSERT INTO download_tasks
                (task_id, download_id, youtube_id, song_name, position, track_title, track_number,
                 duration_seconds, library_path, phase, phase_entered_at, next_attempt_at, finished_at,
                 progress_percent)
            SELECT gen_random_uuid(), :downloadId, s.youtube_id, s.song_name, s.position,
                   s.track_title, s.track_number, s.duration_seconds, s.library_path,
                   CASE WHEN s.library_path IS NULL THEN 'SEARCH_INIT' ELSE 'SUCCEEDED' END, :now,
                   CASE WHEN s.library_path IS NULL THEN :holdUntil ELSE :now END,
                   CASE WHEN s.library_path IS NULL THEN NULL ELSE :now END,
                   CASE WHEN s.library_path IS NULL THEN 0 ELSE 100 END
              FROM unnest(:youtubeIds::text[], :songNames::text[], :trackTitles::text[],
                          :trackNumbers::int[], :durations::int[], :libraryPaths::text[])
                   WITH ORDINALITY AS s(youtube_id, song_name, track_title, track_number,
                                        duration_seconds, library_path, position)
             WHERE EXISTS (SELECT 1 FROM admitted)
            """;

    /**
     * Every song in the library, with what it is filed as: its row's own title (for a row from before
     * V12 its song's title, unless it was an album track, where one id can carry two titles), its album
     * and track number, and whether its pick was EXACT (a live take or remix fetched because nothing
     * better was shared is not). The album is an album download's own id and YouTube number, or a song
     * or playlist track's {@code song_albums} answer -- only one that was there within the organiser's
     * wait after the song finished, so the file was filed in that album's folder: a later answer (a
     * lookup that ran late, a week-old "none" asked again) would claim a file that sits in its own
     * folder. A row from before V12 has no track number, so it never matches by album. Shared by both
     * "already have it" checks below so they agree on what "have" means. Whether the file is still on
     * disk is the caller's to check.
     */
    private static final String FILED_SONGS = """
            SELECT f.youtube_id, f.library_path, f.finished_at,
                   COALESCE(f.candidates::jsonb -> f.candidate_index ->> 'grade' = 'EXACT', false) AS exact,
                   COALESCE(f.track_title, CASE WHEN fd.download_type <> 'ALBUM' THEN fm.title END) AS title,
                   CASE WHEN fd.download_type = 'ALBUM' THEN fd.youtube_id ELSE fa.album_id END AS album_id,
                   CASE WHEN fd.download_type = 'ALBUM' THEN f.track_number ELSE fa.track_number END AS track_number
              FROM download_tasks f
              JOIN downloads fd ON fd.download_id = f.download_id
              LEFT JOIN media_items fm ON fm.youtube_id = f.youtube_id
              LEFT JOIN song_albums fa ON fa.youtube_id = f.youtube_id AND fd.download_type <> 'ALBUM'
                    AND fa.resolved_at <= f.finished_at + interval '%d seconds'
             WHERE f.phase = 'SUCCEEDED'
               AND f.library_path IS NOT NULL""".formatted(LibraryOrganiser.ALBUM_LOOKUP_GRACE.toSeconds());

    /**
     * The library's EXACT copies of the songs a download is about to be admitted with, newest first per
     * song ({@code position} is the song's 1-based place in the list given). The same album and track
     * number, for any download: an album download's is its own album and YouTube's number, a song's or
     * playlist track's its {@code song_albums} answer when it already has one. Or, for anything but an
     * album download, the same YouTube id with the same title. An album download never matches by id:
     * a Deluxe edition shares the plain album's ids for its first tracks, and taking those files would
     * file the Deluxe half in the plain album's folder.
     *
     * <p>ponytail: a scan of the filed history per admission (no index on youtube_id); add one with a
     * migration if an install's history reaches hundreds of thousands of songs.
     */
    private static final String FILED_COPIES_SQL = """
            WITH wanted AS (
                SELECT w.position, w.youtube_id, w.title,
                       CASE WHEN :album THEN :albumId::text ELSE a.album_id END AS album_id,
                       CASE WHEN :album THEN w.track_number ELSE a.track_number END AS track_number
                  FROM unnest(:youtubeIds::text[], :titles::text[], :trackNumbers::int[])
                       WITH ORDINALITY AS w(youtube_id, title, track_number, position)
                  LEFT JOIN song_albums a ON a.youtube_id = w.youtube_id AND NOT :album
            )
            SELECT w.position, f.library_path
              FROM wanted w
              JOIN (%s) f
                ON f.exact
               AND ((f.album_id = w.album_id AND f.track_number = w.track_number)
                    OR (NOT :album AND f.youtube_id = w.youtube_id AND lower(f.title) = lower(w.title)))
             ORDER BY w.position, f.finished_at DESC
            """.formatted(FILED_SONGS);

    /**
     * Writes what ytmusic-adapter said an id is — the download's own id and, for a collection, every
     * track's — so the feed and the per-song view have a title and a picture to show. One statement
     * for N rows: the whole batch goes over as one JSON document and {@code jsonb_to_recordset}
     * turns it back into rows, arrays included, which is what {@code unnest} of parallel arrays
     * cannot do for a per-row {@code text[]}. The quoted column names are {@link MediaItem}'s
     * component names verbatim, so Jackson's default output is the input.
     *
     * <p>Upsert, not insert: the same song can arrive as a single request and inside two albums.
     * A later answer refreshes the row, but never with a null over a value — the adapter's answers
     * are not uniformly complete, and a playlist listing a track knows less about it than a direct
     * lookup does.
     */
    private static final String UPSERT_MEDIA_SQL = """
            INSERT INTO media_items (youtube_id, title, artists, artist_ids, image_url, duration_seconds,
                                     track_count, year, album_type)
            SELECT x."youtubeId", x.title, COALESCE(x.artists, '{}'), COALESCE(x."artistIds", '{}'),
                   x."imageUrl", x."durationSeconds", x."trackCount", x.year, x."albumType"
              FROM jsonb_to_recordset(:items::jsonb)
                   AS x("youtubeId" text, title text, artists text[], "artistIds" text[],
                        "imageUrl" text, "durationSeconds" int, "trackCount" int, year int,
                        "albumType" text)
             WHERE x."youtubeId" IS NOT NULL
            ON CONFLICT (youtube_id) DO UPDATE
               SET title            = COALESCE(EXCLUDED.title, media_items.title),
                   artists          = CASE WHEN EXCLUDED.artists = '{}' THEN media_items.artists
                                           ELSE EXCLUDED.artists END,
                   -- Keyed on the NAMES, not the ids: ids belong with the names they came with, so
                   -- they follow them -- kept together, replaced together.
                   artist_ids       = CASE WHEN EXCLUDED.artists = '{}' THEN media_items.artist_ids
                                           ELSE EXCLUDED.artist_ids END,
                   image_url        = COALESCE(EXCLUDED.image_url, media_items.image_url),
                   duration_seconds = COALESCE(EXCLUDED.duration_seconds, media_items.duration_seconds),
                   track_count      = COALESCE(EXCLUDED.track_count, media_items.track_count),
                   year             = COALESCE(EXCLUDED.year, media_items.year),
                   album_type       = COALESCE(EXCLUDED.album_type, media_items.album_type),
                   fetched_at       = now()
            """;

    /**
     * Claims due, unleased, non-terminal tasks. Two branches because the two caps have different
     * shapes: DOWNLOAD_INIT rows are excluded outright when no transfer slot is free (a yes/no gate,
     * fine at a cap of 20), while SEARCH_INIT rows are claimed up to the NUMBER of free search slots.
     * A yes/no gate for searches would let one pass claim {@code batch-size} of them the moment the
     * count dipped under the cap -- ten searches in flight against a cap of two, the very flood the
     * cap exists to stop. Two CTEs rather than one UNION because Postgres refuses
     * {@code FOR UPDATE} inside a set operation; each CTE locks its own rows the same way the single
     * select used to.
     */
    private static final String CLAIM_DUE_SQL = """
            WITH polls AS (
                SELECT task_id FROM download_tasks
                 WHERE next_attempt_at <= :now
                   AND phase NOT IN ('SUCCEEDED', 'FAILED', 'SEARCH_INIT')
                   AND (:transferSlotsFree OR phase <> 'DOWNLOAD_INIT')
                   AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                 ORDER BY next_attempt_at
                   FOR UPDATE SKIP LOCKED
                 LIMIT :limit
            ), searches AS (
                SELECT task_id FROM download_tasks
                 WHERE next_attempt_at <= :now
                   AND phase = 'SEARCH_INIT'
                   AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                 ORDER BY next_attempt_at
                   FOR UPDATE SKIP LOCKED
                 LIMIT :searchSlots
            )
            UPDATE download_tasks
               SET lease_owner = :owner,
                   lease_expires_at = :leaseExpiresAt
             WHERE task_id IN (SELECT task_id FROM polls UNION ALL SELECT task_id FROM searches)
            RETURNING task_id, download_id, youtube_id, song_name, track_title, track_number,
                      duration_seconds, phase, phase_entered_at, next_attempt_at, search_id,
                      search_tier, candidates, candidate_index, retry_index, slskd_username,
                      slskd_filename, slskd_transfer_id, last_error, progress_percent
            """;

    // Writes every field (DownloadTask is the complete state) and clears the lease. Guarded on the
    // row still being non-terminal and still held by the caller's lease: a read-then-write would
    // have the same race this closes, so it has to be this one statement.
    private static final String SAVE_SQL = """
            UPDATE download_tasks
               SET phase = :phase,
                   phase_entered_at = :phaseEnteredAt,
                   next_attempt_at = :nextAttemptAt,
                   search_id = :searchId,
                   search_tier = :searchTier,
                   candidates = :candidates,
                   candidate_index = :candidateIndex,
                   retry_index = :retryIndex,
                   slskd_username = :slskdUsername,
                   slskd_filename = :slskdFilename,
                   slskd_transfer_id = :slskdTransferId,
                   last_error = :lastError,
                   progress_percent = :progressPercent,
                   -- Wall clock, not the injected Clock, and deliberately: this is bookkeeping about
                   -- when the row was written, the same thing downloads.created_at uses now() for. It
                   -- also has to agree with FINISH_TASK_SQL, since the feed sorts on it -- mixing a
                   -- test clock here with now() there would order rows inconsistently. Anything the
                   -- state machine actually reasons about (finished_at, next_attempt_at) stays on the
                   -- injected clock.
                   updated_at = now(),
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE task_id = :id
               AND phase NOT IN ('SUCCEEDED', 'FAILED')
               AND lease_owner = :owner
            """;

    /**
     * Gives a download its terminal status once every one of its tasks is terminal, and is the whole
     * reason the per-task terminal write no longer touches {@code downloads}.
     *
     * <p>Two tasks of the same download finishing at the same time cannot both conclude it, and —
     * the failure that matters — cannot both decline to. Each would otherwise read a snapshot in
     * which the other is still running, so neither writes, and the download stays
     * {@code IN_PROGRESS} forever with every task finished. Making the per-task statement bigger
     * does not fix that; a data-modifying CTE's writes are not visible to the rest of its own
     * statement. So no task decides. This runs once at the end of every pass and asks the question
     * of the whole table, which is the level-triggered rule the rest of this loop already follows:
     * a missed conclusion costs one interval, not a download.
     *
     * <p>{@code d.status = 'IN_PROGRESS'} makes it idempotent — it writes each download exactly
     * once and then stops matching. {@code PARTIAL_SUCCESS} needs one of each outcome, so it is
     * unreachable for a single-song download.
     */
    private static final String CONCLUDE_SQL = """
            UPDATE downloads d
               SET status = agg.status,
                   -- A download finished when its last song did; no clock needed here.
                   finished_at = agg.finished_at
              FROM (SELECT t.download_id,
                           CASE WHEN bool_or(t.phase = 'SUCCEEDED') AND bool_or(t.phase = 'FAILED')
                                     THEN 'PARTIAL_SUCCESS'
                                WHEN bool_or(t.phase = 'SUCCEEDED') THEN 'SUCCEEDED'
                                ELSE 'FAILED' END AS status,
                           MAX(t.finished_at) AS finished_at
                      FROM download_tasks t
                     GROUP BY t.download_id
                    HAVING bool_and(t.phase IN ('SUCCEEDED', 'FAILED'))) agg
             WHERE d.download_id = agg.download_id
               AND d.status = 'IN_PROGRESS'
            """;

    /**
     * Fails a download that has no task rows and never will. Admission is the only place this
     * happens: the metadata call is the one step that can fail before a single task row exists, so
     * it is also the only failure the per-task terminal write cannot record.
     */
    private static final String FAIL_UNADMITTED_SQL = """
            UPDATE downloads
               SET status = 'FAILED',
                   failure_reason = :reason,
                   finished_at = :now
             WHERE download_id = :id
               AND status = 'PENDING'
            """;

    /**
     * Cancels every unfinished song of a download, or one song when {@code :taskId} is given. The SET
     * is {@link DownloadService#FINISH_TASK_SQL}'s with the reason fixed to CANCELLED; the guard is the
     * same "still unfinished" test, and deliberately NOT a lease test: a step that is mid-flight will
     * find the row finished when it comes back and write nothing (SAVE_SQL/FINISH_TASK_SQL guards).
     * RETURNING hands back what the caller needs to stop the transfer in slskd and remove partial
     * files. Bind the whole-download case with {@code bindNull("taskId", UUID.class)}. Cancelling the
     * whole download also ends its album search (P5), so a search still running cannot hand files to
     * songs a later retry reopens; cancelling one song leaves it running for the others.
     */
    private static final String CANCEL_SQL = """
            WITH album AS (
                UPDATE album_searches
                   SET phase = 'DONE',
                       outcome = 'CANCELLED',
                       finished_at = :now,
                       lease_owner = NULL,
                       lease_expires_at = NULL
                 WHERE download_id = :id
                   AND :taskId::uuid IS NULL
                   AND phase <> 'DONE'
            )
            UPDATE download_tasks
               SET phase = 'FAILED',
                   failure_reason = 'CANCELLED',
                   phase_entered_at = :now,
                   finished_at = :now,
                   updated_at = now(),
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE download_id = :id
               AND phase NOT IN ('SUCCEEDED', 'FAILED')
               AND (:taskId::uuid IS NULL OR task_id = :taskId)
            RETURNING task_id, download_id, candidates, candidate_index,
                      slskd_username, slskd_filename, slskd_transfer_id
            """;

    /**
     * Retries a finished download: every FAILED song (cancelled ones included -- "retry" means "the
     * ones I did not get") goes back to the start of its pipeline, in place, and the download reopens.
     * One statement, so a double-click's second request finds no FAILED rows left, the reset CTE is
     * empty, and the outer UPDATE matches nothing: rows updated is 0 or 1 and IS the idempotence.
     *
     * <p>The previous attempt's peers and error are not kept on the row; apply() logged them when the
     * song failed. A second row per song would break every COUNT the cards read.
     *
     * <p>{@code failure_reason = NULL} on downloads: the feed prefers the download's own reason over
     * its songs', so a stale one would outrank the fresh rows. {@code organised_at = NULL}: the playlist
     * file is rewritten whole once the retried songs are filed; songs already filed keep library_path.
     */
    private static final String RETRY_SQL = """
            WITH reset AS (
                UPDATE download_tasks
                   SET phase = 'SEARCH_INIT',
                       phase_entered_at = :now,
                       next_attempt_at = :now,
                       finished_at = NULL,
                       failure_reason = NULL,
                       last_error = NULL,
                       search_id = NULL,
                       search_tier = 0,
                       candidates = '[]',
                       candidate_index = 0,
                       retry_index = 0,
                       slskd_username = NULL,
                       slskd_filename = NULL,
                       slskd_transfer_id = NULL,
                       progress_percent = 0,
                       updated_at = now(),
                       lease_owner = NULL,
                       lease_expires_at = NULL
                 WHERE download_id = :id
                   AND phase = 'FAILED'
                   AND EXISTS (SELECT 1 FROM downloads
                                WHERE download_id = :id
                                  AND status IN ('FAILED', 'PARTIAL_SUCCESS'))
                RETURNING task_id
            )
            UPDATE downloads
               SET status = 'IN_PROGRESS',
                   failure_reason = NULL,
                   finished_at = NULL,
                   organised_at = NULL
             WHERE download_id = :id
               AND status IN ('FAILED', 'PARTIAL_SUCCESS')
               AND EXISTS (SELECT 1 FROM reset)
            """;

    /** A download that failed before it had any songs (bad id, or cancelled while queued) goes back to PENDING; admission fetches the track list again. */
    private static final String READMIT_SQL = """
            UPDATE downloads
               SET status = 'PENDING',
                   failure_reason = NULL,
                   finished_at = NULL
             WHERE download_id = :id
               AND status = 'FAILED'
               AND NOT EXISTS (SELECT 1 FROM download_tasks WHERE download_id = :id)
            """;

    // Counts DOWNLOADS in flight, not tasks, so one large collection can't lock out admission.
    private static final String COUNT_ACTIVE_DOWNLOADS_SQL = """
            SELECT count(*) AS total FROM downloads WHERE status = 'IN_PROGRESS'
            """;

    // Only DOWNLOAD_POLL, the only phase with a real live transfer; counting DOWNLOAD_INIT too would
    // deadlock the gate, since CLAIM_DUE_SQL excludes DOWNLOAD_INIT once this count is maxed out. Rows
    // rather than a count: the same list says which sharer holds each of our transfers (P9, SharerLoad).
    private static final String TRANSFERS_IN_FLIGHT_SQL = """
            SELECT slskd_username, slskd_transfer_id FROM download_tasks
             WHERE phase = 'DOWNLOAD_POLL'
            """;

    // Same shape, same reasoning: SEARCH_POLL is the only phase with a search running in slskd.
    // Counting SEARCH_INIT too would close the search gate on rows the gate stops from ever leaving.
    // Album searches (P5) run on the same two slskd slots, so theirs count too.
    private static final String COUNT_ACTIVE_SEARCHES_SQL = """
            SELECT (SELECT count(*) FROM download_tasks WHERE phase = 'SEARCH_POLL')
                 + (SELECT count(*) FROM album_searches WHERE phase = 'SEARCH_POLL') AS total
            """;

    /**
     * Finished songs whose file has not been filed into the library yet, oldest first, with the names
     * their folders are built from: the song's own media row, its download's (the album's, for an
     * album track) and, for a song or playlist track, its trusted YouTube Music album's
     * ({@code song_albums}, written by {@link SongAlbumResolver}). {@code finished_at > :cutoff} is
     * what stops this from trawling every success in the install's history the day the organiser is
     * switched on, and is also the give-up rule: a file that has not appeared by then is left where
     * slskd put it and never looked for again.
     *
     * <p>A song or playlist track with no album answer yet waits until {@code :albumCutoff} (a short
     * grace after it finished), then is filed by its own name. The wait is in the WHERE, not applied
     * after the LIMIT, so waiting songs never fill the batch and hold back ones that are ready.
     *
     * <p>The {@code tag_} columns are what {@link SongTagger} writes into the file: the row's own title
     * (one id can sit on two rows of an album with two titles), and the album {@code r} the tags follow,
     * which is the download's own album for an album track and the trusted album for anything else.
     * No trusted album: no album, and the song's own picture as the cover.
     *
     * <p>{@code filed_copy}: the library's file (FILED_SONGS) of this same song, which the organiser
     * points the song at instead of filing a second copy. With an album (the one the tags follow) the
     * same album track: typically the music video's twin of a track an album download filed under its
     * audio id, or an album track a song request filed into the album's folder first. Without one, the
     * same YouTube id with the same title (the same single requested twice). The old copy counts when it
     * was an EXACT pick, or whatever it was when this one is not: a second stand-in never piles up next
     * to the first. EXACT copies first, then newest. {@code copy_key} names the copy so the organiser
     * files only one of two copies that are ready in the same pass; the other sees its file next pass.
     */
    private static final String TASKS_TO_ORGANISE_SQL = """
            SELECT t.task_id, t.slskd_filename, t.finished_at, d.download_type,
                   s.title AS song_title, s.artists AS song_artists,
                   c.title AS collection_title, c.artists AS collection_artists,
                   al.title AS album_title, al.artists AS album_artists,
                   COALESCE(t.track_title, s.title) AS tag_title,
                   r.title AS tag_album, r.artists AS tag_album_artists, r.year AS tag_year,
                   CASE WHEN d.download_type = 'ALBUM' THEN t.track_number ELSE a.track_number END AS tag_track,
                   r.track_count AS tag_track_total,
                   CASE WHEN r.youtube_id IS NULL THEN s.image_url ELSE r.image_url END AS tag_cover,
                   (SELECT f.library_path FROM (%s) f
                     WHERE (f.exact OR t.candidates::jsonb -> t.candidate_index ->> 'grade' IS DISTINCT FROM 'EXACT')
                       AND CASE WHEN k.album_id IS NOT NULL AND k.track_number IS NOT NULL
                                THEN f.album_id = k.album_id AND f.track_number = k.track_number
                                ELSE d.download_type <> 'ALBUM' AND f.youtube_id = t.youtube_id
                                     AND lower(f.title) = lower(COALESCE(t.track_title, s.title)) END
                     ORDER BY f.exact DESC, f.finished_at DESC LIMIT 1) AS filed_copy,
                   COALESCE(k.album_id || '#' || k.track_number, t.youtube_id, t.task_id::text) AS copy_key,
                   (t.candidates::jsonb -> t.candidate_index ->> 'size')::bigint AS pick_size
              FROM download_tasks t
              JOIN downloads d ON d.download_id = t.download_id
              LEFT JOIN media_items s ON s.youtube_id = t.youtube_id
              LEFT JOIN media_items c ON c.youtube_id = d.youtube_id
              LEFT JOIN song_albums a ON a.youtube_id = t.youtube_id AND d.download_type <> 'ALBUM'
              LEFT JOIN media_items al ON al.youtube_id = a.album_id
              LEFT JOIN media_items r
                     ON r.youtube_id = CASE WHEN d.download_type = 'ALBUM' THEN d.youtube_id ELSE a.album_id END
              CROSS JOIN LATERAL (SELECT CASE WHEN d.download_type = 'ALBUM' THEN d.youtube_id ELSE a.album_id END AS album_id,
                                         CASE WHEN d.download_type = 'ALBUM' THEN t.track_number
                                              ELSE a.track_number END AS track_number) k
             WHERE t.phase = 'SUCCEEDED'
               AND t.library_path IS NULL
               AND t.slskd_filename IS NOT NULL
               AND t.finished_at > :cutoff
               -- ponytail: any answer counts, including a week-old "none" the lookup is about to ask
               -- again; that song is then filed by its own name. Compare resolved_at if that matters.
               AND (d.download_type = 'ALBUM' OR a.youtube_id IS NOT NULL OR t.finished_at <= :albumCutoff)
             ORDER BY t.finished_at
             LIMIT :limit
            """.formatted(FILED_SONGS);

    /**
     * Songs and playlist tracks whose YouTube Music album is still to be looked up: no answer yet, or a
     * "none trusted" answer older than {@code :relookBefore}. Only rows still downloading, or finished
     * and not filed yet within the organiser's window -- the history is never trawled (its files are
     * filed already, and nothing re-files them). One row per YouTube id, oldest request first.
     */
    private static final String SONGS_TO_RESOLVE_SQL = """
            SELECT youtube_id, song_name, duration_seconds, download_type, download_youtube_id
              FROM (SELECT DISTINCT ON (t.youtube_id)
                           t.youtube_id, t.song_name, COALESCE(t.duration_seconds, s.duration_seconds) AS duration_seconds,
                           d.download_type, d.youtube_id AS download_youtube_id, d.created_at, t.position
                      FROM download_tasks t
                      JOIN downloads d ON d.download_id = t.download_id
                      LEFT JOIN media_items s ON s.youtube_id = t.youtube_id
                      LEFT JOIN song_albums a ON a.youtube_id = t.youtube_id
                     WHERE d.download_type <> 'ALBUM'
                       AND t.youtube_id IS NOT NULL
                       AND (a.youtube_id IS NULL OR (a.album_id IS NULL AND a.resolved_at < :relookBefore))
                       AND t.phase <> 'FAILED'
                       AND (t.phase <> 'SUCCEEDED' OR (t.library_path IS NULL AND t.finished_at > :cutoff))
                     ORDER BY t.youtube_id, d.created_at, t.position) due
             ORDER BY created_at, position
             LIMIT :limit
            """;

    /** One song's album answer. Upsert: a later answer (a re-look) replaces the old one. */
    private static final String SAVE_SONG_ALBUM_SQL = """
            INSERT INTO song_albums (youtube_id, album_id, track_number, resolved_at)
            VALUES (:youtubeId, :albumId, :trackNumber, :now)
            ON CONFLICT (youtube_id) DO UPDATE
               SET album_id = EXCLUDED.album_id,
                   track_number = EXCLUDED.track_number,
                   resolved_at = EXCLUDED.resolved_at
            """;

    private static final String SET_LIBRARY_PATH_SQL = """
            UPDATE download_tasks
               SET library_path = :path
             WHERE task_id = :id
               AND library_path IS NULL
            """;

    /**
     * Finished downloads whose collection-level work (the playlist file) is still to do: every one of
     * their succeeded songs has either been filed or given up on ({@code finished_at <= :cutoff} with
     * no path), and at least one WAS filed -- which is what keeps this off the install's history, where
     * nothing was ever filed. Idempotent through {@code organised_at IS NULL}.
     */
    private static final String DOWNLOADS_TO_FINALISE_SQL = """
            SELECT d.download_id, d.download_type, c.title AS collection_title
              FROM downloads d
              LEFT JOIN media_items c ON c.youtube_id = d.youtube_id
             WHERE d.status IN ('SUCCEEDED', 'PARTIAL_SUCCESS')
               AND d.organised_at IS NULL
               AND EXISTS (SELECT 1 FROM download_tasks t
                            WHERE t.download_id = d.download_id
                              AND t.library_path IS NOT NULL)
               AND NOT EXISTS (SELECT 1 FROM download_tasks t
                                WHERE t.download_id = d.download_id
                                  AND t.phase = 'SUCCEEDED'
                                  AND t.library_path IS NULL
                                  AND t.finished_at > :cutoff)
             ORDER BY d.finished_at
             LIMIT :limit
            """;

    /** The filed songs of one download in track order, with what a playlist line shows for each. */
    private static final String PLAYLIST_ENTRIES_SQL = """
            SELECT t.library_path, s.title, s.artists, s.duration_seconds
              FROM download_tasks t
              LEFT JOIN media_items s ON s.youtube_id = t.youtube_id
             WHERE t.download_id = :id
               AND t.library_path IS NOT NULL
             ORDER BY t.position, t.finished_at
            """;

    private static final String SET_ORGANISED_AT_SQL = """
            UPDATE downloads
               SET organised_at = :now
             WHERE download_id = :id
               AND organised_at IS NULL
               -- A retry between downloadsToFinalise's SELECT and this stamp reopens the download;
               -- stamping it then would leave the playlist file short until the next retry.
               AND status IN ('SUCCEEDED', 'PARTIAL_SUCCESS')
            """;

    // ---- album searches (P5) --------------------------------------------------------------------

    /**
     * CLAIM_DUE_SQL for {@code album_searches}: a lease on every due poll, and on at most
     * {@code :searchSlots} due starts. The runner claims these BEFORE the songs and gives the songs only
     * the slots left, so the two together never start more searches than slskd runs, and an album is
     * never stuck behind a long playlist's songs while its own songs wait for it. Joined with the
     * album's media row for the name it searches with.
     */
    private static final String CLAIM_DUE_ALBUM_SEARCHES_SQL = """
            WITH polls AS (
                SELECT download_id FROM album_searches
                 WHERE next_attempt_at <= :now
                   AND phase = 'SEARCH_POLL'
                   AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                 ORDER BY next_attempt_at
                   FOR UPDATE SKIP LOCKED
                 LIMIT :limit
            ), searches AS (
                SELECT download_id FROM album_searches
                 WHERE next_attempt_at <= :now
                   AND phase = 'SEARCH_INIT'
                   AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                 ORDER BY next_attempt_at
                   FOR UPDATE SKIP LOCKED
                 LIMIT :searchSlots
            ), claimed AS (
                UPDATE album_searches
                   SET lease_owner = :owner,
                       lease_expires_at = :leaseExpiresAt
                 WHERE download_id IN (SELECT download_id FROM polls UNION ALL SELECT download_id FROM searches)
                RETURNING download_id, phase, search_tier, search_id, phase_entered_at, next_attempt_at
            )
            SELECT c.download_id, c.phase, c.search_tier, c.search_id, c.phase_entered_at,
                   c.next_attempt_at, m.title, m.artists
              FROM claimed c
              JOIN downloads d ON d.download_id = c.download_id
              LEFT JOIN media_items m ON m.youtube_id = d.youtube_id
            """;

    /**
     * SAVE_SQL for an album search: written whole, lease cleared, only by the lease holder and only while
     * it is not DONE (a cancel ends it under a running step). When the step has just started a wording
     * ({@code :extendHold}), the songs still held get a fresh hold in the same statement, so a slow
     * search slot does not let them fall due while the album search is alive. Songs whose hold already ran
     * out are left alone: they are searching on their own. Only songs the release can still touch
     * (WAITING_ALBUM_SONGS_SQL's test): one retrying a later wording of its own would otherwise sit out
     * a hold nothing ends early.
     */
    private static final String SAVE_ALBUM_SEARCH_SQL = """
            WITH saved AS (
                UPDATE album_searches
                   SET phase = :phase,
                       search_tier = :searchTier,
                       search_id = :searchId,
                       phase_entered_at = :phaseEnteredAt,
                       next_attempt_at = :nextAttemptAt,
                       lease_owner = NULL,
                       lease_expires_at = NULL
                 WHERE download_id = :downloadId
                   AND lease_owner = :owner
                   AND phase <> 'DONE'
                RETURNING download_id
            ), held AS (
                UPDATE download_tasks
                   SET next_attempt_at = :holdUntil
                 WHERE download_id = :downloadId
                   AND :extendHold
                   AND EXISTS (SELECT 1 FROM saved)
                   AND phase = 'SEARCH_INIT'
                   AND search_id IS NULL
                   AND search_tier = 0
                   AND candidates = '[]'
                   AND next_attempt_at > :now
            )
            SELECT count(*) AS saved FROM saved
            """;

    /**
     * The songs an album search may still hand files to: not started on their own (no search, first
     * wording, no files kept), not finished or cancelled, and not held by a live lease. The same test as
     * the release below, so what the picker plans for is what the release can touch.
     */
    private static final String WAITING_ALBUM_SONGS_SQL = """
            SELECT task_id, download_id, youtube_id, song_name, track_title, track_number,
                   duration_seconds, phase, phase_entered_at, next_attempt_at, search_id,
                   search_tier, candidates, candidate_index, retry_index, slskd_username,
                   slskd_filename, slskd_transfer_id, last_error, progress_percent
              FROM download_tasks
             WHERE download_id = :downloadId
               AND phase = 'SEARCH_INIT'
               AND search_id IS NULL
               AND search_tier = 0
               AND candidates = '[]'
               AND (lease_expires_at IS NULL OR lease_expires_at < :now)
             ORDER BY position
            """;

    /**
     * Ends an album search and releases its songs, in ONE statement, so a crash cannot leave the search
     * done with its songs still held, nor release them twice. The search row is marked DONE only by its
     * own lease holder and only once; the songs are touched only when that happened ({@code EXISTS
     * done}), and only those still untouched (WAITING_ALBUM_SONGS_SQL's test): a cancelled song, a song
     * between two of its own wordings, or one a live lease holds is left alone. A song in {@code
     * :taskIds} gets its folder files ({@code :candidates}, index-aligned JSON) and goes straight to
     * DOWNLOAD_INIT; every other song is due now. Both get a fresh phase clock and no lease, so a step
     * still holding an expired lease on one of them cannot save over this.
     */
    private static final String RELEASE_ALBUM_SONGS_SQL = """
            WITH done AS (
                UPDATE album_searches
                   SET phase = 'DONE',
                       outcome = :outcome,
                       finished_at = :now,
                       lease_owner = NULL,
                       lease_expires_at = NULL
                 WHERE download_id = :downloadId
                   AND lease_owner = :owner
                   AND phase <> 'DONE'
                RETURNING download_id
            ), picked AS (
                SELECT p.task_id::uuid AS task_id, p.candidates
                  FROM unnest(:taskIds::text[], :candidates::text[]) AS p(task_id, candidates)
            )
            UPDATE download_tasks t
               SET phase = CASE WHEN EXISTS (SELECT 1 FROM picked p WHERE p.task_id = t.task_id)
                                THEN 'DOWNLOAD_INIT' ELSE 'SEARCH_INIT' END,
                   candidates = COALESCE((SELECT p.candidates FROM picked p WHERE p.task_id = t.task_id), '[]'),
                   candidate_index = 0,
                   retry_index = 0,
                   last_error = NULL,
                   phase_entered_at = :now,
                   next_attempt_at = :now,
                   updated_at = now(),
                   lease_owner = NULL,
                   lease_expires_at = NULL
             WHERE t.download_id = :downloadId
               AND EXISTS (SELECT 1 FROM done)
               AND t.phase = 'SEARCH_INIT'
               AND t.search_id IS NULL
               AND t.search_tier = 0
               AND t.candidates = '[]'
               AND (t.lease_expires_at IS NULL OR t.lease_expires_at < :now)
            """;

    private static final TypeReference<List<DownloadCandidate>> CANDIDATE_LIST =
            new TypeReference<>() {};

    private final DatabaseClient client;
    private final ObjectMapper objectMapper;

    public DownloadTaskRepository(R2dbcEntityTemplate entityTemplate, ObjectMapper objectMapper) {
        this.client = entityTemplate.getDatabaseClient();
        this.objectMapper = objectMapper;
    }

    /** Downloads ready for metadata gathering, oldest first. Writes nothing. */
    public Flux<Download> admitDownloads(int limit) {
        return client.sql(ADMISSIBLE_SQL)
                .bind("limit", limit)
                .map(DownloadTaskRepository::toDownload)
                .all();
    }

    /** A song, playlist or curated download: every song due now, no album search. */
    public Mono<Long> createTasks(UUID downloadId, List<DownloadTask> tasks, Instant now) {
        return createTasks(downloadId, tasks, now, null);
    }

    /**
     * @param tasks          in the collection's track order; their index becomes {@code position}
     * @param albumHoldUntil non-null for an album download (P5): its album search row is created too, and
     *                       the songs are due at this instant instead of now
     * @return task rows created: N for an admitted N-song download, 0 when the download was no
     *         longer PENDING or already had songs
     */
    public Mono<Long> createTasks(UUID downloadId, List<DownloadTask> tasks, Instant now, Instant albumHoldUntil) {
        if (tasks.isEmpty()) {
            return Mono.just(0L);
        }
        return client.sql(CREATE_TASKS_SQL)
                .bind("albumSearch", albumHoldUntil != null)
                .bind("holdUntil", albumHoldUntil == null ? now : albumHoldUntil)
                .bind("downloadId", downloadId)
                .bind("youtubeIds", tasks.stream().map(DownloadTask::youtubeId).toArray(String[]::new))
                .bind("songNames", tasks.stream().map(DownloadTask::songName).toArray(String[]::new))
                .bind("trackTitles", tasks.stream().map(DownloadTask::trackTitle).toArray(String[]::new))
                .bind("trackNumbers", tasks.stream().map(DownloadTask::trackNumber).toArray(Integer[]::new))
                .bind("durations", tasks.stream().map(DownloadTask::durationSeconds).toArray(Integer[]::new))
                .bind("libraryPaths", tasks.stream().map(DownloadTask::libraryPath).toArray(String[]::new))
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    /**
     * The library's EXACT copies of the songs of a download about to be admitted, newest first per
     * song; see FILED_COPIES_SQL. Whether each file is still there is {@link LibraryOrganiser#stillFiled}'s.
     *
     * @param type and {@code downloadYoutubeId}: the download's own, the album id for an album download
     */
    public Flux<LibraryOrganiser.FiledCopy> filedCopies(DownloadType type, String downloadYoutubeId,
                                                        List<DownloadTask> tasks) {
        if (tasks.isEmpty()) {
            return Flux.empty();
        }
        return client.sql(FILED_COPIES_SQL)
                .bind("album", type == DownloadType.ALBUM)
                .bind("albumId", downloadYoutubeId)
                .bind("youtubeIds", tasks.stream().map(DownloadTask::youtubeId).toArray(String[]::new))
                .bind("titles", tasks.stream().map(DownloadTask::trackTitle).toArray(String[]::new))
                .bind("trackNumbers", tasks.stream().map(DownloadTask::trackNumber).toArray(Integer[]::new))
                .map((row, meta) -> new LibraryOrganiser.FiledCopy(
                        row.get("position", Long.class).intValue(), row.get("library_path", String.class)))
                .all();
    }

    /**
     * Deduplicated by id before binding: a playlist can list one track twice, and
     * {@code ON CONFLICT DO UPDATE} refuses to touch the same row twice in one statement.
     */
    public Mono<Long> upsertMedia(List<MediaItem> items) {
        List<MediaItem> distinct = items.stream()
                .filter(item -> item.youtubeId() != null)
                .collect(java.util.stream.Collectors.toMap(MediaItem::youtubeId, item -> item,
                        (first, second) -> first, java.util.LinkedHashMap::new))
                .values().stream().toList();
        if (distinct.isEmpty()) {
            return Mono.just(0L);
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(distinct);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("Could not serialise media items", e));
        }
        return client.sql(UPSERT_MEDIA_SQL)
                .bind("items", json)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> concludeDownloads() {
        return client.sql(CONCLUDE_SQL)
                .fetch()
                .rowsUpdated()
                .doOnError(error -> log.error("Could not conclude finished downloads", error));
    }

    public Mono<Long> failUnadmitted(UUID downloadId, DownloadFailureCode code, Instant now) {
        return client.sql(FAIL_UNADMITTED_SQL)
                .bind("id", downloadId)
                .bind("reason", code.name())
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    /** The songs this call cancelled, with what stopping them in slskd and on disk needs. Empty when nothing was left to cancel. */
    public Flux<DownloadTask> cancelTasks(UUID downloadId, UUID taskId, Instant now) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(CANCEL_SQL)
                .bind("id", downloadId)
                .bind("now", now);
        spec = taskId == null ? spec.bindNull("taskId", UUID.class) : spec.bind("taskId", taskId);
        return spec.map((row, meta) -> DownloadTask.builder()
                        .taskId(row.get("task_id", UUID.class))
                        .downloadId(row.get("download_id", UUID.class))
                        .candidates(readCandidates(row.get("candidates", String.class)))
                        .candidateIndex(row.get("candidate_index", Integer.class))
                        .slskdUsername(row.get("slskd_username", String.class))
                        .slskdFilename(row.get("slskd_filename", String.class))
                        .slskdTransferId(row.get("slskd_transfer_id", String.class))
                        .build())
                .all();
    }

    /** 1 when the download's FAILED songs were reset and it reopened; 0 when there was nothing to retry. See RETRY_SQL. */
    public Mono<Long> retry(UUID downloadId, Instant now) {
        return client.sql(RETRY_SQL).bind("id", downloadId).bind("now", now).fetch().rowsUpdated();
    }

    /** 1 when a download that failed before it had songs went back to PENDING; 0 otherwise. */
    public Mono<Long> readmit(UUID downloadId) {
        return client.sql(READMIT_SQL).bind("id", downloadId).fetch().rowsUpdated();
    }

    /**
     * Finished songs still to be moved into the library, oldest first. See {@link LibraryOrganiser}.
     *
     * @param albumCutoff a song or playlist track that finished after this waits for its album lookup
     */
    public Flux<LibraryOrganiser.Job> tasksToOrganise(int limit, Instant cutoff, Instant albumCutoff) {
        return client.sql(TASKS_TO_ORGANISE_SQL)
                .bind("cutoff", cutoff)
                .bind("albumCutoff", albumCutoff)
                .bind("limit", limit)
                .map((row, meta) -> new LibraryOrganiser.Job(
                        row.get("task_id", UUID.class),
                        DownloadType.valueOf(row.get("download_type", String.class)),
                        row.get("slskd_filename", String.class),
                        row.get("finished_at", Instant.class),
                        row.get("song_title", String.class),
                        artists(row.get("song_artists", String[].class)),
                        row.get("collection_title", String.class),
                        artists(row.get("collection_artists", String[].class)),
                        row.get("album_title", String.class),
                        artists(row.get("album_artists", String[].class)),
                        new SongTagger.Tags(
                                row.get("tag_title", String.class),
                                artists(row.get("song_artists", String[].class)),
                                row.get("tag_album", String.class),
                                artists(row.get("tag_album_artists", String[].class)),
                                row.get("tag_year", Integer.class),
                                row.get("tag_track", Integer.class),
                                row.get("tag_track_total", Integer.class),
                                row.get("tag_cover", String.class)),
                        row.get("filed_copy", String.class), row.get("copy_key", String.class),
                        row.get("pick_size", Long.class)))
                .all();
    }

    /** Songs whose album is still to be looked up, oldest request first. See {@link SongAlbumResolver}. */
    public Flux<SongAlbumResolver.Song> songsToResolve(int limit, Instant cutoff, Instant relookBefore) {
        return client.sql(SONGS_TO_RESOLVE_SQL)
                .bind("cutoff", cutoff)
                .bind("relookBefore", relookBefore)
                .bind("limit", limit)
                .map((row, meta) -> new SongAlbumResolver.Song(
                        row.get("youtube_id", String.class),
                        row.get("song_name", String.class),
                        row.get("duration_seconds", Integer.class),
                        DownloadType.valueOf(row.get("download_type", String.class)),
                        row.get("download_youtube_id", String.class)))
                .all();
    }

    /** @param albumId null for "looked, and no album could be trusted"; then so is {@code trackNumber} */
    public Mono<Long> saveSongAlbum(String youtubeId, String albumId, Integer trackNumber, Instant now) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(SAVE_SONG_ALBUM_SQL)
                .bind("youtubeId", youtubeId)
                .bind("now", now);
        spec = bindNullable(spec, "albumId", albumId);
        spec = trackNumber == null ? spec.bindNull("trackNumber", Integer.class) : spec.bind("trackNumber", trackNumber);
        return spec.fetch().rowsUpdated();
    }

    /** Records where a song's file now lives. Writes once: a second call for the same task is a no-op. */
    public Mono<Long> setLibraryPath(UUID taskId, String libraryPath) {
        return client.sql(SET_LIBRARY_PATH_SQL)
                .bind("id", taskId)
                .bind("path", libraryPath)
                .fetch()
                .rowsUpdated();
    }

    /** Downloads whose songs are all filed (or given up on) and whose playlist file is still to write. */
    public Flux<LibraryOrganiser.Collection> downloadsToFinalise(int limit, Instant cutoff) {
        return client.sql(DOWNLOADS_TO_FINALISE_SQL)
                .bind("cutoff", cutoff)
                .bind("limit", limit)
                .map((row, meta) -> new LibraryOrganiser.Collection(
                        row.get("download_id", UUID.class),
                        DownloadType.valueOf(row.get("download_type", String.class)),
                        row.get("collection_title", String.class)))
                .all();
    }

    /** One download's filed songs in track order. */
    public Flux<LibraryOrganiser.Entry> playlistEntries(UUID downloadId) {
        return client.sql(PLAYLIST_ENTRIES_SQL)
                .bind("id", downloadId)
                .map((row, meta) -> new LibraryOrganiser.Entry(
                        row.get("library_path", String.class),
                        row.get("title", String.class),
                        artists(row.get("artists", String[].class)),
                        row.get("duration_seconds", Integer.class)))
                .all();
    }

    /** Marks a download's collection-level work done. Writes once. */
    public Mono<Long> setOrganisedAt(UUID downloadId, Instant now) {
        return client.sql(SET_ORGANISED_AT_SQL)
                .bind("id", downloadId)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    private static List<String> artists(String[] values) {
        return values == null ? List.of() : List.of(values);
    }

    public Mono<Long> countActiveDownloads() {
        return client.sql(COUNT_ACTIVE_DOWNLOADS_SQL)
                .map((row, meta) -> row.get("total", Long.class))
                .one();
    }

    /** One of our transfers that slskd is running: the sharer it is with and slskd's id for it. */
    public record TransferInFlight(String username, String transferId) {}

    /** Every transfer naviseerr has in slskd right now, across all downloads and instances. */
    public Flux<TransferInFlight> transfersInFlight() {
        return client.sql(TRANSFERS_IN_FLIGHT_SQL)
                .map((row, meta) -> new TransferInFlight(row.get("slskd_username", String.class),
                        row.get("slskd_transfer_id", String.class)))
                .all();
    }

    public Mono<Long> countActiveSearches() {
        return client.sql(COUNT_ACTIVE_SEARCHES_SQL)
                .map((row, meta) -> row.get("total", Long.class))
                .one();
    }

    /**
     * @param searchSlots how many SEARCH_INIT rows may be claimed this pass -- the free search slots.
     *                    Zero claims none; polls and DOWNLOAD_INIT are unaffected by it.
     */
    public Flux<DownloadTask> claimDueTasks(int limit, String owner, Instant now, Duration lease,
                                            boolean transferSlotsFree, int searchSlots) {
        return client.sql(CLAIM_DUE_SQL)
                .bind("owner", owner)
                .bind("leaseExpiresAt", now.plus(lease))
                .bind("now", now)
                .bind("transferSlotsFree", transferSlotsFree)
                .bind("limit", limit)
                .bind("searchSlots", Math.max(0, searchSlots))
                .map(this::toTask)
                .all();
    }

    /** Due album searches with a fresh lease: every poll, and at most {@code searchSlots} starts. */
    public Flux<AlbumSearch> claimDueAlbumSearches(int limit, String owner, Instant now, Duration lease,
                                                   int searchSlots) {
        return client.sql(CLAIM_DUE_ALBUM_SEARCHES_SQL)
                .bind("owner", owner)
                .bind("leaseExpiresAt", now.plus(lease))
                .bind("now", now)
                .bind("limit", limit)
                .bind("searchSlots", Math.max(0, searchSlots))
                .map((row, meta) -> AlbumSearch.builder()
                        .downloadId(row.get("download_id", UUID.class))
                        .phase(DownloadPhase.valueOf(row.get("phase", String.class)))
                        .searchTier(row.get("search_tier", Integer.class))
                        .searchId(row.get("search_id", String.class))
                        .phaseEnteredAt(row.get("phase_entered_at", Instant.class))
                        .nextAttemptAt(row.get("next_attempt_at", Instant.class))
                        .title(row.get("title", String.class))
                        .artists(artists(row.get("artists", String[].class)))
                        .build())
                .all();
    }

    /**
     * @param holdUntil non-null when a wording has just started: the album's still-held songs are held
     *                  until then
     * @return 1 when written, 0 when the lease was lost or the search was ended meanwhile
     */
    public Mono<Long> saveAlbumSearch(AlbumSearch album, String owner, Instant now, Instant holdUntil) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(SAVE_ALBUM_SEARCH_SQL)
                .bind("downloadId", album.downloadId())
                .bind("owner", owner)
                .bind("phase", album.phase().name())
                .bind("searchTier", album.searchTier())
                .bind("phaseEnteredAt", album.phaseEnteredAt())
                .bind("nextAttemptAt", album.nextAttemptAt())
                .bind("now", now)
                .bind("extendHold", holdUntil != null)
                .bind("holdUntil", holdUntil == null ? now : holdUntil);
        return bindNullable(spec, "searchId", album.searchId())
                .map((row, meta) -> row.get("saved", Long.class))
                .one();
    }

    /** The album's songs an album search may still hand files to, in track order. */
    public Flux<DownloadTask> waitingAlbumSongs(UUID downloadId, Instant now) {
        return client.sql(WAITING_ALBUM_SONGS_SQL)
                .bind("downloadId", downloadId)
                .bind("now", now)
                .map(this::toTask)
                .all();
    }

    /**
     * Ends an album search and releases its songs; see RELEASE_ALBUM_SONGS_SQL.
     *
     * @param picks the folder files for each song a whole folder holds; empty when none does
     * @return songs released; 0 when this owner no longer holds the search or it had already ended
     */
    public Mono<Long> releaseAlbumSongs(UUID downloadId, String owner, AlbumSearch.Outcome outcome,
                                        java.util.Map<UUID, List<DownloadCandidate>> picks, Instant now) {
        List<java.util.Map.Entry<UUID, List<DownloadCandidate>>> picked = List.copyOf(picks.entrySet());
        return client.sql(RELEASE_ALBUM_SONGS_SQL)
                .bind("downloadId", downloadId)
                .bind("owner", owner)
                .bind("outcome", outcome.name())
                .bind("taskIds", picked.stream().map(e -> e.getKey().toString()).toArray(String[]::new))
                .bind("candidates", picked.stream().map(e -> writeCandidates(e.getValue())).toArray(String[]::new))
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> save(DownloadTask task, String owner) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(SAVE_SQL)
                .bind("id", task.taskId())
                .bind("owner", owner)
                .bind("phase", task.phase().name())
                .bind("phaseEnteredAt", task.phaseEnteredAt())
                .bind("nextAttemptAt", task.nextAttemptAt())
                .bind("searchTier", task.searchTier())
                .bind("candidates", writeCandidates(task.candidates()))
                .bind("candidateIndex", task.candidateIndex())
                .bind("retryIndex", task.retryIndex())
                .bind("progressPercent", task.progressPercent());
        spec = bindNullable(spec, "searchId", task.searchId());
        spec = bindNullable(spec, "slskdUsername", task.slskdUsername());
        spec = bindNullable(spec, "slskdFilename", task.slskdFilename());
        spec = bindNullable(spec, "slskdTransferId", task.slskdTransferId());
        spec = bindNullable(spec, "lastError", task.lastError());
        return spec.fetch().rowsUpdated();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static Download toDownload(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return Download.builder()
                .downloadId(row.get("download_id", UUID.class))
                .youtubeId(row.get("youtube_id", String.class))
                .downloadType(DownloadType.valueOf(row.get("download_type", String.class)))
                .status(DownloadStatus.valueOf(row.get("status", String.class)))
                .failureReason(row.get("failure_reason", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .admittedAt(row.get("admitted_at", Instant.class))
                .finishedAt(row.get("finished_at", Instant.class))
                .build();
    }

    private DownloadTask toTask(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return DownloadTask.builder()
                .taskId(row.get("task_id", UUID.class))
                .downloadId(row.get("download_id", UUID.class))
                .youtubeId(row.get("youtube_id", String.class))
                .songName(row.get("song_name", String.class))
                .trackTitle(row.get("track_title", String.class))
                .trackNumber(row.get("track_number", Integer.class))
                .durationSeconds(row.get("duration_seconds", Integer.class))
                .phase(DownloadPhase.valueOf(row.get("phase", String.class)))
                .phaseEnteredAt(row.get("phase_entered_at", Instant.class))
                .nextAttemptAt(row.get("next_attempt_at", Instant.class))
                .searchId(row.get("search_id", String.class))
                .searchTier(row.get("search_tier", Integer.class))
                .candidates(readCandidates(row.get("candidates", String.class)))
                .candidateIndex(row.get("candidate_index", Integer.class))
                .retryIndex(row.get("retry_index", Integer.class))
                .slskdUsername(row.get("slskd_username", String.class))
                .slskdFilename(row.get("slskd_filename", String.class))
                .slskdTransferId(row.get("slskd_transfer_id", String.class))
                .lastError(row.get("last_error", String.class))
                .progressPercent(row.get("progress_percent", java.math.BigDecimal.class))
                .build();
    }

    private String writeCandidates(List<DownloadCandidate> candidates) {
        try {
            return objectMapper.writeValueAsString(candidates == null ? List.of() : candidates);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise download candidates", e);
        }
    }

    private List<DownloadCandidate> readCandidates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, CANDIDATE_LIST);
        } catch (Exception e) {
            // Falls back rather than throws: one bad row must not abort the whole claimed batch.
            log.error("Could not deserialise download candidates; treating as no candidates", e);
            return List.of();
        }
    }
}
