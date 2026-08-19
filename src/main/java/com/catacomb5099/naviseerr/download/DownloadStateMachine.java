package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.QueueDownloadResponse;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.SlskdSearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.util.TransferedFileUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Every branching decision in the download pipeline. Pure: no I/O, no Reactor, no clock of its own —
 * {@code now} is always passed in. That is what makes the whole branch matrix testable without mocking
 * HTTP or sleeping.
 */
@Component
public class DownloadStateMachine {

    public static final String SEARCH_FAILED = "Searching for downloads failed";
    public static final String NO_CANDIDATES = "No download candidates found";
    public static final String SOURCES_EXHAUSTED = "All download sources exhausted";
    public static final String TIMED_OUT = "timed out";
    public static final String TRANSFER_NOT_FOUND = "slskd has no record of this transfer";

    private final Duration searchPollInterval;
    private final Duration downloadPollInterval;
    private final Duration searchBudget;
    private final Duration downloadBudget;
    private final Duration missingTransferGrace;
    private final int retryLimit;

    public DownloadStateMachine(
            @Value("${download-task.search-poll-interval-ms:2000}") Duration searchPollInterval,
            @Value("${download-task.download-poll-interval-ms:5000}") Duration downloadPollInterval,
            @Value("${download-task.search-budget-ms:120000}") Duration searchBudget,
            @Value("${download-task.download-budget-ms:3600000}") Duration downloadBudget,
            @Value("${download-task.missing-transfer-grace-ms:60000}") Duration missingTransferGrace,
            @Value("${slskd-service.retry-count}") int retryLimit) {
        this.searchPollInterval = searchPollInterval;
        this.downloadPollInterval = downloadPollInterval;
        this.searchBudget = searchBudget;
        this.downloadBudget = downloadBudget;
        this.missingTransferGrace = missingTransferGrace;
        this.retryLimit = retryLimit;
    }

