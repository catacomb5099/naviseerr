# Persistence (R2DBC + Postgres, Flyway)

> Status: current as of 2026-08-13, branch `durable-download-state-machine`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

Naviseerr persists download state in Postgres via Spring Data R2DBC (reactive, non-blocking - no JDBC in the runtime path). Three tables now: `downloads` (the low-churn, user-facing record), `songs` (one song's metadata - name, artists, image), and `download_tasks` (the working state of one download's pipeline, written every few seconds).

## Configuration

[application.yaml](../../src/main/resources/application.yaml):

- `spring.r2dbc.url|username|password` - env-backed with localhost defaults (`r2dbc:postgresql://localhost:5432/naviseerr`). This is the only connection the running application uses.
- `spring.sql.init.mode: never` - schema is no longer applied by Spring on startup; Flyway owns it now (below).
- `spring.flyway.*` - `enabled: true`, `baseline-on-migrate: true`, `baseline-version: 1`, plus its own `url`/`user`/`password` pointed at the same database over a **blocking JDBC** connection, used only at boot to run migrations. The runtime path stays entirely on R2DBC; nothing else uses the JDBC connection.

## Flyway layout

`db/migration/` under `src/main/resources`:

- [V1__baseline.sql](../../src/main/resources/db/migration/V1__baseline.sql) - the `downloads` table and its index, moved out of the retired `schema.sql` verbatim.
- [V2__download_tasks.sql](../../src/main/resources/db/migration/V2__download_tasks.sql) - the `download_tasks` table and its partial index (see below).
- V3/V4 add progress tracking and the download feed's supporting columns/indexes; not detailed in this doc yet.
- [V5__song_metadata.sql](../../src/main/resources/db/migration/V5__song_metadata.sql) - creates `songs` (see below), backfills one row per existing `downloads.song_name`, adds `download_tasks.song_id` (nullable), and drops the `NOT NULL` on both `song_name` columns without dropping the columns themselves. The first migration in this repo to run a real data backfill rather than an additive `CREATE TABLE`/`ADD COLUMN` - see AGENTS.md's "Schema Management Approach" for why that specifically needed Flyway. `song_id` stays nullable here on purpose: at this point `DownloadTaskRepository.ADMIT_SQL` does not populate it yet, and a same-transaction column `DEFAULT` cannot look a value up in another table, so `NOT NULL` here would fail every download admitted before the code catches up.
- [V6__download_tasks_song_id_not_null.sql](../../src/main/resources/db/migration/V6__download_tasks_song_id_not_null.sql) - tightens `download_tasks.song_id` to `NOT NULL`, once `ADMIT_SQL` (rewritten in the same plan) populates it on every insert. Carries its own defensive `UPDATE ... WHERE song_id IS NULL` backfill first, for any install that admitted a download in the window between `V5` and the `ADMIT_SQL` rewrite - not just a formality for a fresh install where the two migrations always run back to back. A separate migration from `V5` rather than an edit to it, because `V5` was already reviewed and merged before this landed, and a migration is immutable from the moment it is committed. See `docs/decisions/song-metadata-table-31-08-2026.md` for the full "expand now, tighten within-release, contract next release" reasoning, including the still-pending `V7` that will drop `song_name` from both `downloads` and `download_tasks`.

`baseline-on-migrate: true` with `baseline-version: 1` tells Flyway that any install already holding a `downloads` table (i.e. every existing install, which used `schema.sql` + `spring.sql.init` before this change) should be treated as already being at `V1`, rather than trying to recreate it. Do not remove that setting — without it, Flyway refuses to run against a non-empty schema it doesn't recognise. New migrations from here on are plain, ordered `V*__description.sql` files; nothing about `V1`/`V2` is special beyond "already applied on every existing install."

Why Flyway now, when `CREATE TABLE IF NOT EXISTS` (the old approach) was technically sufficient for adding `download_tasks`: naviseerr is continuously updated software installed by other people, and upcoming work (`PARTIAL_SUCCESS` and `CANCELLED` on `downloads.status`) needs to alter an existing `CHECK` constraint, which `IF NOT EXISTS`-style idempotent DDL cannot express. Baselining while the schema is still two tables is materially easier than baselining later across a population of installs at varying versions. See `AGENTS.md`'s "Schema Management Approach" and [docs/decisions/durable-download-state-machine-13-08-2026.md](../decisions/durable-download-state-machine-13-08-2026.md) ("Introduce Flyway now") for the full history, including the reversed earlier conclusion.

