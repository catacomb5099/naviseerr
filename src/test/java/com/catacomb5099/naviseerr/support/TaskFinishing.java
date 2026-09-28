package com.catacomb5099.naviseerr.support;

import com.catacomb5099.naviseerr.download.DownloadFailureCode;
import com.catacomb5099.naviseerr.download.DownloadService;
import com.catacomb5099.naviseerr.download.DownloadStatus;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;

import java.time.Instant;
import java.util.UUID;

public final class TaskFinishing {

    private TaskFinishing() {}

    /**
     * Finishes a song the way the runner does, for a fixture row no runner ever claimed.
     * {@code finishTask} only lands for the caller holding the row's lease, so this stamps one first.
     */
    public static Long finish(R2dbcEntityTemplate template, DownloadService service, UUID taskId,
                              DownloadStatus status, DownloadFailureCode code, Instant now) {
        template.getDatabaseClient()
                .sql("UPDATE download_tasks SET lease_owner = 'it' WHERE task_id = :id")
                .bind("id", taskId)
                .fetch().rowsUpdated().block();
        return service.finishTask(taskId, status, code, now, "it").block();
    }
}
