package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Every pass asks the database what is due and acts on the answer; nothing is held in memory between passes. */
@Slf4j
@Component
public class DownloadTaskRunner {

    private final DownloadTaskRepository repository;
    private final DownloadStepExecutor executor;
    private final DownloadService downloadService;
    private final SlskdService slskdService;
    private final YtMusicService ytMusicService;
    private final Clock clock;
    private final Duration loopInterval;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxConcurrentDownloads;
    private final int maxConcurrentTransfers;
    /** Identifies this process in lease_owner. Nothing depends on it surviving a restart. */
    private final String instanceId = UUID.randomUUID().toString();
    private Disposable subscription;

    public DownloadTaskRunner(
            DownloadTaskRepository repository,
            DownloadStepExecutor executor,
            DownloadService downloadService,
            SlskdService slskdService,
            YtMusicService ytMusicService,
            Clock clock,
            @Value("${download-task.loop-interval-ms:2000}") Duration loopInterval,
            @Value("${download-task.batch-size:10}") int batchSize,
            @Value("${download-task.lease-duration-ms:60000}") Duration leaseDuration,
            @Value("${download-task.max-concurrent-downloads:20}") int maxConcurrentDownloads,
            @Value("${download-task.max-concurrent-transfers:20}") int maxConcurrentTransfers) {
        this.repository = repository;
        this.executor = executor;
        this.downloadService = downloadService;
        this.slskdService = slskdService;
        this.ytMusicService = ytMusicService;
        this.clock = clock;
        this.loopInterval = loopInterval;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxConcurrentDownloads = maxConcurrentDownloads;
        this.maxConcurrentTransfers = maxConcurrentTransfers;
    }

    @PostConstruct
    void start() {
        subscription = Flux.interval(loopInterval)
                .onBackpressureDrop()
                .concatMap(tick -> pass())
                .subscribe();
    }

    /**
     * Three steps, in order, every tick: admit new requests, step whatever is due, then conclude any
     * download whose songs have all finished. Conclusion runs last so a download that finished
     * during this very pass reports its outcome on the same tick rather than lingering a full
     * interval in IN_PROGRESS -- and runs unconditionally, so one that was missed (a crash between
     * the last task's terminal write and here) is picked up by the next pass regardless.
     */
    Mono<Void> pass() {
        Instant now = clock.instant();
        return admit(now)
                .then(stepDueTasks(now))
                .then(repository.concludeDownloads()
                        .doOnNext(concluded -> {
                            if (concluded > 0) log.info("Concluded {} download(s)", concluded);
                        })
                        .then())
                .onErrorResume(error -> {
                    log.error("Download task pass failed", error);
                    return Mono.empty();
                });
    }

    /**
     * Claims only up to remaining capacity, so no download is ever admitted and then rejected — which
     * is why nothing needs to revert a row to PENDING. Counts in-flight DOWNLOADS, not tasks: a
     * 500-song collection is one download, so it cannot starve admission for everything else.
     */
    private Mono<Void> admit(Instant now) {
        return repository.countActiveDownloads()
                .flatMapMany(inFlight -> {
                    int slots = Math.min(batchSize, maxConcurrentDownloads - inFlight.intValue());
                    if (slots <= 0) {
                        return Flux.<Download>empty();
                    }
                    return repository.admitDownloads(slots);
                })
                // Bounded by batchSize for the same reason the step phase is: this is now one
                // ytmusic-adapter request per download, so unbounded concurrency here would make
                // admission the loudest caller of the sidecar rather than the quietest.
                .flatMap(download -> gatherMetadata(download, now), batchSize)
                .then();
    }