## Schema

`downloads` ([V1__baseline.sql](../../src/main/resources/db/migration/V1__baseline.sql)):

```sql
CREATE TABLE downloads (
    download_id UUID PRIMARY KEY,
    song_name   TEXT NOT NULL,
    status      TEXT NOT NULL
                CHECK (status IN ('PENDING', 'IN_PROGRESS', 'FAILED', 'SUCCEEDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_downloads_status_created_at ON downloads (status, created_at);
```

`downloads.song_name` is now a **dead column** - see "The `songs` table" below.

`songs` ([V5__song_metadata.sql](../../src/main/resources/db/migration/V5__song_metadata.sql)):

```sql
CREATE TABLE songs (
    song_id     UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    download_id UUID   NOT NULL REFERENCES downloads (download_id),
    name        TEXT   NOT NULL,
    artists     TEXT[] NOT NULL DEFAULT '{}',
    image_url   TEXT
);

CREATE INDEX idx_songs_download_id ON songs (download_id);
```

One song's metadata - name, artists, cover image - written once, atomically with its `downloads` row
(`DownloadService.requestDownload`'s single `INSERT ... SELECT ... FROM created` CTE), so a download
without a song cannot exist. `artists` is `TEXT[]`, not JSON in a `TEXT` column like `candidates`
below: this table is read on paths polled every few seconds (`CLAIM_DUE_SQL`, the download feed), so
skipping a JSON round trip on every read outweighs the schema flexibility JSON would buy for a field
that is a flat list of strings and nothing more. See
[docs/decisions/song-metadata-table-31-08-2026.md](../decisions/song-metadata-table-31-08-2026.md) for
the full rationale, including why the foreign key runs `download_tasks -> songs` rather than the other
way around.

`downloads.song_name` and `download_tasks.song_name` (below) are both **dead**: no production code
path writes or reads either any more. The one exception is the already-dead
`DownloadService.claimPendingDownloads`/`CLAIM_PENDING_SQL` (still selecting `song_name`) and
`Download.songName` (the `@Column("song_name")` field it reads through) - both must be removed
alongside the column drop, not just the column itself. Both columns are kept, nullable, only so a
rollback to a pre-`songs`-table server image still has a column to read; a future `V7` migration
drops them. Do not reintroduce a write or a read of either - see [gotchas.md](gotchas.md).

`download_tasks` ([V2__download_tasks.sql](../../src/main/resources/db/migration/V2__download_tasks.sql), `song_id` added by [V5](../../src/main/resources/db/migration/V5__song_metadata.sql)/[V6](../../src/main/resources/db/migration/V6__download_tasks_song_id_not_null.sql)):

```sql
CREATE TABLE download_tasks (
    download_id       UUID PRIMARY KEY REFERENCES downloads (download_id),
    song_id           UUID        NOT NULL REFERENCES songs (song_id),  -- added V5, NOT NULL as of V6
    song_name         TEXT,                                             -- dead; NOT NULL dropped by V5
    phase             TEXT        NOT NULL
                                  CHECK (phase IN ('SEARCH_INIT', 'SEARCH_POLL',
                                                   'DOWNLOAD_INIT', 'DOWNLOAD_POLL',
                                                   'SUCCEEDED', 'FAILED')),
    phase_entered_at  TIMESTAMPTZ NOT NULL,
    next_attempt_at   TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ,
    failure_reason    TEXT,
    lease_owner       TEXT,
    lease_expires_at  TIMESTAMPTZ,
    search_id         TEXT,
    candidates        TEXT        NOT NULL DEFAULT '[]',
    candidate_index   INT         NOT NULL DEFAULT 0,
    retry_index       INT         NOT NULL DEFAULT 0,
    slskd_username    TEXT,
    slskd_filename    TEXT,
    slskd_transfer_id TEXT,
    last_error        TEXT
);

CREATE INDEX idx_download_tasks_due ON download_tasks (next_attempt_at)
    WHERE phase NOT IN ('SUCCEEDED', 'FAILED');
```

Status/phase are `TEXT` + `CHECK` (not native Postgres enums) so R2DBC maps the Java enums to/from text automatically with no codec config.

`download_tasks` is deliberately a separate table from `downloads`, not new columns on it: it isolates the write-every-few-seconds churn away from the low-churn table history queries read, and it needs nothing `downloads`' entity mapping (`Download.java`, `DownloadServiceClaimIT`) has to know about. `candidates` is `TEXT` holding a JSON array (`DownloadCandidate`), not `JSONB` — the list is written once and read whole, never queried by content, so `JSONB`'s indexing/operators buy nothing, and JSON-in-`TEXT` means adding a field to `DownloadCandidate` later needs no migration at all.

**Task rows are retained in a terminal phase (`SUCCEEDED`/`FAILED`), never deleted.** A self-hoster filing a bug report needs to answer "which peers were tried, and how did each fail?" from their own instance, and per-song history is exactly what a future collection feature needs too. The **partial index** `idx_download_tasks_due` is what makes that retention free: it covers only non-terminal rows (`WHERE phase NOT IN ('SUCCEEDED', 'FAILED')`), so the due-work query's cost stays independent of how much finished history has accumulated. Without that partial predicate, retaining rows forever would mean the due-work query degrades as the table grows — the two decisions (retain, and index only what's live) are a pair.

