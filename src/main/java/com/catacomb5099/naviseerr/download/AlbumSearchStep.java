package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P5, whole album first, and P6, part album next: one album download's search for a sharer holding every
 * song, or failing that the most of them, stepped by {@link DownloadTaskRunner} on the same two search
 * slots the songs use. While it runs, the album's songs are held (their {@code next_attempt_at} lies
 * {@link #holdUntil} ahead, re-extended at each wording), so they do not start searches of their own. It
 * ends by releasing them in one statement: songs the best folder holds go straight to downloading that
 * folder's file, the rest are due now and search on their own as before. If this step dies, the hold
 * simply runs out and every song searches on its own.
 *
 * <p>Its own I/O shell and decisions in one place: two phases, one slskd call each, and every way out
 * leads to the same release.
 */
@Slf4j
@Component
public class AlbumSearchStep {

    /** A song gets its file in the best folder plus the same track in the next two (other sharers). */
    static final int CANDIDATES_PER_SONG = 3;

    private final DownloadTaskRepository repository;
    private final SlskdService slskdService;
    private final AlbumFolderPicker picker;
    private final StallingSharers stallingSharers;
    private final Duration searchPollInterval;
    private final Duration searchBudget;

    public AlbumSearchStep(DownloadTaskRepository repository, SlskdService slskdService,
                           AlbumFolderPicker picker, StallingSharers stallingSharers,
                           @Value("${download-task.search-poll-interval-ms:2000}") Duration searchPollInterval,
                           @Value("${download-task.search-budget-ms:120000}") Duration searchBudget) {
        this.repository = repository;
        this.slskdService = slskdService;
        this.picker = picker;
        this.stallingSharers = stallingSharers;
        this.searchPollInterval = searchPollInterval;
        this.searchBudget = searchBudget;
    }

    /**
     * How long an album's songs wait for the album search: two search budgets, one for a search slot to
     * free up and one for the search. No setting of its own.
     */
    public Instant holdUntil(Instant now) {
        return now.plus(searchBudget.multipliedBy(2));
    }

    /** Never errors: a failed step is logged and its lease runs out, so the next pass retries it. */
    public Mono<Void> step(AlbumSearch album, Map<String, SearchState> searchesById, Instant now, String owner) {
        Mono<Void> work = album.phase() == DownloadPhase.SEARCH_INIT
                ? start(album, now, owner)
                : poll(album, searchesById.get(album.searchId()), now, owner);
        return work.onErrorResume(error -> {
            log.error("Album search of download {} ('{}', step {}) could not be applied; the lease will "
                    + "expire and it will be retried", album.downloadId(), album.title(), album.phase(), error);
            return Mono.empty();
        });
    }

    /**
     * One {@code POST /searches}, run inside the runner's one-at-a-time search section. Nothing left to
     * search for (every song already started, or was cancelled) ends it without searching. A search slskd
     * will not start is not retried: the songs search on their own, which is what they did before.
     */
    private Mono<Void> start(AlbumSearch album, Instant now, String owner) {
        if (album.title() == null) {
            return finish(album, AlbumSearch.Outcome.SEARCH_FAILED, Map.of(), now, owner);
        }
        return repository.waitingAlbumSongs(album.downloadId(), now).hasElements()
                .flatMap(waiting -> !waiting
                        ? finish(album, AlbumSearch.Outcome.NOTHING_TO_SEARCH, Map.of(), now, owner)
                        : slskdService.searchResults(album.searchQuery())
                                .flatMap(started -> {
                                    if (started.getId() == null || started.getId().isBlank()) {
                                        return finish(album, AlbumSearch.Outcome.SEARCH_FAILED, Map.of(), now, owner);
                                    }
                                    log.info("Album '{}' of download {}: looking for one sharer with every song ('{}')",
                                            album.title(), album.downloadId(), album.searchQuery());
                                    return repository.saveAlbumSearch(album.toBuilder()
                                            .phase(DownloadPhase.SEARCH_POLL).searchId(started.getId())
                                            .phaseEnteredAt(now).nextAttemptAt(now).build(), owner, now, holdUntil(now))
                                            .then();
                                })
                                .onErrorResume(error -> {
                                    // slskd saying no (409 while Soulseek is offline) already says why; its
                                    // stack trace is 48 lines of nothing more. Anything else keeps it.
                                    if (error instanceof WebClientResponseException refused) {
                                        log.warn("Album search of download {} could not be started; its songs will "
                                                + "search on their own: slskd answered {} {}", album.downloadId(),
                                                refused.getStatusCode(), refused.getResponseBodyAsString());
                                    } else {
                                        log.warn("Album search of download {} could not be started; its songs will "
                                                + "search on their own", album.downloadId(), error);
                                    }
                                    return finish(album, AlbumSearch.Outcome.SEARCH_FAILED, Map.of(), now, owner);
                                }));
    }

    /**
     * Reads the batched {@code GET /searches} summary. Once the search is complete, or has had its
     * search budget (slskd leaves some searches "InProgress" for days), its responses are fetched once and
     * judged: nobody answered and another wording exists, try that; otherwise release the songs.
     */
    private Mono<Void> poll(AlbumSearch album, SearchState summary, Instant now, String owner) {
        boolean complete = summary != null && Boolean.TRUE.equals(summary.getIsComplete());
        if (!complete && !album.isPastBudget(now, searchBudget)) {
            return repository.saveAlbumSearch(album.toBuilder().nextAttemptAt(now.plus(searchPollInterval)).build(),
                    owner, now, null).then();
        }
        return slskdService.getSearchWithResponses(album.searchId())
                .zipWith(repository.waitingAlbumSongs(album.downloadId(), now).collectList())
                .flatMap(found -> judge(album, found.getT1(), found.getT2(), now, owner))
                .onErrorResume(error -> {
                    if (album.isPastBudget(now, searchBudget)) {
                        log.warn("Album search {} of download {} could not be read; its songs will search on "
                                + "their own", album.searchId(), album.downloadId(), error);
                        return finish(album, AlbumSearch.Outcome.SEARCH_FAILED, Map.of(), now, owner);
                    }
                    return repository.saveAlbumSearch(album.toBuilder()
                            .nextAttemptAt(now.plus(searchPollInterval)).build(), owner, now, null).then();
                });
    }

    private Mono<Void> judge(AlbumSearch album, SearchState full, List<DownloadTask> songs, Instant now,
                             String owner) {
        List<SearchResponseItem> responses = full.getResponses() == null ? List.of() : full.getResponses();
        if (responses.isEmpty() && album.hasAnotherWording() && !songs.isEmpty()) {
            log.info("Album '{}' of download {}: nobody answered '{}'; trying '{}'", album.title(),
                    album.downloadId(), album.searchQuery(),
                    album.toBuilder().searchTier(album.searchTier() + 1).build().searchQuery());
            // A new wording is a new search: back to SEARCH_INIT for a free slot, with a fresh budget.
            return repository.saveAlbumSearch(album.toBuilder().phase(DownloadPhase.SEARCH_INIT)
                    .searchTier(album.searchTier() + 1).searchId(null).phaseEnteredAt(now).nextAttemptAt(now)
                    .build(), owner, now, null).then();
        }
        if (songs.isEmpty()) {
            return finish(album, AlbumSearch.Outcome.NOTHING_TO_SEARCH, Map.of(), now, owner);
        }
        // Off the event loop: judging 250 responses (about 5,000 files) takes a fifth of a second.
        return Mono.fromCallable(() -> picker.folders(responses, songs, album.title(), album.artists(),
                        sharer -> stallingSharers.isStalling(sharer, now)))
                .subscribeOn(Schedulers.parallel())
                .flatMap(folders -> settle(album, responses.size(), songs.size(), folders, now, owner));
    }

    private Mono<Void> settle(AlbumSearch album, int responses, int songs, List<AlbumFolderPicker.Folder> folders,
                               Instant now, String owner) {
        if (folders.isEmpty()) {
            log.info("Album '{}' of download {}: no sharer among {} has all {} song(s), or half of them; each "
                    + "searches on its own", album.title(), album.downloadId(), responses, songs);
            return finish(album, AlbumSearch.Outcome.NO_WHOLE_FOLDER, Map.of(), now, owner);
        }
        AlbumFolderPicker.Folder best = folders.getFirst();
        boolean whole = best.files().size() == songs;
        log.info("Album '{}' of download {}: {} folder(s); {} of {} song(s) from '{}' ({}), {} other file(s) "
                        + "there left alone{}", album.title(), album.downloadId(), folders.size(),
                best.files().size(), songs, best.peer().getUsername(), best.path(), best.extras(),
                whole ? "" : "; the rest search on their own");
        // P6: only the songs the best folder holds; a song only a later folder has searches on its own
        // rather than coming from yet another sharer's folder.
        Map<UUID, List<DownloadCandidate>> picks = AlbumFolderPicker.candidates(folders, CANDIDATES_PER_SONG);
        picks.keySet().retainAll(best.files().keySet());
        return finish(album, whole ? AlbumSearch.Outcome.WHOLE_FOLDER : AlbumSearch.Outcome.PART_FOLDER,
                picks, now, owner);
    }

    private Mono<Void> finish(AlbumSearch album, AlbumSearch.Outcome outcome,
                              Map<UUID, List<DownloadCandidate>> picks, Instant now, String owner) {
        return repository.releaseAlbumSongs(album.downloadId(), owner, outcome, picks, now)
                .doOnNext(released -> log.debug("Album search of download {} ended {}; {} song(s) released",
                        album.downloadId(), outcome, released))
                .then();
    }
}
