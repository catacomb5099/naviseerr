package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.curator.CuratorException;
import com.catacomb5099.naviseerr.mediaserver.MediaServerNotifier;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Every pass asks the database what is due and acts on the answer; nothing is held in memory between passes. */
@Slf4j
@Component
public class DownloadTaskRunner {

    private final DownloadTaskRepository repository;
    private final DownloadStepExecutor executor;
    private final DownloadService downloadService;
    private final SlskdService slskdService;
    private final TrackLists trackLists;
    private final LibraryOrganiser organiser;
    private final AlbumSearchStep albumSearches;
    private final ManualSearch manualSearch;
    private final MediaServerNotifier mediaServers;
    private final Clock clock;
    private final Duration loopInterval;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxConcurrentDownloads;
    private final int maxConcurrentTransfers;
    private final int maxConcurrentSearches;
    /** Identifies this process in lease_owner. Nothing depends on it surviving a restart. */
    private final String instanceId = UUID.randomUUID().toString();
    private Disposable subscription;
    /**
     * ponytail: a constant, not a config key. While Soulseek is offline the pass holds every unfinished
     * row's clocks ({@link #stepOrPause}); past this long it stops and steps as before, so the songs
     * fail as SOULSEEK_OFFLINE from slskd's 409 and a wrong Soulseek password still ends in a red card
     * that says why, not in cards that say Searching for ever. Make it a property if it ever needs tuning.
     */
    static final Duration OFFLINE_PAUSE_CEILING = Duration.ofMinutes(30);
    /** When slskd first said it is not logged in to Soulseek; null while it is. One per process. */
    private volatile Instant soulseekOfflineSince;
    private volatile boolean pastCeilingLogged;
    /** When the previous pass held the clocks; null while Soulseek is up. Sets how far the next hold moves them. */
    private volatile Instant lastPausedAt;

    public DownloadTaskRunner(
            DownloadTaskRepository repository,
            DownloadStepExecutor executor,
            DownloadService downloadService,
            SlskdService slskdService,
            TrackLists trackLists,
            LibraryOrganiser organiser,
            AlbumSearchStep albumSearches,
            ManualSearch manualSearch,
            MediaServerNotifier mediaServers,
            Clock clock,
            @Value("${download-task.loop-interval-ms:2000}") Duration loopInterval,
            @Value("${download-task.batch-size:10}") int batchSize,
            @Value("${download-task.lease-duration-ms:60000}") Duration leaseDuration,
            @Value("${download-task.max-concurrent-downloads:20}") int maxConcurrentDownloads,
            @Value("${download-task.max-concurrent-transfers:20}") int maxConcurrentTransfers,
            @Value("${download-task.max-concurrent-searches:2}") int maxConcurrentSearches) {
        this.repository = repository;
        this.executor = executor;
        this.downloadService = downloadService;
        this.slskdService = slskdService;
        this.trackLists = trackLists;
        this.organiser = organiser;
        this.albumSearches = albumSearches;
        this.manualSearch = manualSearch;
        this.mediaServers = mediaServers;
        this.clock = clock;
        this.loopInterval = loopInterval;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxConcurrentDownloads = maxConcurrentDownloads;
        this.maxConcurrentTransfers = maxConcurrentTransfers;
        this.maxConcurrentSearches = maxConcurrentSearches;
    }

    @PostConstruct
    void start() {
        subscription = Flux.interval(loopInterval)
                .onBackpressureDrop()
                .concatMap(tick -> pass())
                .subscribe();
    }

