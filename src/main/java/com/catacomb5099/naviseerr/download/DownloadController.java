package com.catacomb5099.naviseerr.download;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
public class DownloadController {

    /**
     * Cap on {@code GET /downloads?ids=}. The endpoint exists so a client can reconcile the handful of
     * cards it kept across a restart, which is single digits in practice; the bound is here because an
     * unbounded {@code ANY(:ids)} is the only thing these two read endpoints add that a caller could
     * size themselves.
     */
    static final int MAX_RESOLVE_IDS = 100;

    private final DownloadService downloadService;
    private final ActiveDownloadRepository activeDownloadRepository;
    private final Clock clock;
    private final long pollIntervalMs;
    private final Duration terminalRetention;

    public DownloadController(DownloadService downloadService,
                              ActiveDownloadRepository activeDownloadRepository,
                              Clock clock,
                              @Value("${download-task.download-poll-interval-ms:5000}") Duration pollInterval,
                              @Value("${download-task.terminal-retention-ms:600000}") Duration terminalRetention) {
        this.downloadService = downloadService;
        this.activeDownloadRepository = activeDownloadRepository;
        this.clock = clock;
        this.pollIntervalMs = pollInterval.toMillis();
        this.terminalRetention = terminalRetention;
    }

    /**
     * Requests one track by its YouTube Music {@code videoId}.
     *
     * <p>An id, not a name, and that is the substantive change here: the server now asks
     * ytmusic-adapter what the id is at admission time, instead of the client gluing a title and an
     * artist together into a string the matcher then had to take apart again. It also makes a track
     * whose title contains a {@code /} requestable, which it was not while the title was the path.
     */
    @PostMapping("/download/song/{songId}")
    Mono<ResponseEntity<Download>> downloadSong(@PathVariable String songId) {
        if (songId == null || songId.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return accept(downloadService.requestDownload(songId, DownloadType.SONG));
    }

    /**
     * Requests every track of an album or a playlist as ONE download. The response is a single
     * {@link Download}; its songs do not exist yet, because the track list is not fetched until the
     * loop admits it.
     *
     * <p>{@code type} is required rather than inferred from the id: albums and playlists are two
     * different ytmusic-adapter endpoints, and guessing from an id prefix would be a heuristic that
     * silently picks the wrong one the first time YouTube changes a prefix. An unparseable value is
     * rejected by Spring before this method runs; {@code SONG} is rejected here, since a single
     * track has its own route.
     */
    @PostMapping("/download/collection/{collectionId}")
    Mono<ResponseEntity<Download>> downloadCollection(@PathVariable String collectionId,
                                                      @RequestParam DownloadType type) {
        if (collectionId == null || collectionId.isBlank() || !type.isCollection()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return accept(downloadService.requestDownload(collectionId, type));
    }

    /** Fast ack: the row is inserted, nothing else happens on the request thread. */
    private Mono<ResponseEntity<Download>> accept(Mono<Download> saved) {
        return saved.map(download -> ResponseEntity.status(HttpStatus.ACCEPTED).body(download))
                .onErrorResume(error -> Mono.just(
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()));
    }

    /**
     * Everything the client should currently be showing: every download that has not finished, plus
     * every download that finished within {@code terminalRetention}.
     *
     * <p>The window is the whole reason this works without the server tracking who has seen what. A
     * finished download used to vanish from this response the instant it completed, so the outcome the
     * user was waiting for was the one thing the feed never delivered. Keeping it here briefly means
     * any client polling at any cadence sees the transition, with no per-client state, no acknowledgement
     * protocol, and no client identity to get wrong when the same user has two tabs open.
     */
    @GetMapping("/downloads/active")
    Mono<ActiveDownloadsResponse> activeDownloads() {
        return activeDownloadRepository.findActive(clock.instant().minus(terminalRetention))
                .collectList()
                .map(downloads -> new ActiveDownloadsResponse(pollIntervalMs,
                        terminalRetention.toMillis(), downloads));
    }

    /**
     * Resolves specific downloads by id, ignoring both the terminal filter and the retention window.
     *
     * <p>This is what makes a client's stored cards honest across a restart rather than merely
     * persistent. A client that was closed for an hour holds cards whose outcome has long since aged out
     * of {@code /downloads/active}; without this it can only guess between "finished while I was gone"
     * and "still running", and either guess is wrong half the time. Ids with no row are omitted rather
     * than 404'd, so absence from this response -- and only this response -- means "gone for good".
     */
    @GetMapping("/downloads")
    Mono<ResponseEntity<DownloadsByIdResponse>> downloadsByIds(@RequestParam List<UUID> ids) {
        if (ids.isEmpty() || ids.size() > MAX_RESOLVE_IDS) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return activeDownloadRepository.findByIds(ids)
                .collectList()
                .map(downloads -> ResponseEntity.ok(new DownloadsByIdResponse(downloads)));
    }

    /**
     * One download with every song under it. This is where a collection's "9 of 12" is broken down:
     * which three, at what stage, failing how, from which peer. Aimed at the person running the
     * instance, so it reports the pipeline's own bookkeeping rather than a summary of it.
     *
     * <p>The card is the same shape the feed returns, so a client can open a detail view from a card
     * it already holds and reconcile the two without a mapping step. Songs are empty, not absent, for
     * a download that has not been admitted yet.
     */
    @GetMapping("/downloads/{id}")
    Mono<ResponseEntity<DownloadDetailView>> downloadDetail(@PathVariable UUID id) {
        return activeDownloadRepository.findByIds(List.of(id)).next()
                .zipWith(activeDownloadRepository.findSongs(id).collectList(),
                        DownloadDetailView::new)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/downloads/all")
    Mono<ResponseEntity<AllDownloadsResponse>> allDownloads(
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "1") Integer pageNumber) {
        if (pageSize < 1 || pageNumber < 1) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return activeDownloadRepository.findAll(pageSize, pageNumber)
                .map(ResponseEntity::ok);
    }
}
