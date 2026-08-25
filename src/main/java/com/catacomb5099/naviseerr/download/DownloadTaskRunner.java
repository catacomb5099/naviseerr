package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
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

    Mono<Void> pass() {
        Instant now = clock.instant();
        return admit(now)
                .then(stepDueTasks(now))
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
                .flatMap(inFlight -> {
                    int slots = Math.min(batchSize, maxConcurrentDownloads - inFlight.intValue());
                    if (slots <= 0) {
                        return Mono.empty();
                    }
                    return repository.admitNewDownloads(slots, now)
                            .doOnNext(admitted -> {
                                if (admitted > 0) log.info("Admitted {} download(s)", admitted);
                            });
                })
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
        slskdService.getServerState()
                .then()
                .onErrorResume(error -> {
                    log.warn("slskd keep-alive check failed", error);
                    return Mono.empty();
                });
        if (claimed.isEmpty()) {
            return Mono.empty();
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
                    log.error("Download {} step {} could not be applied; the lease will expire and "
                            + "the row will be retried", task.downloadId(), task.phase(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> apply(DownloadTask task, DownloadDecision decision) {
        return switch (decision) {
            case DownloadDecision.Advance advance -> repository.save(advance.next(), instanceId).then();
            case DownloadDecision.Continue proceed -> repository.save(proceed.next(), instanceId).then();
            case DownloadDecision.Terminal terminal -> {
                log.info("Download {} finished as {}{}", task.downloadId(), terminal.status(),
                        terminal.failureCode() == null ? "" : " (" + terminal.failureCode() + ")");
                yield downloadService.finishDownload(task.downloadId(), terminal.status(),
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
