package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class SlskdDownloadProcessor {
    private final SlskdService slskdService;

    public SlskdDownloadProcessor(SlskdService slskdService) {
        this.slskdService = slskdService;
    }

    public Mono<TransferedFile> pollUntilComplete(String username, String downloadId) {
        return pollUntilComplete(username, downloadId, 500L);
    }

    // polling with exponential backoff until SearchState.isComplete = true
    private Mono<TransferedFile> pollUntilComplete(String username, String downloadId, long delay) {
        return slskdService.getDownloadProgress(username, downloadId)
                .flatMap(state ->
                        state.getState().contains("Completed") ?
                                Mono.just(state) :
                                Mono.delay(Duration.ofMillis(delay))
                                        .then(pollUntilComplete(username, downloadId, Math.min(delay * 2, 30000L))));
    }
}
