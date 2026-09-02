package com.catacomb5099.naviseerr.download;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
     * Superseded by {@link #requestDownload(DownloadRequest)}. A song title containing {@code /}
     * cannot be represented as a path variable under any encoding, so requests for such songs are
     * simply unrepresentable here -- the reason the body-based route exists at all.
     *
     * <p>Kept working exactly as before -- empty artists, no image -- because a self-hoster who
     * pulls a new server image can still be running an old client image for a while; breaking this
     * outright would break every in-flight download request for such a client. Slated for removal
     * once the client no longer calls it; see
     * {@code docs/superpowers/plans/2026-08-31-download-request-metadata.md}.
     */
    @Deprecated
    @PostMapping("/download/{songName}")
    Mono<ResponseEntity<Download>> download(@PathVariable String songName) {
        if (songName == null || songName.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return downloadService.requestDownload(songName)
                .map(saved -> ResponseEntity.status(HttpStatus.ACCEPTED).body(saved))
                .onErrorResume(error -> Mono.just(
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()));
    }

    /**
     * Body-based replacement for the deprecated path-based route above: the client sends the song
     * name, artist list, and cover image it actually has, instead of gluing them into one string
     * the server then has to guess how to split -- and instead of being unable to request a song
     * whose title contains {@code /} at all.
     */
    @PostMapping("/download")
    Mono<ResponseEntity<Download>> requestDownload(@RequestBody DownloadRequest request) {
        if (request == null || request.songName() == null || request.songName().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return downloadService.requestDownload(request.songName(), request.artists(), request.imageUrl())
                .map(saved -> ResponseEntity.status(HttpStatus.ACCEPTED).body(saved))
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