    /**
     * Every tick, in order: admit new requests; ask slskd whether it is logged in to Soulseek and either
     * step whatever is due or hold every unfinished row's clocks ({@link #stepOrPause}); conclude any
     * download whose songs have all finished; then file finished songs into the library. Conclusion
     * runs after stepping so a download that finished during this very pass reports its outcome on
     * the same tick rather than lingering a full interval in IN_PROGRESS -- and runs unconditionally,
     * so one that was missed (a crash between the last task's terminal write and here) is picked up
     * by the next pass regardless. Filing is the same shape: it asks the table which finished songs
     * still have no library path, so a song whose file was not moved yet is picked up again next pass.
     * Last, the music servers are told to rescan when a download was stamped organised this pass
     * ({@link MediaServerNotifier}: one call per server, at most one every 30 s), fire and forget: a
     * host that swallows packets costs its own timeout, never a slower or a failed pass.
     */
    Mono<Void> pass() {
        Instant now = clock.instant();
        return admit(now)
                .then(stepOrPause(now)
                        // slskd being unreachable is one fact, worth one line: the rest of the pass
                        // (concluding finished downloads, filing their files) needs no slskd at all,
                        // so it must not be skipped because the batched calls failed (07-10-2026).
                        // Only slskd's failures are that fact; anything else (the database, a bug)
                        // keeps its stack trace, as DownloadStepExecutor and AlbumSearchStep draw it.
                        .onErrorResume(error -> {
                            if (error instanceof WebClientException) {
                                log.warn("Could not step downloads this pass; slskd is unreachable: {}",
                                        DownloadStateMachine.describe(error));
                            } else {
                                log.error("Could not step downloads this pass", error);
                            }
                            return Mono.empty();
                        }))
                .then(repository.concludeDownloads()
                        .doOnNext(concluded -> {
                            if (concluded > 0) log.info("Concluded {} download(s)", concluded);
                        })
                        .then())
                .then(organise(now))
                .then(Mono.<Void>fromRunnable(() -> mediaServers.flush(now)
                        .subscribe(null, error -> log.warn("Could not ask the music servers to rescan: {}", error.toString()))))
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
        return trackLists.of(download.getDownloadType(), download.getYoutubeId())
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
                    // Each row keeps its own title, number and length: one id can sit on two rows
                    // of one album, and media_items holds only one of them.
                    List<DownloadTask> tasks = songs.stream()
                            .map(song -> DownloadTask.initial(download.getDownloadId(), song.id(),
                                            soulseekQuery(song), now).toBuilder()
                                    .trackTitle(song.name())
                                    .trackNumber(song.trackNumber())
                                    .durationSeconds(song.durationSeconds())
                                    .build())
                            .toList();
                    // The download's own id first, then every track. For a song the two are the
                    // same row and the upsert folds them. Written BEFORE the task rows, so a crash
                    // between the two leaves harmless extra metadata rather than nameless tasks;
                    // the next pass redoes both, and the upsert is idempotent.
                    List<MediaItem> media = new java.util.ArrayList<>();
                    // Keyed by the id the REQUEST carried, not the one the adapter echoed back:
                    // the feed joins on downloads.youtube_id, and the two can differ (a playlist
                    // requested as VL... is answered as PL...). The track count is YouTube's own
                    // when it gives one (an album's real total, unavailable tracks included).
                    media.add(new MediaItem(download.getYoutubeId(), collection.name(),
                            collection.authorNames(), collection.authorIds(), collection.imageUrl(),
                            null, Objects.requireNonNullElse(collection.trackCount(), songs.size()),
                            collection.year(), collection.type()));
                    songs.stream().map(MediaItem::of).forEach(media::add);
                    // P5: an album first looks for one sharer with every song; its songs wait for that.
                    Instant albumHold = download.getDownloadType() == DownloadType.ALBUM
                            ? albumSearches.holdUntil(now) : null;
                    return repository.upsertMedia(media)
                            .then(alreadyFiled(download, tasks))
                            .flatMap(ready -> repository.createTasks(download.getDownloadId(), ready, now, albumHold)
                                    .doOnNext(admitted -> {
                                        if (admitted > 0) {
                                            log.info("Admitted download {} ({} '{}') as {} task(s)",
                                                    download.getDownloadId(), download.getDownloadType(),
                                                    collection.name(), admitted);
                                        } else {
                                            // The guards in CREATE_TASKS_SQL held, so something else had
                                            // already admitted this row. Nothing to fix, worth seeing;
                                            // copies made for it are not wanted.
                                            log.debug("Download {} was already admitted or is no longer "
                                                    + "pending; no tasks created", download.getDownloadId());
                                            ready.stream().filter(t -> t.slskdFilename() != null)
                                                    .forEach(t -> organiser.discardCopy(t.slskdFilename()).subscribe());
                                        }
                                    }))
                            .then();
                })
                .onErrorResume(YtMusicBadRequestException.class, error -> {
                    log.warn("Download {} has an id ytmusic-adapter cannot resolve ({} {}); failing it: {}",
                            download.getDownloadId(), download.getDownloadType(),
                            download.getYoutubeId(), error.getMessage());
                    return fail(download, DownloadFailureCode.METADATA_UNAVAILABLE);
                })
                // The curator's own 4xx: no edition for that category, an unknown key, or a wrong token.
                // Asking again cannot change the answer, so the download fails now; a curator that is
                // merely down (retryable) falls through and leaves the row PENDING like an adapter outage.
                .onErrorResume(error -> error instanceof CuratorException ce && !ce.isRetryable(), error -> {
                    log.warn("Download {} asks for a suggested playlist the curator cannot give ({} {}); "
                            + "failing it: {}", download.getDownloadId(), download.getDownloadType(),
                            download.getYoutubeId(), error.getMessage());
                    return fail(download, DownloadFailureCode.METADATA_UNAVAILABLE);
                })
                .onErrorResume(error -> {
                    // The message only: a stack trace per pending download per pass while the adapter
                    // is down said nothing the two "Retrying ytmusic-adapter request" lines had not.
                    log.warn("Could not gather metadata for download {} ({} {}); leaving it PENDING "
                            + "for the next pass: {}", download.getDownloadId(),
                            download.getDownloadType(), download.getYoutubeId(), error.toString());
                    return Mono.empty();
                });
    }

    /**
     * The tasks, with what the library already has set on each song it has from an earlier download
     * (see {@code FILED_COPIES_SQL} for what counts), so it is created finished instead of downloaded
     * again. A song, playlist or curated row POINTS at the file ({@code libraryPath}); so does an album's
     * track the library has as this very album's. An album's track the library has only as ANOTHER
     * album's (a compilation, EP, deluxe edition or best-of sharing the recording) gets its own COPY
     * ({@link LibraryOrganiser#copyIntoDownloads}, set as {@code slskdFilename}): each album folder
     * must hold its songs, tagged as that album's (19-10-2026). A copy that fails (disk full, a
     * permission) is logged and the song downloads as before. Only with the organiser on: without it
     * there is no library to look in.
     */
    private Mono<List<DownloadTask>> alreadyFiled(Download download, List<DownloadTask> tasks) {
        if (!organiser.isEnabled()) {
            return Mono.just(tasks);
        }
        boolean album = download.getDownloadType() == DownloadType.ALBUM;
        return repository.filedCopies(download.getDownloadType(), download.getYoutubeId(), tasks)
                .collectList()
                .flatMap(copies -> organiser.stillFiled(tasks.size(), copies))
                .flatMapMany(found -> Flux.range(0, tasks.size()).concatMap(i -> {
                    DownloadTask task = tasks.get(i);
                    LibraryOrganiser.FiledCopy copy = found.get(i);
                    if (copy == null) {
                        return Mono.just(task);
                    }
                    if (album && !copy.sameAlbum()) {
                        return organiser.copyIntoDownloads(copy.libraryPath())
                                .map(name -> task.toBuilder().slskdFilename(name).build())
                                .onErrorResume(error -> {
                                    log.warn("Could not copy {} for download {}; downloading the song instead",
                                            copy.libraryPath(), download.getDownloadId(), error);
                                    return Mono.just(task);
                                });
                    }
                    return Mono.just(task.toBuilder().libraryPath(copy.libraryPath()).build());
                }))
                .collectList()
                .doOnNext(ready -> {
                    long pointed = ready.stream().filter(t -> t.libraryPath() != null).count();
                    long copied = ready.stream().filter(t -> t.slskdFilename() != null).count();
                    if (pointed + copied > 0) {
                        log.info("Download {}: {} of {} song(s) already in the library; not downloading them again"
                                + "{}", download.getDownloadId(), pointed + copied, tasks.size(),
                                copied > 0 ? " (" + copied + " copied into the album's folder)" : "");
                    }
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
     * Moves each finished song's file from slskd's downloads folder into the library and records
     * where it went; then, for each download whose songs are all filed, writes the collection's own
     * file (a playlist's .m3u8) and stamps it organised. Songs first, so a playlist whose last track
     * lands in this pass gets its file on the same tick. Off unless both library folders are
     * configured. Every song and every download is isolated: one that cannot be handled (not there
     * yet, permissions) is logged and left for the next pass.
     */
    private Mono<Void> organise(Instant now) {
        if (!organiser.isEnabled()) {
            return Mono.empty();
        }
        Instant cutoff = organiser.cutoff(now);
        AtomicInteger filedThisPass = new AtomicInteger();
        // Copies of one song ready in the same pass are filed one after the other, and once one is
        // filed the rest wait a pass: then they see its file (filed_copy) and are not filed twice.
        return repository.tasksToOrganise(batchSize, cutoff, organiser.albumCutoff(now))
                .collect(Collectors.groupingBy(job -> Objects.requireNonNullElse(job.copyKey(), job.taskId().toString()),
                        LinkedHashMap::new, Collectors.toList()))
                .flatMapMany(copies -> Flux.fromIterable(copies.values()))
                .flatMap(copies -> {
                    // The album's own track goes first, so a playlist's twin then points at the file in
                    // the album's folder rather than the album pointing at the twin's (19-10-2026).
                    copies.sort(Comparator.comparing(job -> job.type() != DownloadType.ALBUM));
                    AtomicBoolean filed = new AtomicBoolean();
                    return Flux.fromIterable(copies).concatMap(job -> filed.get() ? Mono.empty() : organiser.file(job, now)
                            .flatMap(path -> repository.setLibraryPath(job.taskId(), path.toString()))
                            .doOnNext(written -> { filed.set(true); filedThisPass.incrementAndGet(); })
                            .onErrorResume(error -> {
                                log.warn("Could not file song {} ('{}') into the library; will retry "
                                        + "next pass", job.taskId(), job.slskdFilename(), error);
                                return Mono.empty();
                            }));
                }, batchSize)
                // Only a pass that filed something can have made a waiting song's copy appear.
                .then(Mono.defer(() -> filedThisPass.get() > 0 ? markWaitingSongsDone(now) : Mono.empty()))
                .then(repository.downloadsToFinalise(batchSize, cutoff)
                        .flatMap(collection -> finalise(collection)
                                // Deferred: the stamp must only be built once the file is written.
                                .then(Mono.defer(() -> repository.setOrganisedAt(collection.downloadId(), now)))
                                .onErrorResume(error -> {
                                    log.warn("Could not finalise download {} ('{}'); will retry next "
                                            + "pass", collection.downloadId(), collection.title(), error);
                                    return Mono.empty();
                                }), batchSize)
                        // Rows stamped this pass: the music servers have something new to see.
                        .reduce(0L, Long::sum)
                        .doOnNext(stamped -> {
                            if (stamped > 0) mediaServers.libraryChanged();
                        })
                        .then());
    }

    /**
     * Songs and playlist tracks still on their way whose song the library now has (a playlist requested
     * while its album was still downloading; admission could not know) are marked done with that file
     * instead of searching or transferring on (19-10-2026). A transfer slskd is still running for one
     * is stopped, best effort, and its partial files removed. Then finished downloads are concluded
     * once more, so a playlist whose last song was just marked closes on this tick and gets its file
     * below. An album download's held songs are never swept: each album gets its own copy.
     */
    private Mono<Void> markWaitingSongsDone(Instant now) {
        return repository.waitingCopies(now)
                .collectList()
                .filter(copies -> !copies.isEmpty())
                .flatMap(copies -> {
                    List<DownloadTaskRepository.WaitingCopy> songs = copies.stream()
                            .filter(distinctBy(DownloadTaskRepository.WaitingCopy::taskId)).toList();
                    Map<UUID, Integer> position = new LinkedHashMap<>();
                    songs.forEach(song -> position.put(song.taskId(), position.size() + 1));
                    return organiser.stillFiled(songs.size(), copies.stream()
                                    .map(copy -> new LibraryOrganiser.FiledCopy(position.get(copy.taskId()), copy.libraryPath()))
                                    .toList())
                            .flatMapMany(found -> Flux.range(0, songs.size())
                                    .filter(i -> found.get(i) != null)
                                    .concatMap(i -> repository.markFiled(songs.get(i).taskId(), found.get(i).libraryPath(), now)
                                            .doOnNext(was -> {
                                                log.info("Song {} of download {} is already in the library as {}; not "
                                                        + "fetching it", was.taskId(), was.downloadId(), found.get(i).libraryPath());
                                                stopInSlskd(was);
                                            })))
                            .count()
                            .flatMap(marked -> marked > 0 ? repository.concludeDownloads().then() : Mono.empty());
                });
    }

    /** Same shape as DownloadService.stopInSlskd: the row is written; slskd is told after, best effort. */
    private void stopInSlskd(DownloadTask was) {
        if (was.slskdTransferId() != null) {
            Mono.defer(() -> slskdService.cancelDownload(was.slskdUsername(), was.slskdTransferId()))
                    .subscribe(ignored -> { },
                            error -> log.warn("Could not cancel transfer {} from '{}' for song {}; it stays in slskd's list",
                                    was.slskdTransferId(), was.slskdUsername(), was.taskId(), error),
                            () -> log.info("Cancelled transfer {} from '{}' for song {}: the library has the song",
                                    was.slskdTransferId(), was.slskdUsername(), was.taskId()));
        }
        organiser.deletePartials(was).subscribe();
    }

    private static <T, K> java.util.function.Predicate<T> distinctBy(java.util.function.Function<T, K> key) {
        Set<K> seen = new HashSet<>();
        return item -> seen.add(key.apply(item));
    }

    /** A playlist gets its .m3u8 (and its picture beside it); a song or an album needs nothing more than its files in place. */
    private Mono<Void> finalise(LibraryOrganiser.Collection collection) {
        if (!collection.type().isPlaylist()) {
            return Mono.empty();
        }
        return repository.playlistEntries(collection.downloadId())
                .collectList()
                .flatMap(entries -> organiser.writePlaylist(collection.title(), collection.imageUrl(), entries))
                // Jellyfin reads no picture file: the notifier sends it after Jellyfin's rescan.
                .doOnNext(mediaServers::playlistWritten)
                .then();
    }

    /** Fails a download that never got a task row. Nothing else can record this failure. */
    private Mono<Void> fail(Download download, DownloadFailureCode code) {
        return repository.failUnadmitted(download.getDownloadId(), code, clock.instant())
                .doOnNext(rows -> log.info("Download {} failed before admission ({}); rows updated: {}",
                        download.getDownloadId(), code, rows))
                .then();
    }

    /**
     * One {@code GET /server} first -- the call the idle keep-alive used to make, now every pass. While
     * slskd is not logged in to Soulseek (the internet is down, or slskd is reconnecting) nothing can be
     * searched or fetched, so nothing is claimed or stepped; instead every unfinished row's clocks move
     * forward by the time since the previous held pass, and no search or download budget runs down while
     * nothing could be done (07-10-2026). Conclusion and filing still run after this. The transitions are logged, not
     * the passes. Past {@link #OFFLINE_PAUSE_CEILING} the pass steps as before.
     */
    private Mono<Void> stepOrPause(Instant now) {
        return slskdService.getServerState().flatMap(state -> {
            Instant since = soulseekOfflineSince;
            if (state.isLoggedIn()) {
                if (since != null) {
                    soulseekOfflineSince = null;
                    lastPausedAt = null;
                    pastCeilingLogged = false;
                    log.info("Soulseek is back after {} s offline; downloads resume", Duration.between(since, now).toSeconds());
                }
                return stepDueTasks(now);
            }
            if (since == null) {
                since = now;
                soulseekOfflineSince = now;
                log.info("Soulseek is offline (slskd is not logged in); downloads wait with their clocks held, "
                        + "for up to {} minutes", OFFLINE_PAUSE_CEILING.toMinutes());
            }
            if (Duration.between(since, now).compareTo(OFFLINE_PAUSE_CEILING) >= 0) {
                if (!pastCeilingLogged) {
                    pastCeilingLogged = true;
                    log.warn("Soulseek has been offline for {} minutes; downloads resume and will fail as "
                            + "SOULSEEK_OFFLINE until slskd logs in (check its Soulseek username and password)",
                            OFFLINE_PAUSE_CEILING.toMinutes());
                }
                return stepDueTasks(now);
            }
            // By the time since the previous held pass, not by the interval: admission runs before this
            // gate and, with the internet down, waits on ytmusic-adapter for every PENDING download, so
            // a pass can take 45 s -- shifting by 2 s then would let the budgets run down at nearly real
            // speed. Never less than the interval (the first held pass has no previous one).
            Duration by = lastPausedAt == null ? loopInterval : Duration.between(lastPausedAt, now);
            if (by.compareTo(loopInterval) < 0) by = loopInterval;
            lastPausedAt = now;
            return repository.pauseDueWork(now, by)
                    .doOnNext(rows -> log.debug("Soulseek offline: held the clocks of {} unfinished row(s)", rows))
                    .then();
        });
    }

    /**
     * Gates only the two steps that START something in slskd: a transfer (DOWNLOAD_INIT) and a search
     * (SEARCH_INIT). Polls of already-running searches and transfers are never gated — polling is one
     * cheap GET, and starving it stalls work slskd is happily finishing. The search gate is a count,
     * not a yes/no: slskd runs two searches at a time and queues the rest inside itself, so anything
     * we start beyond its slots only waits in that queue while our search budget runs down. Album
     * searches (P5) share those slots and are claimed first: the songs get only what they leave, so
     * one pass never starts more than the free slots between them, and an album's songs (held while
     * it searches) are never kept waiting behind another download's songs. A search a person is running
     * from the pop-up ({@link ManualSearch}) holds a slot too, so the loop does not pile a third search
     * onto slskd behind it.
     */
    private Mono<Void> stepDueTasks(Instant now) {
        return Mono.zip(repository.transfersInFlight().collectList(), repository.countActiveSearches())
                .flatMap(active -> {
                    boolean transferSlotsFree = active.getT1().size() < maxConcurrentTransfers;
                    int searchSlots = (int) Math.max(0, maxConcurrentSearches - active.getT2() - manualSearch.inFlight());
                    return repository.claimDueAlbumSearches(batchSize, instanceId, now, leaseDuration, searchSlots)
                            .collectList()
                            .flatMap(albums -> {
                                long albumStarts = albums.stream()
                                        .filter(album -> album.phase() == DownloadPhase.SEARCH_INIT).count();
                                return repository.claimDueTasks(batchSize, instanceId, now, leaseDuration,
                                                transferSlotsFree, (int) (searchSlots - albumStarts))
                                        .collectList()
                                        .flatMap(claimed -> stepAll(albums, claimed, active.getT1()));
                            });
                });
    }

    /**
     * Fetches the two batched slskd lists ONCE per pass — not once per claimed row — and only when at
     * least one claimed row actually needs one, so an idle pass (nothing claimed) and a pass with only
     * SEARCH_INIT/DOWNLOAD_INIT rows make zero calls to either endpoint. This, together with
     * {@link DownloadStepExecutor} reading from the resulting maps instead of calling slskd itself, is
     * what turns "one call per download per poll" into "two calls per pass, however many downloads
     * are in flight."
     *
     * <p>{@code ours} is every transfer naviseerr has in flight, not just the claimed ones: a poll
     * caps at batch-size rows, so the sibling that is actually being sent is often not claimed in the
     * same pass as the one waiting behind it (P9, {@link SharerLoad}).
     */
    private Mono<Void> stepAll(List<AlbumSearch> albums, List<DownloadTask> claimed,
                               List<DownloadTaskRepository.TransferInFlight> ours) {
        if (claimed.isEmpty() && albums.isEmpty()) {
            // Nothing due this pass means the batched calls below never run. The GET /server that
            // stepOrPause made at the top of the pass has already exercised the connection pool (see
            // SlskdConfig's timeout/pool comment for what a stale pool costs once a real download
            // finally reuses one), so an idle pass needs nothing more here.
            return Mono.empty();
        }
        // slskd returns its whole search history, so narrow to our own rows -- and drop null ids,
        // which collectMap would otherwise key on (the nested-response bug, search-side).
        Set<String> trackedSearchIds = java.util.stream.Stream.concat(
                        claimed.stream().filter(t -> t.phase() == DownloadPhase.SEARCH_POLL).map(DownloadTask::searchId),
                        albums.stream().filter(a -> a.phase() == DownloadPhase.SEARCH_POLL).map(AlbumSearch::searchId))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        boolean needsSearches = !trackedSearchIds.isEmpty();

        // slskd returns its whole transfer history, so narrow to our own rows -- and drop null ids,
        // which collectMap would otherwise key on (the nested-response bug). The claimed polls decide
        // whether to fetch; every one of ours in flight is kept, for SharerLoad.
        Set<String> trackedTransferIds = claimed.stream()
                .filter(t -> t.phase() == DownloadPhase.DOWNLOAD_POLL)
                .map(DownloadTask::slskdTransferId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        boolean needsTransfers = !trackedTransferIds.isEmpty();
        Set<String> ourTransferIds = new HashSet<>(trackedTransferIds);
        ours.stream().map(DownloadTaskRepository.TransferInFlight::transferId).filter(Objects::nonNull)
                .forEach(ourTransferIds::add);

        log.debug("Pass stepping {} claimed row(s) and {} album search(es); slskd calls this pass: "
                + "GET /searches={}, GET /transfers/downloads={}", claimed.size(), albums.size(),
                needsSearches, needsTransfers);

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
                        .filter(file -> file.getId() != null && ourTransferIds.contains(file.getId()))
                        .collectMap(TransferedFile::getId)
                        .doOnNext(byId -> {
                            // A shortfall here is the signature of a broken lookup, not of a finished
                            // download -- slskd keeps completed transfers in this list. Logged at WARN
                            // because the state machine's response is to fail the row, and a silent
                            // version of this line is what let the nested-response bug run for an hour.
                            List<String> unmatched = trackedTransferIds.stream()
                                    .filter(id -> !byId.containsKey(id)).toList();
                            if (!unmatched.isEmpty()) {
                                log.warn("Matched only {} of {} tracked transfer(s) in the slskd "
                                        + "response; unmatched ids: {}",
                                        trackedTransferIds.size() - unmatched.size(),
                                        trackedTransferIds.size(), unmatched);
                            } else {
                                log.debug("Matched all {} tracked transfer(s)", trackedTransferIds.size());
                            }
                        })
                : Mono.just(Map.of());

        // SEARCH_INIT rows are stepped one after another: slskd answers an overlapping POST /searches
        // with 429 ("Only one concurrent operation is permitted"). Album starts go first, in the same
        // queue, for the same reason. Everything else stays concurrent.
        return Mono.zip(searches, transfers)
                .flatMap(fetched -> {
                    SharerLoad sharers = SharerLoad.of(ours, fetched.getT2());
                    Instant now = clock.instant();
                    return Flux.merge(
                            Flux.concat(
                                    Flux.fromIterable(albums)
                                            .filter(album -> album.phase() == DownloadPhase.SEARCH_INIT)
                                            .concatMap(album -> albumSearches.step(album, fetched.getT1(), now, instanceId)),
                                    Flux.fromIterable(claimed)
                                            .filter(task -> task.phase() == DownloadPhase.SEARCH_INIT)
                                            .concatMap(task -> stepOne(task, fetched.getT1(), fetched.getT2(),
                                                    sharers))),
                            Flux.fromIterable(albums)
                                    .filter(album -> album.phase() != DownloadPhase.SEARCH_INIT)
                                    .flatMap(album -> albumSearches.step(album, fetched.getT1(), now, instanceId)),
                            Flux.fromIterable(claimed)
                                    .filter(task -> task.phase() != DownloadPhase.SEARCH_INIT)
                                    .flatMap(task -> stepOne(task, fetched.getT1(), fetched.getT2(), sharers),
                                            batchSize))
                            .then();
                });
    }

    /** Every task is isolated: one bad step must never abort the rest of the pass. */
    private Mono<Void> stepOne(DownloadTask task, Map<String, SearchState> searchesById,
                               Map<String, TransferedFile> transfersById, SharerLoad sharers) {
        return executor.execute(task, searchesById, transfersById, sharers)
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
                    log.info("Song '{}' of download {} did not find enough with '{}' ({} file(s) kept so far); "
                                    + "trying the next wording '{}' (tier {} of {})",
                            task.songName(), task.downloadId(), task.searchQuery(),
                            advance.next().candidates().size(), advance.next().searchQuery(),
                            advance.next().searchTier() + 1, SearchQueryTiers.of(task.songName()).size());
                }
                // P5 fallback: every album-folder file failed and the song goes back to its own search.
                // Those files will not be resumed, so their partial files go, as for a song that failed.
                boolean leftAlbumFolder = advance.next().phase() == DownloadPhase.SEARCH_INIT
                        && (task.phase() == DownloadPhase.DOWNLOAD_INIT || task.phase() == DownloadPhase.DOWNLOAD_POLL);
                if (leftAlbumFolder) {
                    log.info("Song '{}' of download {}: no sharer of the whole album could send it; searching "
                            + "for it on its own", task.songName(), task.downloadId());
                }
                yield repository.save(advance.next(), instanceId)
                        // Zero rows means the row went terminal under us -- cancelled by the user while this
                        // step was enqueueing. slskd now has a transfer nobody tracks; stop it. A crash before
                        // this line still orphans one (accepted in the 13-08-2026 ADR).
                        .flatMap(rows -> rows == 0
                                && advance.next().phase() == DownloadPhase.DOWNLOAD_POLL
                                && advance.next().slskdTransferId() != null
                                ? slskdService.cancelDownload(advance.next().slskdUsername(), advance.next().slskdTransferId())
                                        .doOnSuccess(v -> log.info("Cancelled transfer {} from '{}' that started after song {} was cancelled",
                                                advance.next().slskdTransferId(), advance.next().slskdUsername(), task.taskId()))
                                        .onErrorResume(error -> {
                                            log.warn("Could not cancel orphaned transfer {} from '{}' for song {}",
                                                    advance.next().slskdTransferId(), advance.next().slskdUsername(), task.taskId(), error);
                                            return Mono.empty();
                                        })
                                : Mono.empty())
                        .then(leftAlbumFolder ? organiser.deletePartials(task) : Mono.empty());
            }
            case DownloadDecision.Continue proceed -> repository.save(proceed.next(), instanceId).then();
            case DownloadDecision.Terminal terminal -> {
                log.info("Song '{}' of download {} finished as {}{}", task.songName(),
                        task.downloadId(), terminal.status(),
                        terminal.failureCode() == null ? "" : " (" + terminal.failureCode() + ")");
                if (terminal.status() == DownloadStatus.SUCCEEDED && task.candidates() != null
                        && task.candidateIndex() < task.candidates().size()
                        && "OTHER_VERSION".equals(task.currentCandidate().grade())) {
                    log.info("Song '{}' of download {} was fetched as another version of the song ('{}') "
                            + "because nobody shared the requested one", task.songName(), task.downloadId(),
                            task.currentCandidate().filename());
                }
                // Only this SONG. The download's own status is settled by concludeDownloads() at the
                // end of the pass, once every one of its songs is terminal.
                yield downloadService.finishTask(task.taskId(), terminal.status(),
                                terminal.failureCode(), clock.instant(), instanceId)
                        // rows > 0 is the first, real finish -- the same guard that stops a duplicate
                        // finish re-stamping finished_at also stops it re-running the cleanup. A song
                        // that failed for good will not be retried, so slskd's resume-able partial
                        // file is just clutter now. Best effort, and never fails the song.
                        .flatMap(rows -> rows > 0 && terminal.status() == DownloadStatus.FAILED
                                ? organiser.deletePartials(task)
                                : Mono.empty());
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
