package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.*;
import com.catacomb5099.naviseerr.util.TransferedFileUtil;
import com.catacomb5099.naviseerr.util.networkcalls.ReactivePoller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Component
public class SlskdDownloadProcessor {
    private final SlskdService slskdService;

    @Value("${slskd-service.retry-count}")
    int retryAttempts;
    @Value("${slskd-service.max-poll-attempts}")
    int maxPollAttempts;
    @Value("${slskd-service.first-back-off-duration-ms}")
    long firstBackOffDuration;

    public SlskdDownloadProcessor(SlskdService slskdService) {
        this.slskdService = slskdService;
    }

    // TODO: great place for Metric incrementing, 1. how often main result retries more than once till success, 2. how many times main result retries till success, 3.which entry in the top Y candidates was successful(ranking info) 4. If all top Y failed
    /**
     * Polls a list of file candidates until one completes successfully, or all fail.
     * @param files list of candidate files to try
     * @return Mono emitting the first successfully completed TransferedFile, or error if all fail
     */
    public Mono<TransferedFile> pollUntilComplete(List<Map.Entry<SearchResponseItem, SearchFile>> files) {
        List<Supplier<Mono<QueueDownloadResponse>>> calls = files.stream()
                .map(fileEntry -> (Supplier<Mono<QueueDownloadResponse>>) () -> slskdService.enqueueDownload(fileEntry.getKey().getUsername(), fileEntry.getValue()))
                .toList();

        Predicate<TransferedFile> done = tf -> Arrays.stream(TransferState.values())
                .filter(TransferState::isSuccess)
                .anyMatch(TransferedFileUtil.getStateList(tf)::contains);
        Predicate<TransferedFile> failed = tf -> Arrays.stream(TransferState.values())
                .filter(TransferState::isFailure)
                .anyMatch(TransferedFileUtil.getStateList(tf)::contains);
        RetryBackoffSpec retry = ReactivePoller.defaultBackoff(Duration.ofMillis(firstBackOffDuration), maxPollAttempts);
        Function<QueueDownloadResponse, Supplier<Mono<TransferedFile>>> function = queueDownloadResponse -> {
            var enqueued = queueDownloadResponse.getEnqueued().getFirst();
            return () -> slskdService.getDownloadProgress(enqueued.getUsername(), enqueued.getId());
        };

        return ReactivePoller.pollUntilAny(calls, done, failed, retry, function);
    }
}
