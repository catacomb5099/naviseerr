# Reactive Patterns Cookbook

> Status: current as of 2026-08-13, branch `durable-download-state-machine`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

The backend is Spring WebFlux + Project Reactor. Almost everything is `Mono`-based; the download loop's `Flux` uses are `Flux.interval` (the pass tick) and `Flux.fromIterable`/`flatMap` (stepping the rows claimed within one pass). Keep it non-blocking. This doc is the project's small set of reusable reactive idioms.

## Rule: no blocking in reactive paths

Do not call blocking APIs on the event loop. All external I/O goes through `WebClient` (Netty) and R2DBC (reactive). Flyway is the one deliberate exception — it needs a blocking JDBC driver, used only at boot to run migrations; the runtime path stays entirely on R2DBC. If you must use a blocking call elsewhere, isolate it with `.subscribeOn(Schedulers.boundedElastic())` and treat it as the exception, not the norm (per `AGENTS.md`).

## Pattern 1: the level-triggered pass loop

[DownloadTaskRunner.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java) is the project's core reactive pattern, replacing the retry-based poller that used to hold this role:

```java
subscription = Flux.interval(loopInterval)
        .onBackpressureDrop()
        .concatMap(tick -> pass())
        .subscribe();
```

Every tick runs one `pass()`: admit new work, then claim and step whatever is due — see [download-manager.md](download-manager.md) for what a pass actually does. **Level-triggered, not edge-triggered:** the loop never reacts to a notification that "a download needs attention" — it repeatedly asks the database what is due right now and acts on the answer. A lost wakeup (a dropped tick, a missed emit, a crashed process) then costs one loop interval, not a stranded download, because the next tick asks the same question again and gets the same answer.

This is the direct replacement for the deleted `ReactivePoller` + doubling-backoff engine and the deleted in-heap `Sinks` work queue (`DownloadQueue`/`DownloadWorker`). Both were edge-triggered in the sense that matters here: a `ReactivePoller` retry loop was a wait held in a live subscription, and a `Sinks` emission was a notification that existed exactly once and vanished on restart along with the process holding it. `next_attempt_at`, a plain column, replaces both: it is a wait that survives a crash because it never depended on anything staying in memory to fire.

`Flux.interval(...).concatMap(tick -> pass())` **serialises passes** — a slow pass delays the next tick. This is accepted for simplicity, not an oversight: leases already make overlapping passes safe (see [download-manager.md](download-manager.md#leases-not-a-reaper)), so switching to an overlapping-pass model (`flatMap` at this level) is a change this loop can absorb later with no other change required. `onBackpressureDrop()` means a tick that arrives while the previous `pass()` is still running is simply dropped rather than queued — there is nothing to lose by dropping a tick, since the next one asks the same "what's due" question.

## Pattern 2: stepping claimed rows concurrently — a different axis from Pattern 1

Within one pass, the rows a `CLAIM` won are stepped with `flatMap`, not `concatMap`:

```java
return Mono.zip(searches, transfers)
        .flatMap(fetched -> Flux.fromIterable(claimed)
                .flatMap(task -> stepOne(task, fetched.getT1(), fetched.getT2()), batchSize)
                .then());
```

**This project got the distinction between these two operators wrong once already, so it is worth stating precisely.** Pass-level serialisation (Pattern 1) and row-level concurrency (this pattern) are independent axes — one is about ordering ticks of the same loop, the other is about ordering independent pieces of work discovered within a single tick. An earlier version used `concatMap` for both, which meant the rows claimed inside one pass were stepped strictly one at a time: with `batch-size: 10` and slskd's 10-second HTTP timeout, a single pass could take up to 100 seconds — reproducing, inside one pass, the exact head-of-line blocking this whole design exists to eliminate between passes. Nothing about stepping row A requires row B to finish first, so `flatMap(batchSize)` is the correct operator: it needs no thread pool, because WebFlux already runs an event loop per core — the earlier bug was purely a matter of using the wrong Reactor operator for independent work, not a concurrency-primitives problem.

The rule of thumb this project now follows: `concatMap` where later work must wait for earlier work to finish (successive passes over the same due-work query, so results stay consistent); `flatMap` where multiple items are independent and none needs to wait on another (rows claimed within one pass, each driving its own download).

## Pattern 3: isolating one bad step from the rest of a pass

[DownloadTaskRunner.stepOne](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRunner.java) and the pass itself both guard against one failure tearing down the loop:

```java
return executor.execute(task, searchesById, transfersById)
        .flatMap(decision -> apply(task, decision))
        .onErrorResume(error -> {
            log.error("Download {} step {} could not be applied; the lease will expire and "
                    + "the row will be retried", task.downloadId(), task.phase(), error);
            return Mono.empty();
        });
```

Every task is isolated: an error applying one row's decision is logged and swallowed, not propagated into the `flatMap` stepping the rest of the batch. Because the row's lease was already stamped and nothing cleared it, the row simply becomes claimable again once the lease expires — the failure and the retry mechanism are the same lease machinery described in [download-manager.md](download-manager.md#leases-not-a-reaper). `DownloadStepExecutor.execute` goes one step further and never lets an slskd call failure propagate as an error signal at all — it maps a failed call to `DownloadStateMachine.onCallFailed`, so the caller always has a decision to write rather than an exception to handle.

## Testing reactive code

Use reactor-test `StepVerifier` for assertions. `DownloadStateMachine` itself needs none of this — it is a pure function of `(task, response, now)`, so its branch matrix is tested with plain JUnit assertions and no mocking of HTTP or the clock. See [testing.md](testing.md).
