package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdQueryBuilder;
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
 * and {@code DOWNLOAD_INIT} are not batchable in slskd's API, so those two still call directly.
 *
 * <p>Never returns an error signal — an slskd failure is a decision too, so the caller always has
 * something to write.
 */
@Slf4j
@Component
public class DownloadStepExecutor {

    private final SlskdService slskdService;
    private final SlskdSearchResultProcessor searchResultProcessor;
    private final SlskdQueryBuilder queryBuilder;
    private final DownloadStateMachine stateMachine;
    private final Clock clock;

    public DownloadStepExecutor(SlskdService slskdService,
                                SlskdSearchResultProcessor searchResultProcessor,
                                SlskdQueryBuilder queryBuilder,
                                DownloadStateMachine stateMachine,
                                Clock clock) {
        this.slskdService = slskdService;
        this.searchResultProcessor = searchResultProcessor;
        this.queryBuilder = queryBuilder;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    public Mono<DownloadDecision> execute(DownloadTask task, Map<String, SearchState> searchesById,
                                          Map<String, TransferedFile> transfersById) {
        Instant now = clock.instant();
        return step(task, searchesById, transfersById, now)
                .onErrorResume(error -> {
                    log.warn("Step {} for download {} failed", task.phase(), task.downloadId(), error);
                    return Mono.just(stateMachine.onCallFailed(task, error, now));
                });
    }

    private Mono<DownloadDecision> step(DownloadTask task, Map<String, SearchState> searchesById,
                                        Map<String, TransferedFile> transfersById, Instant now) {
        return switch (task.phase()) {
            case SEARCH_INIT -> slskdService.searchResults(queryBuilder.build(task.query()))
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
            case DOWNLOAD_INIT -> Mono.defer(() -> slskdService.enqueueDownload(
                            task.currentCandidate().username(), task.currentCandidate().toSearchFile())
                    .map(response -> stateMachine.afterDownloadInit(task, response, now)));

            // Same "missing means still running" handling as SEARCH_POLL, via TransferedFileUtil's
            // existing null-safety — see the Javadoc on DownloadStateMachine.afterDownloadPoll.
            case DOWNLOAD_POLL -> Mono.just(stateMachine.afterDownloadPoll(
                    task, transfersById.get(task.slskdTransferId()), now));
        };
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
                .flatMap(full -> searchResultProcessor.selectBestFiles(full, task.query())
                        .map(selected -> selected.stream().map(DownloadCandidate::from).toList())
                        .map(candidates -> stateMachine.afterSearchPoll(task, full, candidates, now)));
    }

    /** The batched summary leaves {@code responses} null on some slskd versions and empty on others. */
    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
