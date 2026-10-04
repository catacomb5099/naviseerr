package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.QueueDownloadResponse;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.SlskdSearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
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
import java.util.Objects;

/**
 * Every branching decision in the download pipeline. Pure: no I/O, no Reactor, no clock of its own —
 * {@code now} is always passed in. That is what makes the whole branch matrix testable without mocking
 * HTTP or sleeping. The one piece of shared state it consults is {@link StallingSharers}, an in-memory
 * list, so a test can hand it a fresh one.
 */
@Component
public class DownloadStateMachine {

    private final Duration searchPollInterval;
    private final Duration downloadPollInterval;
    private final Duration searchBudget;
    private final Duration downloadBudget;
    private final Duration queuedBudget;
    private final Duration missingTransferGrace;
    private final int retryLimit;
    private final int firstWordingMinCandidates;
    private final StallingSharers stallingSharers;

    public DownloadStateMachine(
            @Value("${download-task.search-poll-interval-ms:2000}") Duration searchPollInterval,
            @Value("${download-task.download-poll-interval-ms:5000}") Duration downloadPollInterval,
            @Value("${download-task.search-budget-ms:120000}") Duration searchBudget,
            @Value("${download-task.download-budget-ms:3600000}") Duration downloadBudget,
            @Value("${download-task.queued-budget-ms:600000}") Duration queuedBudget,
            @Value("${download-task.missing-transfer-grace-ms:60000}") Duration missingTransferGrace,
            @Value("${slskd-service.retry-count}") int retryLimit,
            @Value("${download-task.first-wording-min-candidates:3}") int firstWordingMinCandidates,
            StallingSharers stallingSharers) {
        this.searchPollInterval = searchPollInterval;
        this.downloadPollInterval = downloadPollInterval;
        this.searchBudget = searchBudget;
        this.downloadBudget = downloadBudget;
        this.queuedBudget = queuedBudget;
        this.missingTransferGrace = missingTransferGrace;
        this.retryLimit = retryLimit;
        this.firstWordingMinCandidates = firstWordingMinCandidates;
        this.stallingSharers = stallingSharers;
    }

    public DownloadDecision afterSearchInit(DownloadTask task, SearchState started, Instant now) {
        if (started == null || started.getId() == null || started.getId().isBlank()) {
            return giveUpSearch(task, DownloadFailureCode.SEARCH_FAILED, now);
        }
        DownloadTask next = task.withPhase(DownloadPhase.SEARCH_POLL, now);
        return new DownloadDecision.Advance(next.toBuilder()
                .searchId(started.getId()).retryIndex(0)
                .slskdUsername(null).slskdFilename(null).slskdTransferId(null).lastError(null)
                .progressPercent(BigDecimal.ZERO)
                .build());
    }

