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
 *
 * <h2>This queue is not durable</h2>
 *
 * <p>The buffer lives in this process's heap only. Nothing here survives a restart and nothing is
 * redelivered. Two ways work is lost today:
 *
 * <ul>
 *   <li><b>Process exit.</b> Everything buffered or in flight is gone. Those rows were already
 *       flipped to {@code IN_PROGRESS} by the claimer, so they are not picked up again by the next
 *       claim cycle - they are stranded in a non-terminal state with nothing to reclaim them.
 *       ({@code PENDING} rows are safe: they were never claimed, so the next cycle takes them.)
 *   <li><b>A rejected emission.</b> {@link #enqueue} logs a failed {@code tryEmitNext} and drops the
 *       download on the floor; the row stays {@code IN_PROGRESS} with no retry.
 * </ul>
 *
 * <p>This was a deliberate first-cut trade-off, not an oversight - see "In-memory Reactor Sinks
 * queue (not DB polling, not a broker)" and "No crash-recovery for in-flight work (MUST be added)"
 * in {@code docs/decisions/event-driven-download-queue-29-06-2026.md}. A durable broker is the
 * documented end state; it is <em>planned, not built</em>. Until it exists, the stranded-row gap is
 * what a reaper (deadline-based reclaim of stale {@code IN_PROGRESS} rows) is there to close.
 */
@Slf4j
@Component
public class DownloadQueue {

    private final Sinks.Many<Download> sink = Sinks.many().unicast().onBackpressureBuffer();

    /**
     * Offers a claimed download to the queue. The only producer is the single interval-claimer
     * thread, so emissions are already serialized and {@code tryEmitNext} is sufficient (no
     * busy-loop handler needed).
     *
     * <p>A rejected emission is logged and the download is dropped - there is no retry and no
     * back-channel to the caller, so its row is left stranded at {@code IN_PROGRESS}. Acceptable
     * only because the sole producer emits serially into an unbounded buffer, which in practice
     * fails only once the sink is terminated or cancelled.
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
