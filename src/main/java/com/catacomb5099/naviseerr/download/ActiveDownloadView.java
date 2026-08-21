package com.catacomb5099.naviseerr.download;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of the active-downloads read model: a download joined with its current task state. */
public record ActiveDownloadView(
        UUID downloadId,
        String songName,
        DownloadStatus status,
        DownloadPhase phase,
        BigDecimal progressPercent,
        Instant phaseEnteredAt) {
}