    /**
     * Turns one accepted request into the task rows that will actually be searched for: one for a
     * song, one per track for an album or playlist. This is the only step that calls
     * ytmusic-adapter, and the only reason admission is no longer a single statement -- what rows a
     * download needs is a question only the provider can answer.
     *
     * <p>Failure handling leans on the typed exceptions YtMusicService already draws:
     * {@link YtMusicBadRequestException} means the id is wrong and no number of retries will make it
     * right, so the download fails now with a code the client can word. Anything else is the sidecar
     * being unavailable, which leaves the row PENDING and untouched -- the next pass tries again,
     * and a sidecar restart does not fail every download requested while it was down.
     */
    private Mono<Void> gatherMetadata(Download download, Instant now) {
        return metadataFor(download)
                .flatMap(collection -> {
                    List<YoutubeSongInfo> songs = collection.songs().stream()
                            .filter(song -> song.id() != null)
                            .toList();
                    if (songs.isEmpty()) {
                        // A real answer that contains nothing to download: an emptied playlist, or
                        // every track region-blocked. Terminal, not retryable -- asking again gets
                        // the same empty list.
                        log.warn("Download {} ({} {}) resolved to no downloadable songs; failing it",
                                download.getDownloadId(), download.getDownloadType(),
                                download.getYoutubeId());
                        return fail(download, DownloadFailureCode.METADATA_UNAVAILABLE);
                    }
                    List<DownloadTask> tasks = songs.stream()
                            .map(song -> DownloadTask.initial(download.getDownloadId(), song.id(),
                                    soulseekQuery(song), now))
                            .toList();
                    // The download's own id first, then every track. For a song the two are the
                    // same row and the upsert folds them. Written BEFORE the task rows, so a crash
                    // between the two leaves harmless extra metadata rather than nameless tasks;
                    // the next pass redoes both, and the upsert is idempotent.
                    List<MediaItem> media = new java.util.ArrayList<>();
                    // Keyed by the id the REQUEST carried, not the one the adapter echoed back:
                    // the feed joins on downloads.youtube_id, and the two can differ (a playlist
                    // requested as VL... is answered as PL...).
                    media.add(new MediaItem(download.getYoutubeId(), collection.name(),
                            collection.authorNames(), collection.imageUrl(), null, songs.size()));
                    songs.stream().map(MediaItem::of).forEach(media::add);
                    return repository.upsertMedia(media)
                            .then(repository.createTasks(download.getDownloadId(), tasks, now))
                            .doOnNext(admitted -> {
                                if (admitted > 0) {
                                    log.info("Admitted download {} ({} '{}') as {} task(s)",
                                            download.getDownloadId(), download.getDownloadType(),
                                            collection.name(), tasks.size());
                                } else {
                                    // The guards in CREATE_TASKS_SQL held, so something else had
                                    // already admitted this row. Nothing to fix, worth seeing.
                                    log.debug("Download {} was already admitted; no tasks created",
                                            download.getDownloadId());
                                }
                            })
                            .then();
                })
                .onErrorResume(YtMusicBadRequestException.class, error -> {
                    log.warn("Download {} has an id ytmusic-adapter cannot resolve ({} {}); failing it: {}",
                            download.getDownloadId(), download.getDownloadType(),
                            download.getYoutubeId(), error.getMessage());
                    return fail(download, DownloadFailureCode.METADATA_UNAVAILABLE);
                })
                .onErrorResume(error -> {
                    log.warn("Could not gather metadata for download {} ({} {}); leaving it PENDING "
                            + "for the next pass", download.getDownloadId(),
                            download.getDownloadType(), download.getYoutubeId(), error);
                    return Mono.empty();
                });
    }

    /**
     * The song's name in the shape slskd queries are built from. "Title - Primary Artist" is the
     * exact string the client used to send before requests became ids, and the shape
     * {@code TrackMatchingService} still splits on to check both halves appear in a filename. It is
     * stored as-is; {@link SearchQueryTiers} derives the bare "title - artist" and the title-only
     * fallback from it, so the rewording lives there, not here.
     */
    static String soulseekQuery(YoutubeSongInfo song) {
        return song.authorNames().isEmpty()
                ? song.name()
                : song.name() + " - " + song.authorNames().getFirst();
    }

