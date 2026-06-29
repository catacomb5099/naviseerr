package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory work queue between the interval claimer (producer) and the download worker (consumer).
 *
 * <p>Backed by a unicast {@link Sinks.Many} with an unbounded buffer: the single subscriber (the
 * worker) is parked when the buffer is empty and is woken immediately on the next emission, so the
 * worker never polls. The worker's bounded {@code flatMap} only requests up to its concurrency, so
 * surplus claimed downloads simply wait in the buffer (natural backpressure).
 */
@Slf4j
@Component
public class DownloadQueue {

    private final Sinks.Many<Download> sink = Sinks.many().unicast().onBackpressureBuffer();

    /**
     * Offers a claimed download to the queue. The only producer is the single interval-claimer
     * thread, so emissions are already serialized and {@code tryEmitNext} is sufficient (no
     * busy-loop handler needed).
     */
    public void enqueue(Download download) {
        Sinks.EmitResult result = sink.tryEmitNext(download);
        if (result.isFailure()) {
            log.error("Failed to enqueue download {} for song '{}': {}",
                    download.getDownloadId(), download.getSongName(), result);
        }
    }

    public Flux<Download> asFlux() {
        return sink.asFlux();
    }
}
