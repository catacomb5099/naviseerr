package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Soulseek search with a person's own words, run while they wait (manual import, 19-10-2026). The
 * loop searches with its own wordings (the title alone, then "title - artist", ...); when those miss,
 * the person types what to search and gets that search's files in the same pop-up. One {@code POST
 * /searches} with the text verbatim, polled until slskd says it is complete or {@code
 * download-task.manual-search-budget-ms} is spent (then what has arrived so far is used), every audio
 * file graded by the existing matcher but all kept (the all-options rule of 08-10-2026), and the list
 * written over the song's remembered one so the existing pick route accepts any of its rows.
 *
 * <p>The one place a search starts outside the loop: {@link DownloadTaskRunner} counts {@link #inFlight()}
 * against {@code max-concurrent-searches} so it starts no new search while a person's runs (slskd runs two
 * at a time and queues the rest, memory of 27-09-2026).
 *
 * <p>ponytail: nothing stores the typed text, so a reopened pop-up names the loop's own wording with the
 * manual list's time; a {@code search_results_query} column (the next migration) is the fix. And each
 * poll re-reads the search with its responses (about 1 MiB for a popular song, 2-15 times); a summary-only
 * {@code GET /searches/{id}} on {@link SlskdService} is the upgrade if a remote slskd makes that slow.
 */
@Slf4j
@Component
public class ManualSearch {

    private final SlskdService slskdService;
    private final SlskdSearchResultProcessor processor;
    private final DownloadTaskRepository repository;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration budget;
    private final AtomicInteger inFlight = new AtomicInteger();

    public ManualSearch(SlskdService slskdService, SlskdSearchResultProcessor processor,
                        DownloadTaskRepository repository, Clock clock,
                        @Value("${download-task.search-poll-interval-ms:2000}") Duration pollInterval,
                        @Value("${download-task.manual-search-budget-ms:30000}") Duration budget) {
        this.slskdService = slskdService;
        this.processor = processor;
        this.repository = repository;
        this.clock = clock;
        this.pollInterval = pollInterval;
        this.budget = budget;
    }

    /** How many person-initiated searches are running right now; the loop leaves them their slot. */
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * Searches with {@code text} for one song and answers the same view {@code GET .../candidates}
     * does, the list being what this search found (replacing the remembered one; an empty result
     * leaves the old list in place, SAVE_SEARCH_RESULTS_SQL's own guard). Empty for an unknown download
     * or song; slskd's refusals and outages come through as {@code WebClientException}s for the
     * controller to word.
     */
    public Mono<TaskCandidatesView> song(UUID downloadId, UUID taskId, String text) {
        return repository.cachedSearch(downloadId, taskId).flatMap(song -> run(text).flatMap(state -> {
            Instant now = clock.instant();
            List<DownloadCandidate> files = processor.allFiles(state, SearchQueryTiers.pickerName(song.songName()), text)
                    .stream().map(DownloadCandidate::from).toList();
            log.info("Manual search '{}' for song {} of download {}: slskd search {} ({}), {} file(s)", text, taskId,
                    downloadId, state.getId(), state.getState(), files.size());
            return repository.saveSearchResults(taskId, files, now).thenReturn(DownloadService.view(song, files,
                    files.isEmpty() ? PickListStatus.NONE : PickListStatus.READY, files.isEmpty() ? "NO_RESULTS" : null,
                    text, state.getId(), now));
        }));
    }

    /**
     * One slskd search, then its state with responses every {@code pollInterval} until it is complete or
     * the budget is spent: the last state seen, so a search still running at the cap yields the answers
     * that had arrived. A search slskd took without an id is an error (nothing to poll).
     */
    Mono<SearchState> run(String text) {
        return slskdService.searchResults(text)
                .flatMap(started -> started.getId() == null || started.getId().isBlank()
                        ? Mono.error(new IllegalStateException("slskd answered the search without an id"))
                        : Mono.defer(() -> slskdService.getSearchWithResponses(started.getId()))
                                .repeatWhen(polls -> polls.delayElements(pollInterval))
                                .takeUntil(state -> Boolean.TRUE.equals(state.getIsComplete()))
                                .take(budget)
                                .last(started))
                .doOnSubscribe(s -> inFlight.incrementAndGet())
                .doFinally(s -> inFlight.decrementAndGet());
    }
}
