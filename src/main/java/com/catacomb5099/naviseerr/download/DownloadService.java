package com.catacomb5099.naviseerr.download;

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
            """;

    private final R2dbcEntityTemplate entityTemplate;

    public DownloadService(R2dbcEntityTemplate entityTemplate) {
        this.entityTemplate = entityTemplate;
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
     * Marks one song's task row terminal. Idempotent: a second call for an already-terminal task
     * updates nothing and returns 0. The download's own status follows from
     * {@link DownloadTaskRepository#concludeDownloads()} at the end of the pass.
     */
    public Mono<Long> finishTask(UUID taskId, DownloadStatus status,
                                 DownloadFailureCode failureCode, Instant now) {
        DatabaseClient.GenericExecuteSpec spec = entityTemplate.getDatabaseClient()
                .sql(FINISH_TASK_SQL)
                .bind("status", status.name())
                .bind("id", taskId)
                .bind("now", now);
        // Stored by NAME, not prose: the client words it, so copy changes never touch this table.
        spec = failureCode == null
                ? spec.bindNull("reason", String.class)
                : spec.bind("reason", failureCode.name());
        return spec.fetch()
                .rowsUpdated()
                .doOnError(error -> log.error("Could not finish task {} as {}",
                        taskId, status, error));
    }
}
