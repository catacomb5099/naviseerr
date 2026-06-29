# Reactive Patterns Cookbook

> Status: current as of 2026-06-29, branch `event-driven-download-queue`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

The backend is Spring WebFlux + Project Reactor. Almost everything is `Mono`-based; the only `Flux` uses are the claimer's `Flux.interval` and the queue's `Sinks` flux. Keep it non-blocking. This doc is the project's small set of reusable reactive idioms.

## Rule: no blocking in reactive paths

Do not call blocking APIs on the event loop. All external I/O goes through `WebClient` (Netty) and R2DBC (reactive). If you must use a blocking call, isolate it with `.subscribeOn(Schedulers.boundedElastic())` and treat it as the exception, not the norm (per `AGENTS.md`).

## Pattern 1: poll-until-done with backoff (ReactivePoller)

[ReactivePoller.java](../../src/main/java/com/catacomb5099/naviseerr/util/networkcalls/ReactivePoller.java) is the polling/retry engine used by both slskd processors. It has two tiers.

Tier 1 - `pollUntil`: one logical poll loop. Each attempt classifies the response and re-polls only while "in progress":

```java
return Mono.defer(call)
    .flatMap(response -> {
        if (isFailure.test(response))  return Mono.error(new PollingFailedException("..."));
        else if (isSuccess.test(response)) return Mono.just(response);
        else return Mono.error(new PollingInProgressException("Still processing"));
    })
    .retryWhen(pollSpec.filter(ex -> ex instanceof PollingInProgressException));
```

Only `PollingInProgressException` triggers a backoff retry (re-poll). `PollingFailedException` propagates up to tier 2.

Tier 2 - `pollUntilAny` / `tryNextSupplier`: tries an ordered list of candidates. For each candidate it runs the tier-1 poll; on a terminal failure it either retries the same candidate (up to `individualFailRetries`) or fails over to the next candidate, resetting the retry budget:

- last candidate and retries exhausted -> `Mono.error(PollingFailedException("All suppliers failed after retries"))`
- retries remaining -> retry the same candidate
- otherwise -> advance to the next candidate (reset budget)
- if the recursion runs past the last candidate -> `Mono.empty()`

Backoff is `defaultBackoff(firstBackoff, maxAttempts)` = `Retry.backoff(maxAttempts, firstBackoff).jitter(0.2).transientErrors(true)`.

Key consequence for callers: a poll can end as either `Mono.error(PollingFailedException)` OR `Mono.empty()` (no candidates / exhausted). Treat both as "did not succeed". See how the worker handles this in Pattern 3.

## Pattern 2: in-memory work queue (Sinks) with sleep/wake

[DownloadQueue.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadQueue.java) is a single-producer, single-consumer work queue:

```java
private final Sinks.Many<Download> sink = Sinks.many().unicast().onBackpressureBuffer();

public void enqueue(Download d) {
    Sinks.EmitResult result = sink.tryEmitNext(d);
    if (result.isFailure()) log.error("... {}", result);
}
public Flux<Download> asFlux() { return sink.asFlux(); }
```

- `unicast` = exactly one subscriber (the worker). `onBackpressureBuffer` = items buffer when the consumer isn't ready; the subscriber is parked when the buffer is empty and woken immediately on the next emit (no polling).
- Emission is from a single thread (the interval claimer), so it is naturally serialized and `tryEmitNext` is sufficient. With multiple producer threads you would need `emitNext(value, EmitFailureHandler.busyLooping(...))` or a serializing wrapper, because `tryEmitNext` can return `FAIL_NON_SERIALIZED`.
- The consumer uses a bounded `flatMap(this::process, concurrency)`, which only requests up to `concurrency`, so surplus items wait in the buffer (natural backpressure). The buffer is currently unbounded - see [gotchas.md](gotchas.md).

When to use Sinks vs alternatives:

- `Sinks.Many` (chosen) - reactive-native, composes with `flatMap`/backpressure, no extra deps; in-memory only.
- `Flux.create` + `FluxSink` - similar bridge, handles multi-threaded emission and overflow strategies; slightly awkward to wire from another bean.
- `BlockingQueue` + `boundedElastic` consumer - simple, but blocking; fights the WebFlux model.
- Durable broker (RabbitMQ/Redis) - durable and multi-instance, but heavier; this is the documented target.

## Pattern 3: mapping a reactive result to a terminal outcome

[DownloadWorker.process](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadWorker.java) collapses success / empty / error into one terminal write, and isolates failures so the long-lived subscription never dies:

```java
return downloadFulfillment.fulfill(d.getSongName())
    .flatMap(tf  -> downloadService.markStatus(d.getDownloadId(), SUCCEEDED))
    .switchIfEmpty(Mono.defer(() -> downloadService.markStatus(d.getDownloadId(), FAILED)))
    .onErrorResume(err -> downloadService.markStatus(d.getDownloadId(), FAILED))
    .then()
    .onErrorResume(err -> Mono.empty()); // safety net: even the status write failing won't kill the worker
```

- `switchIfEmpty` handles empty completion; `onErrorResume` handles errors. Both are needed because the upstream can signal "did not succeed" either way (Pattern 1).
- Wrap the `switchIfEmpty` publisher in `Mono.defer(...)` so the fallback is only built/subscribed when actually empty.
- The trailing `onErrorResume(... Mono.empty())` guarantees `process` always completes, so a failure in one item (including a DB write failure) cannot cancel the outer `flatMap` subscription.

## Testing reactive code

Use reactor-test `StepVerifier` for assertions (and `expectNoEvent` to prove a stream sleeps when idle). See [testing.md](testing.md).
