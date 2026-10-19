package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.services.slskd.DownloadQuality;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.settings.InstallSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
 * <p>ponytail: nothing stores the typed text, so a reopened pop-up names the loop's own wording over the
 * manual list (whose slskd id and time it does carry); a {@code search_results_query} column (the next
 * migration) is the fix. And each
 * poll re-reads the search with its responses (about 1 MiB for a popular song, 2-15 times); a summary-only
 * {@code GET /searches/{id}} on {@link SlskdService} is the upgrade if a remote slskd makes that slow.
 */
@Slf4j
@Component
public class ManualSearch {

    /** Naviseerr's own search for this song or album is still running; its result would overwrite the person's. 409. */
    public static final class SearchRunningException extends RuntimeException {
        SearchRunningException(UUID id) {
            super("Naviseerr's own search for " + id + " is still running; wait for it to finish, then search again");
        }
    }

    /** The album picker was asked about an album with no search row (admitted before albums searched as a whole); 409. */
    public static final class NoAlbumSearchException extends RuntimeException {
        NoAlbumSearchException(UUID downloadId) {
            super("Album " + downloadId + " has no album search to keep folders on");
        }
    }

    private final SlskdService slskdService;
    private final SlskdSearchResultProcessor processor;
    private final AlbumFolderPicker picker;
    private final DownloadTaskRepository repository;
    private final InstallSettingsRepository settings;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration budget;
    private final AtomicInteger inFlight = new AtomicInteger();

    public ManualSearch(SlskdService slskdService, SlskdSearchResultProcessor processor, AlbumFolderPicker picker,
                        DownloadTaskRepository repository, InstallSettingsRepository settings, Clock clock,
                        @Value("${download-task.search-poll-interval-ms:2000}") Duration pollInterval,
                        @Value("${download-task.manual-search-budget-ms:30000}") Duration budget) {
        this.slskdService = slskdService;
        this.processor = processor;
        this.picker = picker;
        this.repository = repository;
        this.settings = settings;
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
        return repository.cachedSearch(downloadId, taskId).flatMap(song -> {
            // The loop's own search would write its list (and poll its id) over this one when it completes.
            if (DownloadService.statusOf(song) == PickListStatus.SEARCHING) {
                return Mono.error(new SearchRunningException(taskId));
            }
            // The chosen download quality orders the list as the loop would (one SELECT); nothing is dropped for it.
            return run(text).zipWith(settings.downloadQuality()).flatMap(result -> {
            SearchState state = result.getT1();
            Instant now = clock.instant();
            List<DownloadCandidate> files = processor.allFiles(state, SearchQueryTiers.pickerName(song.songName()), text, result.getT2())
                    .stream().map(DownloadCandidate::from).toList();
            log.info("Manual search '{}' for song {} of download {}: slskd search {} ({}), {} file(s)", text, taskId,
                    downloadId, state.getId(), state.getState(), files.size());
            return repository.saveSearchResults(taskId, files, now, state.getId()).thenReturn(DownloadService.view(song, files,
                    files.isEmpty() ? PickListStatus.NONE : PickListStatus.READY, files.isEmpty() ? "NO_RESULTS" : null,
                    text, state.getId(), now));
            });
        });
    }

    /**
     * The same for a whole album: every folder the search found holding any of the album's songs, the ones
     * the album search itself would take first ({@code judged}), written over the album's remembered folders so
     * {@code POST .../album-pick} accepts any of them, answered as {@code GET .../album-candidates} does with the
     * text as the query. Every song of the album is matched, finished ones too, so "11 of 11" reads right. No
     * stalling-sharer filter: the person sees everything and decides. Nothing is written when no folder holds a
     * song (as the album search step does), so the old list stays. Empty for an unknown download; an error for a
     * download that is not an album, has no album search row to keep folders on, or whose album search still runs.
     */
    public Mono<AlbumCandidatesView> album(UUID downloadId, String text) {
        return repository.cachedAlbumSearch(downloadId).flatMap(album -> {
            if (album.type() != DownloadType.ALBUM) {
                return Mono.error(new DownloadService.NotAnAlbumException(downloadId));
            }
            if (album.phase() == null) {
                return Mono.error(new NoAlbumSearchException(downloadId));
            }
            // The loop's album search would poll this id and write its folders over the person's when it completes.
            if (!"DONE".equals(album.phase())) {
                return Mono.error(new SearchRunningException(downloadId));
            }
            return repository.songs(downloadId).collectList().flatMap(songs -> run(text).zipWith(settings.downloadQuality()).flatMap(result -> {
                SearchState state = result.getT1();
                DownloadQuality chosen = result.getT2();
                Instant now = clock.instant();
                List<SearchResponseItem> responses = state.getResponses() == null ? List.of() : state.getResponses();
                // Off the event loop, as the album search step does: judging 250 responses takes a fifth of a second.
                return Mono.fromCallable(() -> StoredFolder.of(
                                picker.folders(responses, songs, album.title(), album.artists(), sharer -> false, chosen),
                                picker.allFolders(responses, songs, album.title(), album.artists(), chosen)))
                        .subscribeOn(Schedulers.parallel())
                        .flatMap(folders -> {
                            log.info("Manual album search '{}' for download {}: slskd search {} ({}), {} folder(s)", text,
                                    downloadId, state.getId(), state.getState(), folders.size());
                            Mono<Long> saved = folders.isEmpty() ? Mono.just(0L) : repository.saveAlbumFolders(downloadId, folders, now, state.getId());
                            // The view reads status and reason off the row: DONE with these folders, NO_RESULTS when none.
                            DownloadTaskRepository.CachedAlbumSearch found = new DownloadTaskRepository.CachedAlbumSearch(
                                    album.type(), "DONE", folders.isEmpty() ? "NO_RESULTS" : album.outcome(), album.searchTier(),
                                    album.title(), album.artists(), folders, now, state.getId(), now);
                            return saved.then(repository.songPicks(downloadId).collectList())
                                    .map(picks -> DownloadService.albumView(downloadId, found, picks, text));
                        });
            }));
        });
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
                                // One failed read (a timed-out 1 MiB response) is tried again, not the end of the wait.
                                .retry(1)
                                .repeatWhen(polls -> polls.delayElements(pollInterval))
                                .takeUntil(state -> Boolean.TRUE.equals(state.getIsComplete()))
                                .take(budget)
                                .last(started))
                .doOnSubscribe(s -> inFlight.incrementAndGet())
                .doFinally(s -> inFlight.decrementAndGet());
    }
}
