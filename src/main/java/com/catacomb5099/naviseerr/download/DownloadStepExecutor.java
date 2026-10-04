package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.util.TransferedFileUtil;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The I/O shell around {@link DownloadStateMachine}. {@code SEARCH_POLL} and {@code DOWNLOAD_POLL}
 * make no slskd call of their own — they read from {@code searchesById}/{@code transfersById}, which
 * {@link DownloadTaskRunner} fetches once per pass via the two batched slskd calls. {@code SEARCH_INIT}
 * and {@code DOWNLOAD_INIT} are not batchable in slskd's API, so those two still call directly --
 * except a {@code DOWNLOAD_INIT} held back by {@link SharerLoad}, which calls nothing.
 *
 * <p>Never returns an error signal — an slskd failure is a decision too, so the caller always has
 * something to write.
 */
@Slf4j
@Component
public class DownloadStepExecutor {

    private final SlskdService slskdService;
    private final SlskdSearchResultProcessor searchResultProcessor;
    private final DownloadStateMachine stateMachine;
    private final Clock clock;

    public DownloadStepExecutor(SlskdService slskdService,
                                SlskdSearchResultProcessor searchResultProcessor,
                                DownloadStateMachine stateMachine,
                                Clock clock) {
        this.slskdService = slskdService;
        this.searchResultProcessor = searchResultProcessor;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    public Mono<DownloadDecision> execute(DownloadTask task, Map<String, SearchState> searchesById,
                                          Map<String, TransferedFile> transfersById, SharerLoad sharers) {
        Instant now = clock.instant();
        return step(task, searchesById, transfersById, sharers, now)
                .onErrorResume(error -> {
                    log.warn("Step {} for download {} failed", task.phase(), task.downloadId(), error);
                    return Mono.just(stateMachine.onCallFailed(task, error, now));
                });
    }

    private Mono<DownloadDecision> step(DownloadTask task, Map<String, SearchState> searchesById,
                                        Map<String, TransferedFile> transfersById, SharerLoad sharers,
                                        Instant now) {
        return switch (task.phase()) {
            case SEARCH_INIT -> slskdService.searchResults(task.searchQuery())
                    .map(state -> stateMachine.afterSearchInit(task, state, now));

            // A missing entry (task.searchId() not in the map) is passed through as null and handled
            // by decideAfterSearchPoll/the state machine identically to "still running" — see the
            // Javadoc on DownloadStateMachine.afterSearchPoll.
            case SEARCH_POLL -> decideAfterSearchPoll(task, searchesById.get(task.searchId()), now);

            // No intent write before this call: an occasional duplicate download after a crash mid-
            // enqueue is an accepted cost, not guarded against. See the note under DownloadTask.
            //
            // Mono.defer wraps this so task.currentCandidate() is evaluated lazily, inside the
            // reactive chain, rather than synchronously while building the switch expression. A
            // synchronous throw here (e.g. IndexOutOfBoundsException from a corrupt/out-of-range
            // candidateIndex) would escape execute() before the onErrorResume below ever sees it,
            // aborting the whole pass exactly like the row-mapping bug this defer is paired with.
            case DOWNLOAD_INIT -> Mono.defer(() -> {
                DownloadCandidate candidate = task.currentCandidate();
                return stateMachine.beforeDownloadInit(task, sharers.take(candidate.username()), now)
                        .map(Mono::just)
                        .orElseGet(() -> slskdService
                                .enqueueDownload(candidate.username(), candidate.toSearchFile())
                                .map(response -> stateMachine.afterDownloadInit(task, response, now)));
            });

            // Same "missing means still running" handling as SEARCH_POLL, via TransferedFileUtil's
            // existing null-safety — see the Javadoc on DownloadStateMachine.afterDownloadPoll.
            case DOWNLOAD_POLL -> Mono.fromSupplier(() -> {
                TransferedFile file = transfersById.get(task.slskdTransferId());
                DownloadDecision decision =
                        stateMachine.afterDownloadPoll(task, file, sharers.delivering(), now);
                cancelIfAbandoned(task, file, decision);
                return decision;
            });
        };
    }

    /**
     * When a poll decides to stop waiting on a transfer that slskd still has running -- a sharer
     * that queued us past the queued budget, or the hour-long download budget running out -- tell
     * slskd to cancel it. Otherwise the request keeps our place in that sharer's queue and sits in
     * slskd's list forever (58 of them after the 27-09-2026 evening). Fire-and-forget: the decision
     * is already made and is written whether or not slskd hears this; a failure is a WARN, nothing
     * more. A transfer slskd reports as Completed (succeeded, errored, rejected) needs no cancel.
     */
    private void cancelIfAbandoned(DownloadTask task, TransferedFile file, DownloadDecision decision) {
        boolean stillPolling = decision instanceof DownloadDecision.Continue proceed
                && proceed.next().phase() == DownloadPhase.DOWNLOAD_POLL;
        List<TransferState> states = TransferedFileUtil.getStateList(file);
        if (stillPolling || states.isEmpty() || states.contains(TransferState.COMPLETED)) {
            return;
        }
        Mono.defer(() -> slskdService.cancelDownload(file.getUsername(), file.getId()))
                .subscribe(ignored -> { },
                        error -> log.warn("Could not cancel abandoned transfer {} from '{}' for "
                                + "download {}; it stays in slskd's list", file.getId(),
                                file.getUsername(), task.downloadId(), error),
                        () -> log.info("Cancelled abandoned transfer {} from '{}' for download {}",
                                file.getId(), file.getUsername(), task.downloadId()));
    }

    private Mono<DownloadDecision> decideAfterSearchPoll(DownloadTask task, SearchState state,
                                                         Instant now) {
        if (state == null || !Boolean.TRUE.equals(state.getIsComplete())) {
            return Mono.just(stateMachine.afterSearchPoll(task, state, List.of(), now));
        }
        // The batched GET /searches carries isComplete but NOT responses — it has no
        // includeResponses parameter and always returns that list empty. Selecting straight off the
        // batched state therefore finds zero candidates for every search, however many results it
        // really got, and the download dies on NO_CANDIDATES seconds after the search completes.
        // So the batch decides *when* to select; this single GET supplies *what* to select from.
        // Costs one extra call per download, on the completion transition only, not per poll.
        return slskdService.getSearchWithResponses(task.searchId())
                .doOnNext(full -> log.info(
                        "Search {} for download {} complete (state='{}'); summary reported "
                                + "responseCount={} fileCount={} with {} response(s) inlined, refetch "
                                + "returned {} response(s)",
                        task.searchId(), task.downloadId(), full.getState(), full.getResponseCount(),
                        full.getFileCount(), size(state.getResponses()), size(full.getResponses())))
                // Judged against the cleaned song name, whatever was searched: the search is
                // deliberately loose (the title alone first, then "title - artist"), and the cleaned
                // name still carries the qualifier -- "(Remix)", "(Live)" -- the picker needs to
                // choose the right version, plus the artist it must insist on for a title-only
                // search. Never the raw name: "Neon Indian - Polish Girl -
                // toomainstream" made the picker read "Neon Indian" as the title and reject all 300
                // files Soulseek offered (post-mortem of 28-09-2026). The wording goes along too: when
                // it did not name the artist, the picker requires the artist in the file's path.
                .flatMap(full -> searchResultProcessor.selectBestFiles(full,
                                SearchQueryTiers.pickerName(task.songName()), task.searchQuery())
                        .map(selected -> selected.stream().map(DownloadCandidate::from).toList())
                        .map(candidates -> stateMachine.afterSearchPoll(task, full, candidates, now)));
    }

    /** The batched summary leaves {@code responses} null on some slskd versions and empty on others. */
    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
