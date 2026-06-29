# Persistence (R2DBC + Postgres)

> Status: current as of 2026-06-29, branch `event-driven-download-queue`. Agent-oriented guide - the cited source files are the source of truth; verify before relying.

Naviseerr persists download state in Postgres via Spring Data R2DBC (reactive, non-blocking - no JDBC in the runtime path). There is one table today: `downloads`.

## Configuration

[application.yaml](../../src/main/resources/application.yaml):

- `spring.r2dbc.url|username|password` - env-backed with localhost defaults (`r2dbc:postgresql://localhost:5432/naviseerr`).
- `spring.sql.init.mode: always` + `schema-locations: classpath:schema.sql` - Spring runs the schema script on every startup over the R2DBC connection.

There is no Flyway/Liquibase. Rationale and trade-offs (single table, learner-friendly, `CREATE TABLE IF NOT EXISTS` for idempotency) are in [docs/decisions/minimal-postgres-downloads-26-06-2026.md](../decisions/minimal-postgres-downloads-26-06-2026.md).

## Schema

[schema.sql](../../src/main/resources/schema.sql):

```sql
CREATE TABLE IF NOT EXISTS downloads (
    download_id UUID PRIMARY KEY,
    song_name   TEXT NOT NULL,
    status      TEXT NOT NULL
                CHECK (status IN ('PENDING', 'IN_PROGRESS', 'FAILED', 'SUCCEEDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_downloads_status_created_at ON downloads (status, created_at);
```

Status is `TEXT` + `CHECK` (not a native Postgres enum) so R2DBC maps the Java enum to/from text automatically with no codec config. The index supports the claim query (`WHERE status = 'PENDING' ORDER BY created_at`).

## Entity and status

- [Download.java](../../src/main/java/com/catacomb5099/naviseerr/download/Download.java) - `@Table("downloads")`, `@Id @Column("download_id") UUID downloadId`, plus `songName`, `status` (`DownloadStatus`), `createdAt` (`Instant`). Lombok `@Data/@Builder`.
- [DownloadStatus.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadStatus.java) - `PENDING`, `IN_PROGRESS`, `FAILED`, `SUCCEEDED`.

## DownloadService operations

[DownloadService.java](../../src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java) uses `R2dbcEntityTemplate` (and its `DatabaseClient` for raw SQL):

- `requestDownload(songName)` - builds a `Download` with an app-generated UUID and `PENDING`, then `entityTemplate.insert(download)`. It uses `insert()` (not `save()`) deliberately: `save()` treats a non-null `@Id` as an `UPDATE` and would affect 0 rows. (ADR has the full rationale.)
- `claimPendingDownloads(batchSize)` - a single statement that atomically claims the oldest pending rows:

```sql
UPDATE downloads
SET status = 'IN_PROGRESS'
WHERE download_id IN (
    SELECT download_id FROM downloads
    WHERE status = 'PENDING'
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED
    LIMIT :limit
)
RETURNING download_id, song_name, status, created_at
```

  `FOR UPDATE SKIP LOCKED` means a row is never handed to two concurrent cycles/instances; `RETURNING` yields exactly the rows this call won (already `IN_PROGRESS`). Rows are mapped back via `entityTemplate.getConverter().read(Download.class, row, meta)`.

- `markStatus(downloadId, status)` - terminal write, `@Transactional`:

```sql
UPDATE downloads SET status = :status
WHERE download_id = :id AND status = 'IN_PROGRESS'
```

  Binds `status.name()` (explicit String) and returns rows updated (`Mono<Long>`; 0 if the row was no longer `IN_PROGRESS`). The `AND status = 'IN_PROGRESS'` guard keeps the write idempotent and safe against future cancellation races. Reactive `@Transactional` works via the autoconfigured `R2dbcTransactionManager` (present because of `spring-boot-starter-data-r2dbc`).

## Patterns worth reusing

- Prefer `R2dbcEntityTemplate.insert(entity)` for app-generated UUID inserts.
- For atomic claim/state transitions, a single `UPDATE ... RETURNING` (optionally `FOR UPDATE SKIP LOCKED`) avoids read-modify-write races.
- Map raw rows with `entityTemplate.getConverter().read(Type.class, row, meta)`.
- Bind enums as `enum.name()` when writing raw SQL against a `TEXT` column.

## Related docs

- How these operations are driven: [download-manager.md](download-manager.md)
- ADRs: [minimal-postgres-downloads](../decisions/minimal-postgres-downloads-26-06-2026.md), [event-driven-download-queue](../decisions/event-driven-download-queue-29-06-2026.md)
- Schema/startup caveat: [gotchas.md](gotchas.md)
