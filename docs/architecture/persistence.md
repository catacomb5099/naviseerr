# Persistence (R2DBC + Postgres, Flyway)

> Status: current as of 2026-08-13, branch `durable-download-state-machine`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

Naviseerr persists download state in Postgres via Spring Data R2DBC (reactive, non-blocking - no JDBC in the runtime path). Three tables: `downloads` (one request and its lifecycle), `download_tasks` (the working state of one song's pipeline, written every few seconds), and as of V6 `media_items` (what a YouTube id is: title, artists, artwork — see the V6 section below).

## Configuration

[application.yaml](../../src/main/resources/application.yaml):

- `spring.r2dbc.url|username|password` - env-backed with localhost defaults (`r2dbc:postgresql://localhost:5432/naviseerr`). This is the only connection the running application uses.
- `spring.sql.init.mode: never` - schema is no longer applied by Spring on startup; Flyway owns it now (below).
- `spring.flyway.*` - `enabled: true`, `baseline-on-migrate: true`, `baseline-version: 1`, plus its own `url`/`user`/`password` pointed at the same database over a **blocking JDBC** connection, used only at boot to run migrations. The runtime path stays entirely on R2DBC; nothing else uses the JDBC connection.

## Flyway layout

`db/migration/` under `src/main/resources`:

- [V1__baseline.sql](../../src/main/resources/db/migration/V1__baseline.sql) - the `downloads` table and its index, moved out of the retired `schema.sql` verbatim.
- [V2__download_tasks.sql](../../src/main/resources/db/migration/V2__download_tasks.sql) - the `download_tasks` table and its partial index (see below).

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

[V5__collection_downloads.sql](../../src/main/resources/db/migration/V5__collection_downloads.sql)
then made a `downloads` row one user REQUEST rather than one song:

```sql
ALTER TABLE downloads
    ADD COLUMN download_type TEXT NOT NULL DEFAULT 'SONG'
                            CHECK (download_type IN ('SONG', 'ALBUM', 'PLAYLIST')),
    ADD COLUMN youtube_id    TEXT;
ALTER TABLE downloads ALTER COLUMN song_name DROP NOT NULL;
-- widened for PARTIAL_SUCCESS
ALTER TABLE downloads DROP CONSTRAINT downloads_status_check;
ALTER TABLE downloads ADD CONSTRAINT downloads_status_check
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'FAILED', 'SUCCEEDED', 'PARTIAL_SUCCESS'));
```

`song_name` became nullable because the request now carries only a YouTube id: it is the download's
display title (a song's, or an album/playlist's), written by admission once the metadata call
returns. `PARTIAL_SUCCESS` is reachable only for a collection. See
[the ADR](../decisions/collection-downloads-14-09-2026.md).

`download_tasks` ([V2__download_tasks.sql](../../src/main/resources/db/migration/V2__download_tasks.sql)):

> [!IMPORTANT]
> V5 changed this table's grain. `download_id` was its primary key, which *was* the one-download-one-song
> assumption; a `task_id UUID` primary key replaced it, `download_id` became a plain indexed foreign
> key, and a `youtube_id TEXT` column was added. A song request has one row here; a ten-track album
> has ten. Read the DDL below with that substitution.

```sql
CREATE TABLE download_tasks (
    download_id       UUID PRIMARY KEY REFERENCES downloads (download_id),  -- V5: task_id UUID PRIMARY KEY
    song_name         TEXT        NOT NULL,
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

### V6: `media_items`, lifecycle timestamps, `position`

[V6__download_metadata.sql](../../src/main/resources/db/migration/V6__download_metadata.sql) — full
reasoning in [the ADR](../decisions/download-metadata-25-09-2026.md).

```sql
CREATE TABLE media_items (
    youtube_id       TEXT PRIMARY KEY,
    title            TEXT,
    artists          TEXT[]      NOT NULL DEFAULT '{}',
    image_url        TEXT,
    duration_seconds INT,        -- songs
    track_count      INT,        -- collections
    fetched_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE downloads ADD COLUMN admitted_at TIMESTAMPTZ, ADD COLUMN finished_at TIMESTAMPTZ,
                      ADD COLUMN failure_reason TEXT;
-- backfill: legacy rows get a synthetic youtube_id; every song_name becomes a media_items.title
ALTER TABLE downloads ALTER COLUMN youtube_id SET NOT NULL;
ALTER TABLE downloads DROP COLUMN song_name;
ALTER TABLE download_tasks ADD COLUMN position INT;
```

Written by `DownloadTaskRepository.upsertMedia`, one statement for N rows: the batch is bound as one
JSON document and `jsonb_to_recordset(:items::jsonb) AS x("youtubeId" text, ..., artists text[], ...)`
turns it back into rows — the quoted column names are `MediaItem`'s record components verbatim, so
Jackson's default output is the input. `ON CONFLICT (youtube_id) DO UPDATE` with `COALESCE(EXCLUDED.x,
media_items.x)` refreshes but never blanks. The repository deduplicates ids before binding, because a
playlist can list one track twice and `ON CONFLICT DO UPDATE` refuses to touch a row twice in one
statement.

Read by every `ActiveDownloadRepository` query via `LEFT JOIN media_items m ON m.youtube_id =
d.youtube_id` (LEFT: a QUEUED download has no row yet), and by `SONGS_SQL` via
`download_tasks.youtube_id`. `artists` comes off the row as `String[]`.

`download_tasks.song_name` is untouched by V6 and is the Soulseek query wording, `"Title - Primary
Artist"` — not a display field. `download_tasks.position` is written from `unnest(...) WITH
ORDINALITY` in `CREATE_TASKS_SQL`.

## Entity and status

- [Download.java](../../src/main/java/com/catacomb5099/naviseerr/download/Download.java) - `@Table("downloads")`, `@Id @Column("download_id") UUID downloadId`, plus `youtubeId`, `downloadType` (`DownloadType`), `songName`, `status` (`DownloadStatus`), `createdAt` (`Instant`). Lombok `@Data/@Builder`. One `@Id` only, on `downloadId`; a second would make R2DBC treat that column as the identity.
- [DownloadStatus.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadStatus.java) - `PENDING`, `IN_PROGRESS`, `FAILED`, `SUCCEEDED`.
- `download_tasks` has no `@Table`-mapped entity — [DownloadTaskRepository.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java) uses raw `DatabaseClient` SQL exclusively (below), because every statement needs something Spring Data's derived-query mapping cannot express: `FOR UPDATE SKIP LOCKED`, `RETURNING`, or a data-modifying CTE. Rows are read into and written from [DownloadTask.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTask.java), a plain record.

## `DownloadTaskRepository` operations

All in [DownloadTaskRepository.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadTaskRepository.java):

> [!IMPORTANT]
> V5 split admission in two. The single statement described immediately below no longer exists,
> because what task rows a download needs is now a question only `ytmusic-adapter` can answer, so an
> HTTP call has to happen in the middle of it. `admitDownloads(limit)` selects `PENDING` rows with no
> task row and **writes nothing**; `createTasks(downloadId, title, tasks, now)` then inserts one row
> per song and flips the status, still in one statement. The first half writing nothing is what makes
> the split safe: a crash between them leaves the row untouched for the next pass. Admission also
> narrowed to `PENDING` only — see below for why it used to be broader. Keep reading the statement
> below for the reasoning that carried over.

- **`admitNewDownloads(limit, now)`** *(V5: replaced by `admitDownloads` + `createTasks`)* - the admit CTE. Atomically finds non-terminal `downloads` rows with no task row, inserts one for each at `SEARCH_INIT`, and flips those `downloads` rows to `IN_PROGRESS` - all in one statement:

```sql
WITH admitted AS (
    SELECT d.download_id, d.song_name
      FROM downloads d
     WHERE d.status IN ('PENDING', 'IN_PROGRESS')
       AND NOT EXISTS (SELECT 1 FROM download_tasks t
                        WHERE t.download_id = d.download_id)
     ORDER BY d.created_at
       FOR UPDATE SKIP LOCKED
     LIMIT :limit
), created AS (
    INSERT INTO download_tasks
        (download_id, song_name, phase, phase_entered_at, next_attempt_at)
    SELECT download_id, song_name, 'SEARCH_INIT', :now, :now FROM admitted
    ON CONFLICT (download_id) DO NOTHING
    RETURNING download_id
)
UPDATE downloads SET status = 'IN_PROGRESS'
 WHERE download_id IN (SELECT download_id FROM created)
```

  Matches `PENDING` **or** `IN_PROGRESS`, not just `PENDING` - it turns "every non-terminal download has a task row" into an invariant the loop continuously restores, so a download that somehow loses its task row self-heals on the next pass. *(V5: narrowed to `PENDING`. That breadth existed to make this statement's own two halves crash-safe; since the inserts and the flip are still one statement, an `IN_PROGRESS` download always has task rows and there is nothing to recover.)* `NOT EXISTS` rather than a `LEFT JOIN`, because `FOR UPDATE` cannot be applied across an outer join. `ON CONFLICT DO NOTHING` guards a concurrent admit racing on the same row.

- **`claimDueTasks(limit, owner, now, lease, transferSlotsFree)`** - the lease-based claim. Stamps `lease_owner`/`lease_expires_at` on due, unleased, non-terminal rows and returns them:

```sql
UPDATE download_tasks
   SET lease_owner = :owner,
       lease_expires_at = :leaseExpiresAt
 WHERE download_id IN (   -- V5: task_id IN (SELECT task_id ...)
       SELECT download_id FROM download_tasks
        WHERE next_attempt_at <= :now
          AND phase NOT IN ('SUCCEEDED', 'FAILED')
          AND (:transferSlotsFree OR phase <> 'DOWNLOAD_INIT')
          AND (lease_expires_at IS NULL OR lease_expires_at < :now)
        ORDER BY next_attempt_at
          FOR UPDATE SKIP LOCKED
        LIMIT :limit)
RETURNING download_id, song_name, phase, phase_entered_at, next_attempt_at, search_id,
          candidates, candidate_index, retry_index, slskd_username,
          slskd_filename, slskd_transfer_id, last_error
```

  `lease_expires_at IS NULL OR lease_expires_at < :now` is what lets a dead process's row be reclaimed without a separate reaper - see [download-manager.md](download-manager.md#leases-not-a-reaper). The `transferSlotsFree` branch excludes `DOWNLOAD_INIT` rows from the claim entirely (rather than claiming and deferring them) when `max-concurrent-transfers` has no free slot.

- **`save(task, owner)`** - writes every field of a `DownloadTask` back (no partial-update logic, since the record *is* the complete state) and clears the lease, which is what makes the row visible to the next pass. Keys on `task_id` as of V5; keying it on `download_id` would step every song of an album on one song's slskd response.
- **`concludeDownloads()`** *(V5)* - gives a download its terminal status once every one of its tasks is terminal, `PARTIAL_SUCCESS` when there is one of each outcome. Run at the end of every pass and idempotent (`AND d.status = 'IN_PROGRESS'` stops it matching a second time). This exists because the aggregate **cannot** be folded into the per-task terminal write: two songs finishing concurrently would each see the other as still running and neither would conclude, leaving the download `IN_PROGRESS` forever. [The ADR](../decisions/collection-downloads-14-09-2026.md) has the full argument.
- **`failUnadmitted(downloadId, code, now)`** *(V5; V6 added the code and timestamp)* - fails a request whose metadata call returned a 400/404, which is the one failure with no task row to record it on — so it writes `downloads.failure_reason` and `downloads.finished_at` itself.
- **`upsertMedia(items)`** *(V6)* - writes the `media_items` rows for one adapter answer; see the V6 section above. Called by the runner BEFORE `createTasks`, so a crash between the two leaves harmless extra metadata rather than nameless task rows.
- **`countActiveDownloads()`** / **`countActiveTransfers()`** - back the two capacity bounds in [download-manager.md](download-manager.md#three-independent-bounds): the first counts `downloads` rows (`status = 'IN_PROGRESS'`), the second counts task rows in `DOWNLOAD_POLL` only. `DOWNLOAD_INIT` is deliberately excluded: it always has a null `slskd_transfer_id` (no real transfer exists yet), so counting it against the same cap that gates claiming `DOWNLOAD_INIT` rows would let enough `DOWNLOAD_INIT` rows close the gate permanently - a durable deadlock no restart could clear.

## The terminal CTE

> [!IMPORTANT]
> V5 split this too. The statement below is now `FINISH_TASK_SQL`: it settles ONE SONG's task row,
> keyed on `task_id`, and does not touch `downloads` at all. Its `WITH updated AS (UPDATE downloads
> ...)` half became `concludeDownloads` above, for the concurrency reason given there. The
> idempotence argument below still holds and is still load-bearing; the "no livelock" half of the
> guard is gone, because a download's status is now derived FROM the task rows rather than written
> beside them, so there is no path by which one can go terminal without the other.

[DownloadService.finishTask](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java) *(was `finishDownload`)* is the other atomic statement worth knowing, since it used to be the one place `downloads` and `download_tasks` were written together:

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

## Patterns worth reusing

- Prefer `R2dbcEntityTemplate.insert(entity)` for app-generated UUID inserts (`DownloadService.requestDownload`).
- For atomic claim/state transitions, a single `UPDATE ... RETURNING` (optionally `FOR UPDATE SKIP LOCKED`) avoids read-modify-write races.
- When a write must touch two tables atomically and the runtime has no transaction manager wired for raw `DatabaseClient` SQL, a data-modifying CTE is the escape hatch - Postgres runs each CTE branch exactly once, inside one statement's implicit transaction, even when nothing references the CTE's output.
- Map raw rows manually (`DownloadTaskRepository.toTask`) when the shape doesn't fit a Spring Data entity; use `entityTemplate.getConverter().read(Type.class, row, meta)` when it does (`DownloadService.claimPendingDownloads`, a pre-existing method kept for its own test coverage but not called from the pass loop).
- Bind enums as `enum.name()` when writing raw SQL against a `TEXT` column.
- A partial index (`WHERE <predicate>`) is the way to keep a hot query fast on a table that also retains unbounded historical rows - only index what the hot query actually needs to see.

## Related docs

- How these operations are driven: [download-manager.md](download-manager.md)
- ADR: [durable-download-state-machine](../decisions/durable-download-state-machine-13-08-2026.md) (this table, the Flyway decision, retention); [minimal-postgres-downloads](../decisions/minimal-postgres-downloads-26-06-2026.md) (the original `downloads` table)
- Schema/startup caveats: [gotchas.md](gotchas.md)
