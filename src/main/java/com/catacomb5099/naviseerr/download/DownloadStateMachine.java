package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.QueueDownloadResponse;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.SlskdSearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.util.TransferedFileUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
            return new DownloadDecision.Terminal(DownloadStatus.FAILED,
                    DownloadFailureCode.SEARCH_FAILED);
        }
        DownloadTask next = task.withPhase(DownloadPhase.SEARCH_POLL, now);
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.query(),
                next.phase(), next.phaseEnteredAt(), now, started.getId(), next.candidates(),
                next.candidateIndex(), next.retryIndex(), null, null, null, null));
    }

    /** {@code state} missing or not yet complete is treated as still running, not as an error. */
    public DownloadDecision afterSearchPoll(DownloadTask task, SearchState state,
                                            List<DownloadCandidate> selected, Instant now) {
        if (state != null && SlskdSearchState.isFailure(state.getState())) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED,
                    DownloadFailureCode.SEARCH_FAILED);
        }
        if (state == null || !Boolean.TRUE.equals(state.getIsComplete())) {
            return task.isPastBudget(now, searchBudget)
                    ? new DownloadDecision.Terminal(DownloadStatus.FAILED, DownloadFailureCode.TIMED_OUT)
                    : new DownloadDecision.Continue(task.dueAt(now.plus(searchPollInterval)));
        }
        if (selected == null || selected.isEmpty()) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED,
                    DownloadFailureCode.NO_CANDIDATES);
        }
        DownloadTask next = task.withPhase(DownloadPhase.DOWNLOAD_INIT, now);
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.query(),
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
        return new DownloadDecision.Advance(new DownloadTask(next.downloadId(), next.query(),
                next.phase(), next.phaseEnteredAt(), now, next.searchId(), next.candidates(),
                next.candidateIndex(), next.retryIndex(), enqueued.getUsername(),
                enqueued.getFilename(), enqueued.getId(), null));
    }

    /** A transfer absent from slskd's list gets its own short-budget branch, not the poll timeout. */
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
                    ? new DownloadDecision.Terminal(DownloadStatus.FAILED,
                            DownloadFailureCode.TRANSFER_NOT_FOUND)
                    : new DownloadDecision.Continue(task.dueAt(now.plus(downloadPollInterval)));
        }
        // Genuinely still transferring: the only branch with a percentComplete worth reading.
        DownloadTask observed = task.withProgress(toProgress(file.getPercentComplete()));
        return observed.isPastBudget(now, downloadBudget)
                ? new DownloadDecision.Terminal(DownloadStatus.FAILED, DownloadFailureCode.TIMED_OUT)
                : new DownloadDecision.Continue(observed.dueAt(now.plus(downloadPollInterval)));
    }

    /**
     * slskd omits {@code percentComplete} depending on transfer state, so null/NaN/infinite must be
     * treated as "no observation" rather than defaulted to zero -- see {@link TransferedFile}'s
     * javadoc. Clamped to [0, 100] because slskd is not contractually bound to stay inside that range.
     */
    static BigDecimal toProgress(Float percentComplete) {
        if (percentComplete == null || percentComplete.isNaN() || percentComplete.isInfinite()) {
            return null;
        }
        double clamped = Math.clamp(percentComplete.doubleValue(), 0d, 100d);
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP);
    }

    public DownloadDecision onCallFailed(DownloadTask task, Throwable error, Instant now) {
        return switch (task.phase()) {
            case SEARCH_INIT, SEARCH_POLL -> onSearchCallFailed(task, error, now);
            case DOWNLOAD_INIT, DOWNLOAD_POLL -> retryOrAdvanceCandidate(task, now);
        };
    }

    /**
     * A failed slskd call during search is retried in place -- same phase, no candidate/progress
     * reset -- rather than failing the download outright, as long as the {@code searchBudget} isn't
     * already spent. This covers both a dropped/timed-out connection ({@link WebClientRequestException})
     * and slskd itself erroring the call ({@link WebClientResponseException}, 4xx or 5xx alike: a
     * rejected search is retried the same as a dropped one, on the theory that a transient rejection
     * recovering is worth more than a genuinely bad one failing sooner -- the budget already bounds
     * the cost either way). Anything else is an error shape this code doesn't recognise, so it fails
     * fast rather than guess.
     */
    private DownloadDecision onSearchCallFailed(DownloadTask task, Throwable error, Instant now) {
        boolean retryable = error instanceof WebClientRequestException
                || error instanceof WebClientResponseException;
        if (retryable && !task.isPastBudget(now, searchBudget)) {
            return new DownloadDecision.Continue(task.dueAt(now.plus(searchPollInterval)));
        }
        return new DownloadDecision.Terminal(DownloadStatus.FAILED, DownloadFailureCode.SEARCH_FAILED);
    }

    private DownloadDecision retryOrAdvanceCandidate(DownloadTask task, Instant now) {
        // Reset, not carried forward: a retry or a failover to the next candidate starts a new
        // transfer from zero, and the previous one's progress has nothing to do with it.
        DownloadTask base = task.withPhase(DownloadPhase.DOWNLOAD_INIT, now)
                .dueAt(now.plus(downloadPollInterval))
                .withProgressReset();
        if (task.retryIndex() < retryLimit) {
            return new DownloadDecision.Continue(rebuild(base, task.candidateIndex(),
                    task.retryIndex() + 1));
        }
        if (task.candidateIndex() + 1 < task.candidates().size()) {
            return new DownloadDecision.Continue(rebuild(base, task.candidateIndex() + 1, 0));
        }
        return new DownloadDecision.Terminal(DownloadStatus.FAILED,
                DownloadFailureCode.SOURCES_EXHAUSTED);
    }

    private DownloadTask rebuild(DownloadTask base, int candidateIndex, int retryIndex) {
        return new DownloadTask(base.downloadId(), base.query(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), base.searchId(), base.candidates(),
                candidateIndex, retryIndex, null, null, null, null, base.progressPercent());
    }
}