    /**
     * One shape for all three types, so {@link #gatherMetadata} has no branch of its own. A song is
     * a collection of one -- which is exactly what it becomes in {@code download_tasks} anyway.
     */
    private Mono<YoutubeCollectionInfo> metadataFor(Download download) {
        String id = download.getYoutubeId();
        return switch (download.getDownloadType()) {
            case SONG -> ytMusicService.getSongInfo(id)
                    .map(song -> new YoutubeCollectionInfo(song.id(), List.of(song), null,
                            song.name(), song.authorNames(), song.imageUrl()));
            case ALBUM -> ytMusicService.getAlbumInfo(id);
            case PLAYLIST -> ytMusicService.getPlaylistInfo(id);
        };
    }

    /** Fails a download that never got a task row. Nothing else can record this failure. */
    private Mono<Void> fail(Download download, DownloadFailureCode code) {
        return repository.failUnadmitted(download.getDownloadId(), code, clock.instant())
                .doOnNext(rows -> log.info("Download {} failed before admission ({}); rows updated: {}",
                        download.getDownloadId(), code, rows))
                .then();
    }

    /**
     * Gates only the step that STARTS a transfer. Searches and polls of already-running transfers are
     * never gated — polling is one cheap GET, and starving it stalls a download slskd is finishing.
     */
    private Mono<Void> stepDueTasks(Instant now) {
        return repository.countActiveTransfers()
                .flatMapMany(active -> {
                    boolean transferSlotsFree = active < maxConcurrentTransfers;
                    return repository.claimDueTasks(batchSize, instanceId, now, leaseDuration,
                            transferSlotsFree);
                })
                .collectList()
                .flatMap(this::stepAll);
    }

