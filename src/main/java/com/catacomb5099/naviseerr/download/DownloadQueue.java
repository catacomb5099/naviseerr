package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory work queue between the interval claimer (producer) and the download worker (consumer).
 * A unicast {@link Sinks.Many} whose single subscriber parks while empty and wakes on the next emit,
 * so the worker never polls. Not durable, unbounded buffer - see the event-driven-download-queue ADR.
 */
@Slf4j
@Component
public class DownloadQueue {

    private final Sinks.Many<Download> sink = Sinks.many().unicast().onBackpressureBuffer();

    // Sole producer is the interval claimer, so emissions are serialized and tryEmitNext suffices.
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
