package com.catacomb5099.naviseerr.download;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

class DownloadQueueTest {

    private Download download(String songName) {
        return Download.builder()
                .downloadId(UUID.randomUUID())
                .songName(songName)
                .status(DownloadStatus.IN_PROGRESS)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void sleepsWhenEmpty_thenDeliversOnEnqueue() {
        DownloadQueue queue = new DownloadQueue();
        Download first = download("first");
        Download second = download("second");

        StepVerifier.create(queue.asFlux())
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(200))
                .then(() -> queue.enqueue(first))
                .expectNext(first)
                .then(() -> queue.enqueue(second))
                .expectNext(second)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }
}