    /** {@code state} missing or not yet complete is treated as still running, not as an error. */
    public DownloadDecision afterSearchPoll(DownloadTask task, SearchState state,
                                            List<DownloadCandidate> selected, Instant now) {
        if (state != null && SlskdSearchState.isFailure(state.getState())) {
            return giveUpSearch(task, DownloadFailureCode.SEARCH_FAILED, now);
        }
        if (state == null || !Boolean.TRUE.equals(state.getIsComplete())) {
            return task.isPastBudget(now, searchBudget)
                    ? giveUpSearch(task, DownloadFailureCode.TIMED_OUT, now)
                    : new DownloadDecision.Continue(task.dueAt(now.plus(searchPollInterval)));
        }
        List<DownloadCandidate> chosen = selected == null ? List.of() : selected;
        // The best list seen so far travels on the row (see better): when the artist is a phrase
        // Soulseek blocks, the wordings that name it come back empty, and a few files beat none.
        List<DownloadCandidate> kept = better(chosen, task.candidates());
        // The first wording is the title alone (the owner's call, 28-09-2026). It is enough only with a
        // handful of files in the requested version with the artist confirmed: a common title fills
        // slskd's response cap with other artists' songs, and three studio copies must not settle a
        // request for the live take before the artist wording has run. The later wordings name the
        // artist and are enough with one confirmed file. withPhase rather than dueAt: each tier is a
        // new search with its own search budget. This is the ONLY branch that advances a tier.
        boolean anotherWording = task.searchTier() + 1 < SearchQueryTiers.of(task.songName()).size();
        boolean enough = task.searchTier() == 0
                ? countOf(chosen, TrackMatchingService.Match.EXACT) >= firstWordingMinCandidates
                : hasConfirmed(chosen);
        if (!enough && anotherWording) {
            return new DownloadDecision.Advance(task.withPhase(DownloadPhase.SEARCH_INIT, now)
                    .toBuilder().searchTier(task.searchTier() + 1).searchId(null).candidates(kept).build());
        }
        if (kept.isEmpty()) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED, DownloadFailureCode.NO_CANDIDATES);
        }
        return startDownload(task, kept, now);
    }

    /**
     * The list worth carrying to the next wording: one with the artist confirmed beats one of unverified
     * files, any list beats none, and of two equals the newer wins (it came from the more specific wording).
     */
    private static List<DownloadCandidate> better(List<DownloadCandidate> newer, List<DownloadCandidate> older) {
        return rank(newer) >= rank(older) ? newer : older;
    }

    private static int rank(List<DownloadCandidate> list) {
        return list.isEmpty() ? 0 : hasConfirmed(list) ? 2 : 1;
    }

    /** Any file whose artist the picker could confirm (rows from before grades existed count as confirmed). */
    private static boolean hasConfirmed(List<DownloadCandidate> list) {
        return list.stream().anyMatch(c -> !TrackMatchingService.Match.UNVERIFIED.name().equals(c.grade()));
    }

    private static long countOf(List<DownloadCandidate> list, TrackMatchingService.Match grade) {
        return list.stream().filter(c -> grade.name().equals(c.grade())).count();
    }

    /**
     * A search that cannot be started, errors or never finishes: the files an earlier wording kept are
     * downloaded rather than failing the song -- a few files beat none -- and with none kept the song
     * fails with the given reason, exactly as before files were carried between wordings.
     */
    private DownloadDecision giveUpSearch(DownloadTask task, DownloadFailureCode reason, Instant now) {
        return task.candidates().isEmpty()
                ? new DownloadDecision.Terminal(DownloadStatus.FAILED, reason)
                : startDownload(task, task.candidates(), now);
    }

    private DownloadDecision startDownload(DownloadTask task, List<DownloadCandidate> candidates, Instant now) {
        DownloadTask next = task.withPhase(DownloadPhase.DOWNLOAD_INIT, now);
        return new DownloadDecision.Advance(next.toBuilder()
                .candidates(candidates).candidateIndex(pickCandidate(candidates, 0, now)).retryIndex(0)
                .slskdUsername(null).slskdFilename(null).slskdTransferId(null).lastError(null)
                .progressPercent(BigDecimal.ZERO)
                .build());
    }

    public DownloadDecision afterDownloadInit(DownloadTask task, QueueDownloadResponse response,
                                              Instant now) {
        if (response == null || response.getEnqueued() == null || response.getEnqueued().isEmpty()) {
            return retryOrAdvanceCandidate(task, now);
        }
        TransferedFile enqueued = response.getEnqueued().getFirst();
        DownloadTask next = task.withPhase(DownloadPhase.DOWNLOAD_POLL, now);
        return new DownloadDecision.Advance(next.toBuilder()
                .slskdUsername(enqueued.getUsername())
                .slskdFilename(enqueued.getFilename())
                .slskdTransferId(enqueued.getId())
                .lastError(null)
                .progressPercent(BigDecimal.ZERO)
                .build());
    }

    /** A transfer absent from slskd's list gets its own short-budget branch, not the poll timeout. */
    public DownloadDecision afterDownloadPoll(DownloadTask task, TransferedFile file, Instant now) {
        List<TransferState> states = TransferedFileUtil.getStateList(file);
        if (states.stream().anyMatch(TransferState::isSuccess)) {
            return new DownloadDecision.Terminal(DownloadStatus.SUCCEEDED, null);
        }
        if (states.contains(TransferState.REJECTED)) {
            // The sharer said no ("Transfer rejected: File not shared." -- its share index is
            // stale -- or "Overwhelmed with requests"). Asking for the same file again gets the same
            // answer; measured 27-09-2026, 8 such rejections were each retried in place for nothing.
            return nextCandidate(task, now);
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
        if (observed.isPastBudget(now, downloadBudget)) {
            return new DownloadDecision.Terminal(DownloadStatus.FAILED, DownloadFailureCode.TIMED_OUT);
        }
        // Still waiting on the peer -- "Queued, Remotely", "Requested", "Initializing", or nominally
        // in progress with not one byte moved -- for the whole queued budget. "Queued, Locally" is
        // slskd's own backlog (its download slots are full, the peer has not been asked yet), not this
        // peer's fault, so it is excluded and stays bounded only by the hour above. phase_entered_at
        // is when THIS transfer was enqueued (it resets on every retry and failover), so no new column
        // is needed to know how long we have been in this peer's queue. Straight to the next
        // candidate, not a same-peer retry: a peer that kept us waiting ten minutes will do it again,
        // and the measured case (peer SKYLiGHT_B, 27-09-2026) sat at 0% for the entire hour while
        // seven other candidates were never tried. The sharer is also remembered, so every OTHER
        // song skips it too (see StallingSharers), and DownloadStepExecutor cancels the abandoned
        // transfer in slskd once this decision is out.
        boolean waiting = !states.contains(TransferState.LOCALLY)
                && (!states.contains(TransferState.IN_PROGRESS)
                        || Objects.equals(0L, file.getBytesTransferred()));
        if (waiting && task.isPastBudget(now, queuedBudget)) {
            stallingSharers.markStalled(task.slskdUsername(), now);
            return nextCandidate(task, now);
        }
        return new DownloadDecision.Continue(observed.dueAt(now.plus(downloadPollInterval)));
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
     * reset -- rather than failing the download outright. In {@code SEARCH_POLL} a search is running
     * and the {@code searchBudget} measures it, so retries stop once the budget is spent. In
     * {@code SEARCH_INIT} no search exists yet, and the budget's clock started when the row was
     * created: a song in a large playlist waits minutes for one of the two search slots before its
     * first {@code POST /searches} (28-09-2026: four songs failed on their very first, unretried
     * attempt, 7-21 minutes after creation). So a failed start is retried while the budget lasts, as
     * before, and at least {@code retryLimit} times whatever the wall clock says, {@code
     * searchPollInterval} apart, with the reason kept on the row ({@code lastError}) for the next
     * post-mortem to read. Both cover a dropped or timed-out connection ({@link
     * WebClientRequestException}) and slskd itself erroring the call ({@link
     * WebClientResponseException}, 4xx or 5xx alike: a rejected search is retried the same as a
     * dropped one, on the theory that a transient rejection recovering is worth more than a genuinely
     * bad one failing sooner -- the bounds already limit the cost either way). Anything else is an
     * error shape this code doesn't recognise, so it gives up at once rather than guess.
     */
    private DownloadDecision onSearchCallFailed(DownloadTask task, Throwable error, Instant now) {
        boolean retryable = error instanceof WebClientRequestException
                || error instanceof WebClientResponseException;
        if (!retryable) {
            return giveUpSearch(task, DownloadFailureCode.SEARCH_FAILED, now);
        }
        if (task.phase() == DownloadPhase.SEARCH_INIT) {
            if (task.retryIndex() < retryLimit || !task.isPastBudget(now, searchBudget)) {
                return new DownloadDecision.Continue(task.dueAt(now.plus(searchPollInterval)).toBuilder()
                        .retryIndex(task.retryIndex() + 1).lastError(describe(error)).build());
            }
            // slskd answers 409 when it is not logged in to Soulseek, with the reason in its body: "The
            // server connection must be connected and logged in to perform a search (currently:
            // Disconnected)" (slskd 0.26.0, 04-10-2026). Matched on the status, not the wording, so a
            // reworded slskd still counts; naviseerr never sends a search id, so an id clash, the
            // other 409 slskd could give here, cannot happen.
            return giveUpSearch(task, error instanceof WebClientResponseException.Conflict
                    ? DownloadFailureCode.SOULSEEK_OFFLINE : DownloadFailureCode.SEARCH_FAILED, now);
        }
        if (!task.isPastBudget(now, searchBudget)) {
            return new DownloadDecision.Continue(task.dueAt(now.plus(searchPollInterval)));
        }
        return giveUpSearch(task, DownloadFailureCode.SEARCH_FAILED, now);
    }

    /**
     * slskd's own answer when it gave one ("409 CONFLICT" plus its reason, which the exception's message
     * leaves out), else the exception's message, or its class name when it carries none (a Netty read
     * timeout does not).
     */
    static String describe(Throwable error) {
        if (error instanceof WebClientResponseException refused && !refused.getResponseBodyAsString().isBlank()) {
            return refused.getStatusCode() + " " + refused.getResponseBodyAsString();
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private DownloadDecision retryOrAdvanceCandidate(DownloadTask task, Instant now) {
        if (task.retryIndex() < retryLimit) {
            return new DownloadDecision.Continue(rebuild(task, now, task.candidateIndex(),
                    task.retryIndex() + 1));
        }
        return nextCandidate(task, now);
    }

    private DownloadDecision nextCandidate(DownloadTask task, Instant now) {
        if (task.candidateIndex() + 1 < task.candidates().size()) {
            return new DownloadDecision.Continue(rebuild(task, now,
                    pickCandidate(task.candidates(), task.candidateIndex() + 1, now), 0));
        }
        return new DownloadDecision.Terminal(DownloadStatus.FAILED,
                DownloadFailureCode.SOURCES_EXHAUSTED);
    }

    /**
     * The first candidate at or after {@code from} whose sharer is not currently on the stalling
     * list; if every remaining one is, {@code from} itself -- a slow success beats giving up. The
     * skipped ones are never revisited: a sharer that stalled another song within the last few
     * hours is the worst bet in the list, not a fallback worth keeping.
     */
    private int pickCandidate(List<DownloadCandidate> candidates, int from, Instant now) {
        for (int i = from; i < candidates.size(); i++) {
            if (!stallingSharers.isStalling(candidates.get(i).username(), now)) {
                return i;
            }
        }
        return from;
    }

    /**
     * Back to DOWNLOAD_INIT for the given candidate and attempt. Progress is reset, not carried
     * forward: a retry or a failover starts a new transfer from zero, and the previous one's
     * progress has nothing to do with it.
     */
    private DownloadTask rebuild(DownloadTask task, Instant now, int candidateIndex, int retryIndex) {
        return task.withPhase(DownloadPhase.DOWNLOAD_INIT, now)
                .dueAt(now.plus(downloadPollInterval))
                .withProgressReset()
                .toBuilder()
                .candidateIndex(candidateIndex).retryIndex(retryIndex)
                .slskdUsername(null).slskdFilename(null).slskdTransferId(null).lastError(null)
                .build();
    }
}