    /**
     * Fetches the two batched slskd lists ONCE per pass — not once per claimed row — and only when at
     * least one claimed row actually needs one, so an idle pass (nothing claimed) and a pass with only
     * SEARCH_INIT/DOWNLOAD_INIT rows make zero calls to either endpoint. This, together with
     * {@link DownloadStepExecutor} reading from the resulting maps instead of calling slskd itself, is
     * what turns "one call per download per poll" into "two calls per pass, however many downloads
     * are in flight."
     */
    private Mono<Void> stepAll(List<DownloadTask> claimed) {
        if (claimed.isEmpty()) {
            // Nothing due this pass means the batched calls below never run, so a fully idle system
            // would otherwise make zero slskd calls between real downloads -- leaving the connection
            // pool free to go stale for however long that gap is (see SlskdConfig's timeout/pool
            // comment for what that costs once a real download finally does reuse one). GET /server
            // is the cheapest call slskd exposes (a single small object, no list to page through),
            // so it's used purely to exercise the pool on the same loop-interval-ms cadence as normal
            // operation -- no new schedule, and a lighter endpoint than reusing GET /searches.
            return slskdService.getServerState()
                    .then()
                    .onErrorResume(error -> {
                        log.warn("slskd keep-alive check failed", error);
                        return Mono.empty();
                    });
        }
        // slskd returns its whole search history, so narrow to our own rows -- and drop null ids,
        // which collectMap would otherwise key on (the nested-response bug, search-side).
        Set<String> trackedSearchIds = claimed.stream()
                .filter(t -> t.phase() == DownloadPhase.SEARCH_POLL)
                .map(DownloadTask::searchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        boolean needsSearches = !trackedSearchIds.isEmpty();

        // slskd returns its whole transfer history, so narrow to our own rows -- and drop null ids,
        // which collectMap would otherwise key on (the nested-response bug).
        Set<String> trackedTransferIds = claimed.stream()
                .filter(t -> t.phase() == DownloadPhase.DOWNLOAD_POLL)
                .map(DownloadTask::slskdTransferId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        boolean needsTransfers = !trackedTransferIds.isEmpty();

        log.debug("Pass stepping {} claimed row(s); slskd calls this pass: GET /searches={}, "
                + "GET /transfers/downloads={}", claimed.size(), needsSearches, needsTransfers);

        Mono<Map<String, SearchState>> searches = needsSearches
                ? slskdService.getAllSearches()
                        .filter(state -> state.getId() != null && trackedSearchIds.contains(state.getId()))
                        .collectMap(SearchState::getId)
                        .doOnNext(byId -> {
                            if (byId.size() < trackedSearchIds.size()) {
                                log.warn("Matched only {} of {} tracked search(es) in the slskd "
                                        + "response; unmatched ids: {}", byId.size(),
                                        trackedSearchIds.size(),
                                        trackedSearchIds.stream()
                                                .filter(id -> !byId.containsKey(id)).toList());
                            } else {
                                log.debug("Matched all {} tracked search(es)", byId.size());
                            }
                        })
                : Mono.just(Map.of());
        Mono<Map<String, TransferedFile>> transfers = needsTransfers
                ? slskdService.getAllDownloads()
                        .filter(file -> file.getId() != null && trackedTransferIds.contains(file.getId()))
                        .collectMap(TransferedFile::getId)
                        .doOnNext(byId -> {
                            // A shortfall here is the signature of a broken lookup, not of a finished
                            // download -- slskd keeps completed transfers in this list. Logged at WARN
                            // because the state machine's response is to fail the row, and a silent
                            // version of this line is what let the nested-response bug run for an hour.
                            if (byId.size() < trackedTransferIds.size()) {
                                log.warn("Matched only {} of {} tracked transfer(s) in the slskd "
                                        + "response; unmatched ids: {}", byId.size(),
                                        trackedTransferIds.size(),
                                        trackedTransferIds.stream()
                                                .filter(id -> !byId.containsKey(id)).toList());
                            } else {
                                log.debug("Matched all {} tracked transfer(s)", byId.size());
                            }
                        })
                : Mono.just(Map.of());

        return Mono.zip(searches, transfers)
                .flatMap(fetched -> Flux.fromIterable(claimed)
                        .flatMap(task -> stepOne(task, fetched.getT1(), fetched.getT2()), batchSize)
                        .then());
    }

    /** Every task is isolated: one bad step must never abort the rest of the pass. */
    private Mono<Void> stepOne(DownloadTask task, Map<String, SearchState> searchesById,
                               Map<String, TransferedFile> transfersById) {
        return executor.execute(task, searchesById, transfersById)
                .flatMap(decision -> apply(task, decision))
                .onErrorResume(error -> {
                    log.error("Task {} of download {} (step {}) could not be applied; the lease will "
                            + "expire and the row will be retried", task.taskId(),
                            task.downloadId(), task.phase(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> apply(DownloadTask task, DownloadDecision decision) {
        return switch (decision) {
            case DownloadDecision.Advance advance -> {
                if (advance.next().searchTier() > task.searchTier()) {
                    log.info("Song '{}' of download {} found no candidates for '{}'; retrying Soulseek "
                                    + "with looser query '{}' (tier {} of {})",
                            task.songName(), task.downloadId(), task.searchQuery(),
                            advance.next().searchQuery(), advance.next().searchTier() + 1,
                            SearchQueryTiers.of(task.songName()).size());
                }
                yield repository.save(advance.next(), instanceId).then();
            }
            case DownloadDecision.Continue proceed -> repository.save(proceed.next(), instanceId).then();
            case DownloadDecision.Terminal terminal -> {
                log.info("Song '{}' of download {} finished as {}{}", task.songName(),
                        task.downloadId(), terminal.status(),
                        terminal.failureCode() == null ? "" : " (" + terminal.failureCode() + ")");
                // Only this SONG. The download's own status is settled by concludeDownloads() at the
                // end of the pass, once every one of its songs is terminal.
                yield downloadService.finishTask(task.taskId(), terminal.status(),
                        terminal.failureCode(), clock.instant()).then();
            }
        };
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }
}