    public DownloadDecision afterSearchInit(DownloadTask task, SearchState started, Instant now) {
        if (started == null || started.getId() == null || started.getId().isBlank()) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED, SEARCH_FAILED);
        }
        DownloadTask next = task.withPhase(DownloadPhase.SEARCH_POLL, now);
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.songName(),
                next.phase(), next.phaseEnteredAt(), now, started.getId(), next.candidates(),
                next.candidateIndex(), next.retryIndex(), null, null, null, null));
    }

    /**
     * {@code state} may be {@code null} — the batched {@code GET /searches} simply omits a search it
     * doesn't recognise. Deliberately indistinguishable from "still running": there is no reliable way
     * to tell "not there yet" apart from "slskd forgot it", so a missing search just falls through to
     * the same still-running branch and eventually resolves via the phase budget. Same reasoning
     * applies to {@code selected}, which is only consulted once the search is complete, so callers may
     * pass an empty list while it is still running or missing.
     */
    public DownloadDecision afterSearchPoll(DownloadTask task, SearchState state,
                                            List<DownloadCandidate> selected, Instant now) {
        if (state != null && SlskdSearchState.isFailure(state.getState())) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED, SEARCH_FAILED);
        }
        boolean complete = state != null && Boolean.TRUE.equals(state.getIsComplete());
        if (!complete) {
            return task.isPastBudget(now, searchBudget)
                    ? new DownloadDecision.Terminal(DownloadStatus.FAILED, TIMED_OUT)
                    : new DownloadDecision.Continue(task.dueAt(now.plus(searchPollInterval)));
        }
        if (selected == null || selected.isEmpty()) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED, NO_CANDIDATES);
        }
        DownloadTask next = task.withPhase(DownloadPhase.DOWNLOAD_INIT, now);
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.songName(),
                next.phase(), next.phaseEnteredAt(), now, next.searchId(), selected, 0, 0,
                null, null, null, null));
    }

    public DownloadDecision afterDownloadInit(DownloadTask task, QueueDownloadResponse response,
                                              Instant now) {
        if (response == null || response.getEnqueued() == null || response.getEnqueued().isEmpty()) {
            return retryOrAdvanceCandidate(task, now);
        }
        TransferedFile enqueued = response.getEnqueued().getFirst();
        DownloadTask next = task.withPhase(DownloadPhase.DOWNLOAD_POLL, now);
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.songName(),
                next.phase(), next.phaseEnteredAt(), now, next.searchId(), next.candidates(),
                next.candidateIndex(), next.retryIndex(), enqueued.getUsername(),
                enqueued.getFilename(), enqueued.getId(), null));
    }

    /**
     * {@code file} is {@code null} when the batched {@code GET /transfers/downloads} has no transfer
     * with this row's id, and {@link TransferedFileUtil#getStateList} returns an empty list for it.
     *
     * <p>That case gets its OWN short-budget branch rather than falling through to "still running".
     * Aliasing the two — the original design — meant a lookup that could never succeed was
     * indistinguishable from a transfer making progress, so the row polled for the full hour-long
     * {@code downloadBudget} before reporting a timeout. Not hypothetical: it is exactly what the
     * nested-response bug in {@code getAllDownloads} produced, and the aliasing is what kept it silent.
     * slskd retains completed transfers (a finished one still reports {@code "Completed, Succeeded"}
     * with {@code removed: false}), so "absent from the list" is a real signal, not a normal lifecycle
     * stage, and it deserves to fail loudly and quickly.
     */
    public DownloadDecision afterDownloadPoll(DownloadTask task, TransferedFile file, Instant now) {
        List<TransferState> states = TransferedFileUtil.getStateList(file);
        if (states.stream().anyMatch(TransferState::isSuccess)) {
            return new DownloadDecision.Terminal(DownloadStatus.SUCCEEDED, null);
        }
        if (states.stream().anyMatch(TransferState::isFailure)) {
            return retryOrAdvanceCandidate(task, now);
        }
        if (states.isEmpty()) {
            // Deliberately NOT reported as SUCCEEDED. "We cannot see this transfer" is not evidence
            // that it finished, and treating it as success would mark downloads complete that never
            // moved a byte. A short grace window absorbs the gap between enqueueing and the transfer
            // appearing in the list; past that, stop polling and say so.
            return task.isPastBudget(now, missingTransferGrace)
                    ? new DownloadDecision.Terminal(DownloadStatus.FAILED, TRANSFER_NOT_FOUND)
                    : new DownloadDecision.Continue(task.dueAt(now.plus(downloadPollInterval)));
        }
        return task.isPastBudget(now, downloadBudget)
                ? new DownloadDecision.Terminal(DownloadStatus.FAILED, TIMED_OUT)
                : new DownloadDecision.Continue(task.dueAt(now.plus(downloadPollInterval)));
    }

    public DownloadDecision onCallFailed(DownloadTask task, Throwable error, Instant now) {
        return switch (task.phase()) {
            case SEARCH_INIT, SEARCH_POLL ->
                    new DownloadDecision.Terminal(DownloadStatus.FAILED, SEARCH_FAILED);
            case DOWNLOAD_INIT, DOWNLOAD_POLL -> retryOrAdvanceCandidate(task, now);
        };
    }

    private DownloadDecision retryOrAdvanceCandidate(DownloadTask task, Instant now) {
        DownloadTask base = task.withPhase(DownloadPhase.DOWNLOAD_INIT, now)
                .dueAt(now.plus(downloadPollInterval));
        if (task.retryIndex() < retryLimit) {
            return new DownloadDecision.Continue(rebuild(base, task.candidateIndex(),
                    task.retryIndex() + 1));
        }
        if (task.candidateIndex() + 1 < task.candidates().size()) {
            return new DownloadDecision.Continue(rebuild(base, task.candidateIndex() + 1, 0));
        }
        return new DownloadDecision.Terminal(DownloadStatus.FAILED, SOURCES_EXHAUSTED);
    }

    private DownloadTask rebuild(DownloadTask base, int candidateIndex, int retryIndex) {
        return new DownloadTask(base.downloadId(), base.songName(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), base.searchId(), base.candidates(),
                candidateIndex, retryIndex, null, null, null, null);
    }
}
