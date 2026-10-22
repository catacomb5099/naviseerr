# Idle memory: fixed JVM flags in the server image

**Date:** 20-10-2026
**Status:** Accepted. Measured on the laptop's sealed install; the owner checks it on the NAS first
(the compose lines in `naviseerr-install-1020/TRY-memory.md`) and merges after pasting the number.

## Context

The owner's install sat at about 1.75 GB on idle. The server image started the JVM with no memory
settings, so it got the G1 collector, a heap ceiling of a quarter of the host's RAM (8 GB on a 32 GB
NAS), both JIT compilers and glibc's per-CPU malloc arenas. All of that suits a busy multi-core
server, not an app that serves one person and is otherwise idle. Live data after a full GC is 21 MB.

## Decisions

- The image sets `JDK_JAVA_OPTIONS="-XX:+UseSerialGC -Xms32m -Xmx512m -XX:TieredStopAtLevel=1
  -XX:+ExitOnOutOfMemoryError"` and `MALLOC_ARENA_MAX=2`. Measured after a browsing load: 441 MB as
  shipped, 183 MB with these. `MALLOC_ARENA_MAX=2` alone is worth 90-150 MB, Serial GC about 55 MB,
  the light JIT about 40 MB, `-Xms32m` about 24 MB. The ceiling costs nothing at idle (256 MB and
  512 MB measured the same); 512 MB leaves room for two 16 MB slskd answers parsed at once.
- Out of heap = the container exits and `restart: unless-stopped` brings it back; download rows carry
  on from their leases. Better than limping on.
- `spring.r2dbc.pool.initial-size: 1`: Postgres keeps 2 idle connections instead of 10 (84 -> 68 MB).
- Only the image changes. `./gradlew bootRun`, IntelliJ and a plain `java -jar` keep the JVM defaults.

## Rejected

- Thread stack size, Netty worker count, a smaller code cache: about 5 MB together.
- AppCDS / Spring AOT cache: -9 MB, needs a training run in the Docker build.
- GraalVM native image: would get well below 150 MB, but reflection hints, jaudiotagger, Flyway and
  minutes of build time per architecture.
- A slower idle loop: saves CPU (about 1 % of one core), not memory.

## How to flip

- Different ceiling: `JDK_JAVA_OPTIONS: "-XX:+UseSerialGC -Xms32m -Xmx1g ..."` on the container.
- The JVM's own defaults back: `JDK_JAVA_OPTIONS: ""` and `MALLOC_ARENA_MAX: ""`.
- The full JIT back (CPU-heavy steps such as fuzzy-matching file names run faster): drop
  `-XX:TieredStopAtLevel=1`.
