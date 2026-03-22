package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.util.networkcalls.ReactivePoller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.List;
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
    @Value("${slskd-service.max-files-per-download}")
    int maxFilesPerDownload;

    public SlskdDownloadProcessor(SlskdService slskdService) {
        this.slskdService = slskdService;
    }

    // TODO: great place for Metric incrementing, 1. how often main result retries more than once till success, 2. how many times main result retries till success, 3.which entry in the top Y candidates was successful(ranking info) 4. If all top Y failed
    /**
     * Polls a list of TransferedFile candidates until one completes successfully, or all fail.
     * @param transferedFiles list of candidate files to try
     * @return Mono emitting the first successfully completed TransferedFile, or error if all fail
     */
    public Mono<TransferedFile> pollUntilComplete(List<TransferedFile> transferedFiles) {
        List<Supplier<Mono<TransferedFile>>> calls = transferedFiles.stream()
                .limit(maxFilesPerDownload)
                .map(tf -> (Supplier<Mono<TransferedFile>>) () -> slskdService.getDownloadProgress(tf.getUsername(), tf.getId()))
                .toList();

        Predicate<TransferedFile> done = tf -> tf.getState().contains("Completed");
        Predicate<TransferedFile> failed = tf -> tf.getState().contains("Failed");
        RetryBackoffSpec retry = ReactivePoller.defaultBackoff(Duration.ofMillis(firstBackOffDuration), maxPollAttempts);

        return ReactivePoller.pollUntilAny(calls, done, failed, retry);
    }
}