## Entity and status

- [Download.java](../../src/main/java/com/catacomb5099/naviseerr/download/Download.java) - `@Table("downloads")`, `@Id @Column("download_id") UUID downloadId`, plus `songName` (dead field, see above), `status` (`DownloadStatus`), `createdAt` (`Instant`). Lombok `@Data/@Builder`. Untouched by the state-machine work, so `DownloadServiceClaimIT` stays green.
- [Song.java](../../src/main/java/com/catacomb5099/naviseerr/download/Song.java) - `@Table("songs")`, `@Id @Column("song_id") UUID songId`, plus `downloadId`, `name`, `artists` (`List<String>`), `imageUrl`. Lombok `@Data/@Builder`. `artists` round-trips through Spring Data R2DBC's Postgres array support as `List<String>` with no `String[]` needed on the write side - `SongEntityIT` is the test that settled it (see the risk table in the implementation plan: `String[]` was the documented fallback if it hadn't worked). Written by `R2dbcEntityTemplate`-style insert inside `DownloadService.requestDownload`'s CTE, never updated afterwards.
- [DownloadStatus.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadStatus.java) - `PENDING`, `IN_PROGRESS`, `FAILED`, `SUCCEEDED`.
- `download_tasks` has no `@Table`-mapped entity — [DownloadTaskRepository.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java) uses raw `DatabaseClient` SQL exclusively (below), because every statement needs something Spring Data's derived-query mapping cannot express: `FOR UPDATE SKIP LOCKED`, `RETURNING`, or a data-modifying CTE. Rows are read into and written from [DownloadTask.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java), a plain record.

## `DownloadTaskRepository` operations

All in [DownloadTaskRepository.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java):

- **`admitNewDownloads(limit, now)`** - the admit CTE. Atomically finds non-terminal `downloads` rows with no task row, inserts one for each at `SEARCH_INIT`, and flips those `downloads` rows to `IN_PROGRESS` - all in one statement:

