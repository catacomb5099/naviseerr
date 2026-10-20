package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.services.slskd.DownloadQuality;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.settings.InstallSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
 * <p>The typed text and slskd's id are stored with the list (V21), so a reopened pop-up names this search.
 *
 * <p>The search belongs to the server, not to the request (20-10-2026): it is subscribed here and runs to the end
 * whoever waits, so closing the pop-up or reloading the page loses nothing. While it runs the song (or album) is
 * registered by its id, a second search for it is refused, and {@link #overlay} makes the candidate GETs answer
 * SEARCHING with the typed text; a search that fails with nobody waiting is kept as {@code searchError} until the next.
 * ponytail: the registry lives in memory, so a restart mid-search loses that search (at most ~45 s of work).
 *
 * <p>ponytail: each poll re-reads the search with its responses (about 1 MiB for a popular song, 2-15 times); a summary-only
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

    /** The person's own previous search for this song or album is still running; 409 MANUAL_SEARCH_RUNNING. */
    public static final class AlreadyRunningException extends RuntimeException {
        AlreadyRunningException() {
            super("Your last search is still running. Its files show here when it finishes.");
        }
    }

    /** How slskd's refusal reads to a person: the HTTP status, a code and a sentence (shared by the POST and {@link #overlay}). */
    public record Refusal(HttpStatus status, String reason, String message) {}

    private record Running(String text, Instant startedAt) {}

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
    /** Searches running now, by task id (a song) or download id (an album). */
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();
    /** The sentence for the last search that failed, by the same ids, until the next one starts. */
    private final Map<UUID, String> failed = new ConcurrentHashMap<>();

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
        return running.size();
    }

    /**
     * slskd's answer in a sentence a person can read: its 409 is "not logged in to Soulseek" (the same reading as
     * {@code DownloadStateMachine.onSearchCallFailed}), its 429 "a third search at once"; anything else is slskd not
     * answering. slskd's own words (its response body, unquoted) follow after a colon when it gave any.
     */
    public static Refusal refusal(Throwable error) {
        String words = error instanceof WebClientResponseException refused
                ? refused.getResponseBodyAsString().strip().replaceAll("^\"|\"$", "") : "";
        String tail = words.isBlank() ? "." : ": " + words;
        if (error instanceof WebClientResponseException.Conflict) {
            return new Refusal(HttpStatus.CONFLICT, "SOULSEEK_OFFLINE", "Soulseek is offline right now, so the search could not run" + tail);
        }
        if (error instanceof WebClientResponseException.TooManyRequests) {
            return new Refusal(HttpStatus.TOO_MANY_REQUESTS, "SOULSEEK_BUSY", "Soulseek is busy with other searches; try again in a moment" + tail);
        }
        if (error instanceof WebClientException) {
            return new Refusal(HttpStatus.BAD_GATEWAY, "SLSKD_UNREACHABLE", "slskd did not answer, so the search could not run" + tail);
        }
        return new Refusal(HttpStatus.INTERNAL_SERVER_ERROR, "SEARCH_FAILED", "The search could not run.");
    }

    /** The song's view as the pop-up should see it: SEARCHING with the typed text while the person's search runs. */
    public TaskCandidatesView overlay(TaskCandidatesView view) {
        Running now = running.get(view.taskId());
        if (now != null) {
            return new TaskCandidatesView(view.taskId(), PickListStatus.SEARCHING, null, now.text(), null, now.startedAt(),
                    view.songStage(), view.current(), List.of(), null);
        }
        String error = failed.get(view.taskId());
        return error == null ? view : new TaskCandidatesView(view.taskId(), view.status(), view.reason(), view.query(),
                view.searchId(), view.searchedAt(), view.songStage(), view.current(), view.candidates(), error);
    }

    /** The same for an album's view, keyed by the download. */
    public AlbumCandidatesView overlay(AlbumCandidatesView view) {
        Running now = running.get(view.downloadId());
        if (now != null) {
            return new AlbumCandidatesView(view.downloadId(), PickListStatus.SEARCHING, null, now.text(), null,
                    now.startedAt(), view.songCount(), List.of(), null);
        }
        String error = failed.get(view.downloadId());
        return error == null ? view : new AlbumCandidatesView(view.downloadId(), view.status(), view.reason(), view.query(),
                view.searchId(), view.searchedAt(), view.songCount(), view.folders(), error);
    }

    /**
     * Runs {@code work} on the server whoever waits: the returned Mono is a replay of it, so a waiter that goes away
     * (closed pop-up, reloaded page, client timeout) only stops waiting.
     */
    private static <T> Mono<T> detached(Mono<T> work) {
        Mono<T> shared = work.cache();
        // The waiter, when there is one, words the error; overlay() keeps it for the pop-up that reopens.
        shared.subscribe(value -> { }, error -> log.debug("A manual search ended with {}", error.toString()));
        return shared;
    }

    /** Registers the search under {@code id} for as long as {@code work} runs, or refuses when one already does. */
    private <T> Mono<T> tracked(UUID id, String text, Supplier<Mono<T>> work) {
        return Mono.defer(() -> {
            if (running.putIfAbsent(id, new Running(text, clock.instant())) != null) {
                return Mono.error(new AlreadyRunningException());
            }
            failed.remove(id);
            return work.get()
                    .doOnError(error -> failed.put(id, "Your search for “" + text + "” failed: " + refusal(error).message()))
                    .doFinally(signal -> running.remove(id));
        });
    }

    /**
     * Searches with {@code text} for one song and answers the same view {@code GET .../candidates}
     * does, the list being what this search found (replacing the remembered one; an empty result
     * leaves the old list in place, SAVE_SEARCH_RESULTS_SQL's own guard). Empty for an unknown download
     * or song; slskd's refusals and outages come through as {@code WebClientException}s for the
     * controller to word.
     */
    public Mono<TaskCandidatesView> song(UUID downloadId, UUID taskId, String text) {
        return detached(repository.cachedSearch(downloadId, taskId).flatMap(song -> {
            // The loop's own search would write its list (and poll its id) over this one when it completes.
            if (DownloadService.statusOf(song) == PickListStatus.SEARCHING) {
                return Mono.error(new SearchRunningException(taskId));
            }
            // The chosen download quality orders the list as the loop would (one SELECT); nothing is dropped for it.
            return tracked(taskId, text, () -> run(text).zipWith(settings.downloadQuality()).flatMap(result -> {
            SearchState state = result.getT1();
            Instant now = clock.instant();
            List<DownloadCandidate> files = processor.allFiles(state, SearchQueryTiers.pickerName(song.songName()), text, result.getT2())
                    .stream().map(DownloadCandidate::from).toList();
            log.info("Manual search '{}' for song {} of download {}: slskd search {} ({}), {} file(s)", text, taskId,
                    downloadId, state.getId(), state.getState(), files.size());
            return repository.saveSearchResults(taskId, files, now, text, state.getId()).thenReturn(DownloadService.view(song, files,
                    files.isEmpty() ? PickListStatus.NONE : PickListStatus.READY, files.isEmpty() ? "NO_RESULTS" : null,
                    text, state.getId(), now));
            }));
        }));
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
        return detached(repository.cachedAlbumSearch(downloadId).flatMap(album -> {
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
            return tracked(downloadId, text, () -> repository.songs(downloadId).collectList().flatMap(songs -> run(text).zipWith(settings.downloadQuality()).flatMap(result -> {
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
                            Mono<Long> saved = folders.isEmpty() ? Mono.just(0L) : repository.saveAlbumFolders(downloadId, folders, now, text, state.getId());
                            // The view reads status and reason off the row: DONE with these folders, NO_RESULTS when none.
                            DownloadTaskRepository.CachedAlbumSearch found = new DownloadTaskRepository.CachedAlbumSearch(
                                    album.type(), "DONE", folders.isEmpty() ? "NO_RESULTS" : album.outcome(), album.searchTier(),
                                    album.title(), album.artists(), folders, now, state.getId(), now, text, state.getId());
                            return saved.then(repository.songPicks(downloadId).collectList())
                                    .map(picks -> DownloadService.albumView(downloadId, found, picks));
                        });
            })));
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
                                // One failed read (a timed-out 1 MiB response) is tried again, not the end of the wait.
                                .retry(1)
                                .repeatWhen(polls -> polls.delayElements(pollInterval))
                                .takeUntil(state -> Boolean.TRUE.equals(state.getIsComplete()))
                                .take(budget)
                                .last(started));
    }
}
