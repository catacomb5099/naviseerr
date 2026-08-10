# Naviseerr Architecture (Agent Context)

> Status: current as of 2026-06-29, branch `event-driven-download-queue`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

These are deep-dive reference documents for the Naviseerr backend. They exist so that an agent (or a developer) starting a task can load accurate subsystem context without re-exploring the whole codebase from scratch.

## How these docs relate to the rest

- [`AGENTS.md`](../../AGENTS.md) - the high-level project guide (identity, stack, product direction, engineering rules, workflow). Read it first. It stays concise; the detail lives here.
- `docs/architecture/` (this folder) - subsystem deep-dives: how things actually work today, with file pointers.
- [`docs/decisions/`](../decisions) - ADRs: why a given approach was chosen (rationale + trade-offs).
- [`docs/conversations/`](../conversations) - per-session summaries and raw transcripts.

## How to use them (for agents)

1. Read `AGENTS.md`, then the topic doc(s) relevant to your task.
2. Treat the cited source files as the source of truth. These docs intentionally cite symbols and files rather than line numbers (line numbers drift).
3. If you change behavior a doc describes, update the doc in the same change.
4. Mind the freshness header at the top of each doc and the branch it was written against.

## Documents

- [codebase-map.md](codebase-map.md) - repo layout, package-by-package map, entry points, branch topology, how to build/run.
- [slskd-integration.md](slskd-integration.md) - the Soulseek (slskd) search -> select -> download -> poll pipeline and its retry/failover.
- [download-manager.md](download-manager.md) - the event-driven download queue: ingress, interval claim, in-memory queue, worker, terminal status.
- [persistence.md](persistence.md) - R2DBC + Postgres, the `downloads` table, claim and status-write SQL, the patterns used.
- [ytmusic-integration.md](ytmusic-integration.md) - YouTube Music metadata search (via the sidecar `ytmusic-adapter` service) and response mapping. The active search provider.
- [lastfm-integration.md](lastfm-integration.md) - LastFM metadata search and response mapping. **Superseded, unused, retained on disk** — see the doc above and its ADR.
- [reactive-patterns.md](reactive-patterns.md) - the project's Reactor cookbook: polling-via-retry and the Sinks work queue.
- [testing.md](testing.md) - unit (Mockito + StepVerifier) and integration (Testcontainers) testing, and how to run them.
- [gotchas.md](gotchas.md) - known bugs, foot-guns, and hygiene issues to be aware of before touching related code.

## Suggested read order

`codebase-map` -> the subsystem you are touching (`slskd-integration`, `download-manager`, `persistence`, or `ytmusic-integration` — search runs through YouTube Music now, not `lastfm-integration`) -> `reactive-patterns` -> `gotchas`. Read `testing` before adding or changing tests.

## Current vs target (important)

Much of the "download manager" direction in `AGENTS.md` (RabbitMQ for execution state, Redis for ephemeral progress, SSE streaming, DLQ) is the targeted end state, not what exists today. These docs describe what is actually implemented now and call out the gaps explicitly. When a doc says "target", assume it is not built yet.
