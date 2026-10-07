package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

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

    public DownloadService(R2dbcEntityTemplate entityTemplate, DownloadTaskRepository repository,
                           SlskdService slskdService, LibraryOrganiser organiser) {
        this.entityTemplate = entityTemplate;
        this.repository = repository;
        this.slskdService = slskdService;
        this.organiser = organiser;
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
     */
    public Mono<Long> retry(UUID downloadId, UUID taskId, Instant now) {
        return repository.retry(downloadId, taskId, now)
                .flatMap(rows -> rows > 0 || taskId != null ? Mono.just(rows) : repository.readmit(downloadId))
                .doOnNext(rows -> {
                    if (rows > 0) log.info("Retrying {} song(s) of download {}", rows, downloadId);
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