```sql
WITH admitted AS (
    SELECT d.download_id, s.song_id
      FROM downloads d
      JOIN songs s ON s.download_id = d.download_id
     WHERE d.status IN ('PENDING', 'IN_PROGRESS')
       AND NOT EXISTS (SELECT 1 FROM download_tasks t
                        WHERE t.download_id = d.download_id)
     ORDER BY d.created_at
       FOR UPDATE OF d SKIP LOCKED
     LIMIT :limit
), created AS (
    INSERT INTO download_tasks
        (download_id, song_id, phase, phase_entered_at, next_attempt_at)
    SELECT download_id, song_id, 'SEARCH_INIT', :now, :now FROM admitted
    ON CONFLICT (download_id) DO NOTHING
    RETURNING download_id
)
UPDATE downloads SET status = 'IN_PROGRESS'
 WHERE download_id IN (SELECT download_id FROM created)
```

  Matches `PENDING` **or** `IN_PROGRESS`, not just `PENDING` - it turns "every non-terminal download has a task row" into an invariant the loop continuously restores, so a download that somehow loses its task row self-heals on the next pass. `NOT EXISTS` rather than a `LEFT JOIN`, because `FOR UPDATE` cannot be applied across an outer join. `ON CONFLICT DO NOTHING` guards a concurrent admit racing on the same row.

  The join to `songs` is what supplies `song_id` (as of `V5`/`V6`, above) - safe as an inner join because a download and its song are created by one CTE (`DownloadService.requestDownload`), so a download without a song cannot exist. **`FOR UPDATE OF d SKIP LOCKED`, not a bare `FOR UPDATE`:** a bare lock clause over a joined query takes row locks in every table in the `FROM`, so without naming the `d` alias explicitly this statement would start locking `songs` rows it has never needed to lock. See [download-manager.md](download-manager.md#the-admit-and-claim-sql) and [docs/decisions/song-metadata-table-31-08-2026.md](../decisions/song-metadata-table-31-08-2026.md) for the full reasoning.

- **`claimDueTasks(limit, owner, now, lease, transferSlotsFree)`** - the lease-based claim. Stamps `lease_owner`/`lease_expires_at` on due, unleased, non-terminal rows and returns them, now joining `songs` for the name and artists the loop needs to word a Soulseek query:

```sql
UPDATE download_tasks t
   SET lease_owner = :owner,
       lease_expires_at = :leaseExpiresAt
  FROM songs s
 WHERE s.song_id = t.song_id
   AND t.download_id IN (
       SELECT download_id FROM download_tasks
        WHERE next_attempt_at <= :now
          AND phase NOT IN ('SUCCEEDED', 'FAILED')
          AND (:transferSlotsFree OR phase <> 'DOWNLOAD_INIT')
          AND (lease_expires_at IS NULL OR lease_expires_at < :now)
        ORDER BY next_attempt_at
          FOR UPDATE SKIP LOCKED
        LIMIT :limit)
RETURNING t.download_id, s.name, s.artists, t.phase, t.phase_entered_at,
          t.next_attempt_at, t.search_id, t.candidates, t.candidate_index,
          t.retry_index, t.slskd_username, t.slskd_filename, t.slskd_transfer_id,
          t.last_error, t.progress_percent
```

  `lease_expires_at IS NULL OR lease_expires_at < :now` is what lets a dead process's row be reclaimed without a separate reaper - see [download-manager.md](download-manager.md#leases-not-a-reaper). The `transferSlotsFree` branch excludes `DOWNLOAD_INIT` rows from the claim entirely (rather than claiming and deferring them) when `max-concurrent-transfers` has no free slot.

  **The `songs` join arrives through `FROM`, not the `RETURNING` list**, because `UPDATE ... RETURNING` can only return columns of the table being updated (`download_tasks`) - `s.name`/`s.artists` have to come from somewhere else in the statement, and `UPDATE ... FROM ... RETURNING` is a shape used nowhere else in this codebase. `FOR UPDATE SKIP LOCKED` still selects only from `download_tasks` (inside the `IN (...)` subquery), so it still locks only task rows; the outer `UPDATE`'s join to `songs` merely reads it. This reverses V2's original reasoning for denormalising `song_name` onto `download_tasks` - see [docs/decisions/song-metadata-table-31-08-2026.md](../decisions/song-metadata-table-31-08-2026.md) for what the join now costs (a primary-key lookup into `songs` for at most `batch-size` rows per pass - noted there as never confirmed with `EXPLAIN` against a populated table).

- **`save(task)`** - writes every field of a `DownloadTask` back (no partial-update logic, since the record *is* the complete state) and clears the lease, which is what makes the row visible to the next pass.
- **`countActiveDownloads()`** / **`countActiveTransfers()`** - back the two capacity bounds in [download-manager.md](download-manager.md#three-independent-bounds): the first counts `downloads` rows (`status = 'IN_PROGRESS'`), the second counts task rows in `DOWNLOAD_POLL` only. `DOWNLOAD_INIT` is deliberately excluded: it always has a null `slskd_transfer_id` (no real transfer exists yet), so counting it against the same cap that gates claiming `DOWNLOAD_INIT` rows would let enough `DOWNLOAD_INIT` rows close the gate permanently - a durable deadlock no restart could clear.

## The terminal CTE

[DownloadService.finishDownload](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java) is the other atomic statement worth knowing, since it is the one place `downloads` and `download_tasks` are written together:

```sql
WITH updated AS (
    UPDATE downloads
       SET status = :status
     WHERE download_id = :id
       AND status NOT IN ('SUCCEEDED', 'FAILED')
    RETURNING download_id
)
UPDATE download_tasks
   SET phase = :status,
       phase_entered_at = :now,
       finished_at = :now,
       failure_reason = :reason,
       lease_owner = NULL,
       lease_expires_at = NULL
 WHERE download_id = :id
```

The task half is unconditional on the `downloads` half matching anything, deliberately - see [download-manager.md](download-manager.md#the-atomic-terminal-write) for why a conditional write would livelock. Full walkthrough there; this doc is the SQL reference.

## Array-column reads

`songs.artists` is `TEXT[] NOT NULL DEFAULT '{}'`, but Spring Data R2DBC's row mapper does not hand
back a `List<String>` on a hand-written `DatabaseClient` query the way it does for a `@Table`-mapped
entity read (`Song`, via `R2dbcEntityTemplate`). Both raw-SQL read paths that touch this column read
it as a Java array and null-guard it to an empty list, the same idiom in both places:

```java
String[] artists = row.get("artists", String[].class);
List<String> asList = artists == null ? List.of() : List.of(artists);
```

- [DownloadTaskRepository.toTask](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java) - reads `s.artists` off `CLAIM_DUE_SQL`'s join and wraps it into the `TrackQuery` carried on `DownloadTask.query()`.
- [ActiveDownloadRepository.toView](../../src/main/java/com/catacomb5099/naviseerr/download/ActiveDownloadRepository.java) - reads the same column off the download feed's shared `PROJECTION` and wraps it into `ActiveDownloadView.artists()`.

The null guard is defensive, not load-bearing: the column's own `NOT NULL DEFAULT '{}'` means a real
`NULL` should never arrive. Both call sites keep the guard anyway because an array is not itself a
`List`, and every caller downstream (`TrackQuery`, `ActiveDownloadView`) promises callers an empty
list, never a null, so the conversion has to happen exactly once at the row boundary regardless.

## Patterns worth reusing

- Prefer `R2dbcEntityTemplate.insert(entity)` for app-generated UUID inserts (`DownloadService.requestDownload`).
- For atomic claim/state transitions, a single `UPDATE ... RETURNING` (optionally `FOR UPDATE SKIP LOCKED`) avoids read-modify-write races.
- When a write must touch two tables atomically and the runtime has no transaction manager wired for raw `DatabaseClient` SQL, a data-modifying CTE is the escape hatch - Postgres runs each CTE branch exactly once, inside one statement's implicit transaction, even when nothing references the CTE's output.
- Map raw rows manually (`DownloadTaskRepository.toTask`) when the shape doesn't fit a Spring Data entity; use `entityTemplate.getConverter().read(Type.class, row, meta)` when it does (`DownloadService.claimPendingDownloads`, a pre-existing method kept for its own test coverage but not called from the pass loop).
- Bind enums as `enum.name()` when writing raw SQL against a `TEXT` column.
- A partial index (`WHERE <predicate>`) is the way to keep a hot query fast on a table that also retains unbounded historical rows - only index what the hot query actually needs to see.
- Reading a Postgres array column off a hand-mapped row: `row.get("col", String[].class)`, null-guarded to `List.of()` - see "Array-column reads" above.

## Related docs

- How these operations are driven: [download-manager.md](download-manager.md)
- ADR: [durable-download-state-machine](../decisions/durable-download-state-machine-13-08-2026.md) (this table, the Flyway decision, retention); [minimal-postgres-downloads](../decisions/minimal-postgres-downloads-26-06-2026.md) (the original `downloads` table); [song-metadata-table](../decisions/song-metadata-table-31-08-2026.md) (the `songs` table, the `download_tasks.song_id` FK, the reversed V2 denormalisation)
- Schema/startup caveats: [gotchas.md](gotchas.md)
