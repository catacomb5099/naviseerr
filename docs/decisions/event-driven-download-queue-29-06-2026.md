# Decisions: event-driven-download-queue

- **Date:** 29-06-2026
- **Topic:** event-driven-download-queue

---

## Decision: In-memory Reactor Sinks queue (not DB polling, not a broker)
**Context:** Need a queue that sleeps when empty and wakes immediately on a new request, feeding the slskd flow.
**Options Considered:**
1. *Reactor `Sinks.many().unicast().onBackpressureBuffer()`* — idiomatic, composes with `flatMap` concurrency/backpressure, no new deps; in-memory only.
2. *`Flux.create` + `FluxSink`* — similar bridge, better multi-producer ergonomics, slightly awkward wiring.
3. *`BlockingQueue`/`ExecutorService`* — simple sleep/wake but blocking, fights the WebFlux model.
4. *Durable broker (RabbitMQ/Redis) or DB-as-queue* — durable/multi-instance but heavy for the MVP.
**Final Choice:** Option 1.
**Rationale:** Single producer (the interval claimer) makes emission naturally serialized, so a plain `tryEmitNext` is enough; most reactive-native fit and adds no infra. Broker is the documented end-state.

---

## Decision: Keep PENDING->IN_PROGRESS on the interval claimer, not on the request
**Context:** Where should the transition that triggers enqueueing happen?
**Options Considered:**
1. *On the HTTP request* — transition + enqueue synchronously when the download is requested.
2. *On a separate interval claimer* — request only inserts PENDING; the claimer flips to IN_PROGRESS and enqueues.
**Final Choice:** Option 2 (per user instruction).
**Rationale:** Keeps the request flow trivial (insert + 202), preserves the existing `FOR UPDATE SKIP LOCKED` batch claim, and makes the DB the durable ingress. "No polling" applies to the queue->worker hop; the claim is deliberately interval-driven.

---

## Decision: Bounded worker concurrency (default 3)
**Context:** A slow download must not block all others; slskd is a shared external source.
**Options Considered:**
1. *Sequential (concatMap)* — simplest, but head-of-line blocking.
2. *Bounded parallel (flatMap, N)* — throughput without hammering slskd.
3. *Unbounded* — risk of overwhelming slskd / unbounded fan-out.
**Final Choice:** Option 2, `download-worker.concurrency` default 3 (per user).
**Rationale:** Balances throughput and external-source load; configurable.

---

## Decision: No crash-recovery for in-flight work (MUST be added)
**Context:** The in-memory queue loses queued/in-flight items on restart.
**Options Considered:**
1. *Add startup recovery now* — reaper for orphaned IN_PROGRESS + re-enqueue.
2. *Defer recovery* — ship the simple version, document the gap.
**Final Choice:** Option 2 (per explicit user instruction).
**Rationale:** Keep the first cut simple. PENDING rows are auto-recovered by the next claim interval; only rows already IN_PROGRESS at crash are stranded. Flagged prominently in AGENTS.md as a MUST-DO (reaper/timeout, idempotent reprocessing, queue overflow handling, eventual durable broker).

---

## Decision: Name the acquisition flow "DownloadFulfillment", not "pipeline"
**Context:** The per-request chain was tentatively called a "pipeline".
**Options Considered:**
1. *DownloadFulfillment.fulfill* — domain intent; avoids clashing with the future broker "pipeline".
2. *DownloadProcessor.process* — consistent with existing Slskd*Processor naming.
3. *Keep "pipeline"* — overloaded; AGENTS.md reserves it for the RabbitMQ multi-step pipeline.
**Final Choice:** Option 1 (per user).
**Rationale:** Clear, future-proof, avoids terminology collision.

---

## Decision: Fix the broken test baseline
**Context:** `SearchServiceTest` referenced a removed `SearchService.download`/constructor, so the entire test source set failed to compile on the branch.
**Options Considered:**
1. *Convert it into `DownloadFulfillmentTest`* — it tested exactly the chain `DownloadFulfillment` now owns.
2. *Delete it outright* — loses coverage.
**Final Choice:** Option 1 (delete `SearchServiceTest`, add equivalent `DownloadFulfillmentTest`). Also made `NaviseerrApplicationTests` self-contained via Testcontainers so `./gradlew test` runs without an external DB.
**Rationale:** Restores a green, self-contained suite while preserving intent.
