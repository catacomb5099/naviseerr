# Song metadata table

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## What this changes

Today the client sends `POST /download/Riptide%20-%20Vance%20Joy`. It glues the title and artist together with a hyphen because that is the shape the server's matcher expects, and the server then splits the string back apart on the hyphen to guess which half was the artist. The UI knows a provider convention it should never have heard of, and the server works from a guess.

After this change the client sends what it actually knows: song name, artist list, image URL. The server decides how to word a Soulseek query, and every download read endpoint returns that metadata back.

The metadata goes in a new `songs` table rather than onto `downloads`.

## The three tables

| Table | Owns | Written |
|---|---|---|
| `downloads` | status and timestamps | twice: request, finish |
| `songs` | name, artists, image URL | once, at request |
| `download_tasks` | state machine position | every few seconds |

Right now both of the other tables hold a `song_name`. That is the problem being fixed:

- `downloads.song_name` is a column a playlist download would have nothing to put in. Giving `downloads` one job means it can grow collection columns later without dragging per-song fields along.
- `download_tasks.song_name` makes the state machine table a metadata carrier. It gets a `song_id` foreign key instead and joins for the two fields it genuinely needs, name and artists, to word the Soulseek query.

Both columns go. `downloads` shrinks to `download_id`, `status`, `created_at`.

## What gets added

```sql
CREATE TABLE songs (
    song_id     UUID   PRIMARY KEY,
    download_id UUID   NOT NULL REFERENCES downloads (download_id),
    name        TEXT   NOT NULL,
    artists     TEXT[] NOT NULL DEFAULT '{}',
    image_url   TEXT
);

ALTER TABLE download_tasks ADD COLUMN song_id UUID REFERENCES songs (song_id);
```

Plus a new route, `POST /download`, taking `{songName, artists, imageUrl}`, and two new fields on the download feed.

## Decisions and why

**The foreign key goes on `download_tasks`, not on `songs`.** Your sketch had `songs` pointing at both the download and the task. Three problems with that:

1. There is no separate task id. `download_tasks.download_id` is both its primary key and its foreign key to `downloads`, so a `songs.download_task_id` column would store a copy of `songs.download_id`.
2. Direction should follow creation order. Song rows are written at request time; task rows are created later by the loop's `ADMIT_SQL`. If `songs` held the pointer, admit would have to `UPDATE songs`, putting the loop back in the business of writing the metadata table.
3. It survives collections. One download to N songs to N tasks makes `download_tasks.song_id` the natural unique key, while `download_tasks.download_id` stays for the capacity counting that already reads it.

**Artists is a `TEXT[]`, not JSON in a `TEXT` column.** `download_tasks.candidates` is JSON because it is written once, read whole, and never queried by content. Artists are read on a path a UI polls every few seconds, so skipping a Jackson round trip per row is worth more than schema flexibility. Postgres 16 (pinned in `compose.yaml`) also gives us `gen_random_uuid()` with no extension.

**V5 expands, V6 contracts.** V5 creates `songs`, backfills it, adds `song_id`, and drops the `NOT NULL` on both `song_name` columns. It does not drop the columns. For self-hosted software a rollback means pulling the previous image, which would then query a column that no longer exists. V6 drops them next release, alongside the deprecated route.

**`POST /download/{songName}` survives one release, deprecated.** Same reason: a self-hoster who pulls a new server image with an old client image has to keep working. The path form creates a song row with the raw string as its name, empty artists, no image.

**The image field is `imageUrl` on the wire.** New contract, so there is no legacy casing to mirror, and it matches every other field on `ActiveDownloadView`. The client maps `track.iconURL` to `imageUrl` at its one call site.

**No `position` or `provider_track_id` column.** Both are tempting for collections and both are left out. A nullable column with no backfill is the cheapest migration Flyway can run, and guessing playlist ordering semantics before the collection feature exists is worse than deciding it with the feature.

## Tasks

Order matters between tasks 2 and 3 in a way their titles do not show. Task 3's `ADMIT_SQL` requires a song row for every download, so task 2 has to be producing them first.

Run `./gradlew cleanTest test` after each. A bare `./gradlew test` reports `UP-TO-DATE` and runs nothing, which reads as a pass.

### Task 1: Migration

- [ ] Write `V5__song_metadata.sql`: create `songs`, index it on `download_id`, backfill one song per existing download from `downloads.song_name`, add `download_tasks.song_id`, populate it from the join, set it `NOT NULL`, then drop the `NOT NULL` on both `song_name` columns.
- [ ] The `SET NOT NULL` is safe because every task row references a download and every download just got exactly one song.
- [ ] Comment the file in the style of V2 to V4, and note that this is the first migration to run a real backfill, one of the four reasons AGENTS.md gives for retiring `schema.sql`.
- [ ] Add `SongMetadataMigrationIT`: populate a pre-V5 database, migrate, assert one song per download with the name carried over and `song_id` non-null. Without this a backfill that matches zero rows shows up as blank song names in production instead of a test failure.

No Java changes. Every existing test passes untouched.

### Task 2: Write path

- [ ] Add `DownloadRequest` (`songName`, `artists`, `imageUrl`) and the `Song` R2DBC entity. Declare `artists` as `List<String>` and let the test below decide whether `PostgresDialect` handles it or whether it has to be `String[]`.
- [ ] Replace the insert in `DownloadService.requestDownload` with one data-modifying CTE that creates both rows:

  ```sql
  WITH created AS (
      INSERT INTO downloads (download_id, song_name, status, created_at)
      VALUES (:downloadId, :songName, 'PENDING', :now)
      RETURNING download_id
  )
  INSERT INTO songs (song_id, download_id, name, artists, image_url)
  SELECT :songId, download_id, :songName, :artists, :imageUrl FROM created
  ```

  One statement, so no transaction manager and no way to get a download without its song. This matches `ADMIT_SQL` and `FINISH_DOWNLOAD_SQL`, both written as CTEs so this codebase needs no `TransactionalOperator`.
- [ ] `downloads.song_name` is still written here, on purpose, only until task 3. The un-migrated `ADMIT_SQL` still copies it. Comment it, naming task 3, so it is not mistaken for the duplication this plan removes.
- [ ] Add `POST /download` with `@RequestBody DownloadRequest`. Reject blank or null `songName` with 400. Normalise null `artists` to empty rather than 400, since a client that does not know the artist is a legitimate caller and is exactly the case task 5's fallback covers.
- [ ] Mark `POST /download/{songName}` deprecated, delegating with empty artists and no image. Javadoc why it survives and when it goes.
- [ ] Tests: the body route produces one download row and one song row with artists and image intact (this settles `List<String>` vs `String[]`); a song insert that violates a constraint leaves no download row; 400 on blank and null `songName`; null artists becomes empty; the deprecated route still returns 202.

A song title containing `/` is currently unrequestable because it breaks the path variable however it is encoded. A body fixes that.

### Task 3: Loop stops carrying metadata

- [ ] Add `schema/request/TrackQuery.java`, a record of song name plus artists with null artists normalised to empty. It goes in `schema.request` rather than `download` because `services.slskd` has to consume it and `download` already depends on `services.slskd`, so putting it in `download` would make that dependency circular at package level.
- [ ] Swap `DownloadTask`'s `String songName` component for `TrackQuery query`, and add a non-component `songName()` accessor delegating to it so existing call sites keep compiling. Arity does not change. Update `initial`, `withPhase`, `dueAt`, `withProgress`, `withProgressReset`, the 13-arg legacy constructor, the three explicit `new DownloadTask(...)` calls in `DownloadStateMachine` (lines 56, 78, 90), and `DownloadTaskFixtures`.
- [ ] `ADMIT_SQL` joins `songs` and inserts `song_id` instead of `song_name`. Use `FOR UPDATE OF d SKIP LOCKED`, not a bare `FOR UPDATE`: a bare lock clause over a join takes row locks in every table in the `FROM`, so it would start locking `songs` rows that today's statement does not touch.
- [ ] `CLAIM_DUE_SQL` joins `songs` and returns name and artists. `UPDATE ... RETURNING` can only return columns of the updated table, so the join comes in through `FROM`:

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
  RETURNING t.download_id, s.name, s.artists, t.phase, t.phase_entered_at, ...
  ```

  This reverses V2's decision to denormalise `song_name` so the due-work query needed no join. The reversal is deliberate, the reason is the table split above, and the cost is a primary key lookup into `songs` for at most `batch-size` rows per pass. Say that in the comment and the ADR, so a future reader who finds a join where V2 promised none knows it was chosen.
- [ ] `SAVE_SQL` gains nothing. The loop never rewrites metadata, and adding a column to a statement that runs every few seconds is churn for no benefit.
- [ ] `toTask` builds the `TrackQuery` from `name` and `artists` (`row.get("artists", String[].class)`, null-guarded to `List.of()`) and stops reading `song_name`.
- [ ] Drop `song_name` from the insert CTE in task 2. Both columns are now written by nothing and read by nothing until V6.
- [ ] Tests: admit sets `song_id`; claim returns name and artists off the join; empty artists come back as `List.of()`, not null; and the claim still skips already-leased rows. That last one is the regression the `UPDATE ... FROM` rewrite could plausibly introduce, so assert it rather than trusting existing coverage. `DownloadRecoveryIT`'s raw inserts need song rows, and one case should keep a backfilled task row to prove those still resume.

### Task 4: Read path

- [ ] Join `songs` into `ActiveDownloadRepository.PROJECTION` and select `s.name AS song_name, s.artists, s.image_url`. All three read endpoints inherit it from the shared constant, plus the second arm of the `UNION ALL`.
- [ ] Inner join to `songs`, LEFT JOIN to `download_tasks`. The asymmetry is deliberate. The existing comment warns that an inner join is what made every PENDING download invisible, but that was about `download_tasks`, which the loop creates asynchronously, so its absence is a real state. A song row is created by the same statement as its download, so a download without a song cannot exist. That is what task 2's atomicity test protects. Note the dependency in the comment.
- [ ] Add `List<String> artists` and `String imageUrl` to `ActiveDownloadView`, right after `songName`. Document that artists is empty and never null, and that `imageUrl` is null for downloads made through the deprecated route or before V5.
- [ ] Map both in `toView`: `String[]` out of the row, null-guarded to `List.of()`; `image_url` straight through as nullable.
- [ ] Note in the SQL comments that these queries now return one row per song rather than per download. Identical today at 1:1. For collections it is probably what the client wants anyway, but `ALL_DOWNLOADS_SQL`'s `COUNT(*) OVER ()` will then count songs and its `totalPages` will mean something different. Flag it, do not solve it.
- [ ] Tests: artists and image round-trip through the live branch, the terminal branch, `findByIds`, and `findAll`; a backfilled row reports its name with empty artists and null image. `DownloadControllerTest`'s `view()` fixture gains both fields.

On payload size: `/downloads/active` is polled every few seconds and AGENTS.md asks for compact payloads. The live set is bounded by `max-concurrent-downloads` plus whatever finished inside `terminal-retention-ms`, so single digits in practice. A couple of hundred extra bytes per row is not worth designing around. One sentence in the ADR so nobody re-opens it.

### Task 5: Stop splitting strings

The only behaviour change. Last and self-contained, so it can be reverted alone if hit rate moves the wrong way.

- [ ] Add `services/slskd/SlskdQueryBuilder` with `String build(TrackQuery)`. One home for the question of how a track is worded for Soulseek, which currently has none. Format: primary artist, then song name, space joined, no punctuation, so `"Vance Joy Riptide"`. Soulseek matches tokens against file paths, so today's hyphen is a token that matches nothing. Joining all artists narrows too hard, because a four-way collab is rarely filed under all four names, so make primary-only versus all a `@Value` defaulting to primary.
- [ ] Call it from `SEARCH_INIT` in `DownloadStepExecutor` in place of the bare `task.songName()`.
- [ ] Change `TrackMatchingService.isMatch` to take a `TrackQuery` and a file path. Keep both fuzzy scores and both thresholds; the scoring is not what is broken. Build the fuzzy composite from the same normalised `"artist song"` string the query builder produces, so scoring and searching agree on the wording. Replace `containsBothParts` with: the normalised filename contains the normalised song name and the normalised name of at least one artist. Any artist, not all, for the collab reason above.
- [ ] When artists is empty, fall through to today's `extractParts` split. That is what keeps the deprecated route and backfilled rows working, and the only reason `extractParts` stays. Rewrite its `TODO` into a comment saying it is now the degraded path.
- [ ] Change `SlskdSearchResultProcessor.selectBestFiles` to take a `TrackQuery`, and update its log line, which currently prints `query='...'`. It should print song name and artists separately, since telling those apart in a log is the diagnostic this change makes possible.
- [ ] Write `TrackMatchingServiceTest` before touching the class. It has no test today and is about to change behaviour, so characterise what currently passes first. Cases: a hyphen inside the song title (today's bug), no hyphen at all, artist after the title in the filename, a collab where the filename names one of three artists, a near miss that must still be rejected, and empty artists falling back to the split.
- [ ] Write `SlskdQueryBuilderTest`: punctuation stripped, primary artist only by default, empty artists degrading to the song name alone.

### Task 6: Documentation

- [ ] ADR `docs/decisions/song-metadata-table-31-08-2026.md`, covering every decision above plus the read-path cardinality change.
- [ ] `AGENTS.md`: the endpoint list, the table descriptions under Current Implementation State (which describe two tables and a `downloads` that owns `song_name`), the `ActiveDownloadView` shape, and Domain Model Direction, where `Song` moves from expected to existing.
- [ ] `docs/architecture/persistence.md`: V5, the third table, the array-column read in `toView`.
- [ ] `docs/architecture/download-manager.md`: the rewritten `ADMIT_SQL` and `CLAIM_DUE_SQL`, including the `FOR UPDATE OF d` note.
- [ ] `docs/architecture/slskd-integration.md`: the query builder seam and the new matching rule. Delete the description of the hyphen heuristic.
- [ ] `docs/architecture/codebase-map.md`: `Song`, `TrackQuery`, `DownloadRequest`, `SlskdQueryBuilder`.
- [ ] `docs/architecture/gotchas.md`: dated entries for the deprecated route and the two dead `song_name` columns awaiting V6. A dated list entry gets found later; a code comment does not.

## Next release (V6)

```sql
ALTER TABLE downloads      DROP COLUMN song_name;
ALTER TABLE download_tasks DROP COLUMN song_name;
```

Plus deleting the deprecated `POST /download/{songName}` mapping and the `extractParts` fallback.

## Risks

| Risk | Mitigation |
|---|---|
| The `CLAIM_DUE_SQL` rewrite breaks lease semantics | It is the hot path, and `UPDATE ... FROM ... RETURNING` is a shape used nowhere else here. Task 3 asserts the skip-already-leased case directly. |
| The V5 backfill matches zero rows | `SongMetadataMigrationIT` runs the migration against a populated pre-V5 database. |
| Match hit rate gets worse | Task 5 is last and revertible alone. Thresholds and both fuzzy scores are untouched; only the source of artist and title changes. Its test is written against current behaviour first. |
| `List<String>` does not map over `TEXT[]` | Task 2's first test finds this at the first step rather than the last. `String[]` is the fallback, and the hand-mapped read path never depends on the converter either way. |
| Rollback to the previous image after V5 | What expanding first buys. Both `song_name` columns still hold correct values for every row created before task 3. Downloads created after it come back with a blank name but working status and progress. |
| `ActiveDownloadView`'s component order changes | It is a positional record, so every construction site becomes a compile error rather than a silent mis-mapping. Expect churn in `DownloadControllerTest` and `ActiveDownloadRepositoryIT`. |

## Not in this plan

`DownloadService.claimPendingDownloads` and `markStatusIfInProgress` have no production callers, only tests (verified by grep across `src/main`). `claimPendingDownloads` selects `song_name`, so without this note it would appear in the diff as work protecting nothing. Leave both alone; removal is tracked separately.

The client changes live in `naviseerr-client`, which AGENTS.md puts off limits from this repo without an explicit ask:

- `src/components/SongCard.tsx:19` stops building `` `${track.name} - ${artistNames[0]}` `` and sends `{songName: track.name, artists: artistNames, imageUrl: track.iconURL}`.
- `src/api/endpoints.ts:80` posts a body instead of an encoded path segment.
- `src/lib/downloadLibrary.ts` keeps a localStorage copy of artwork and artists only because the feed cannot supply them. After task 4 it can shrink to the stage snapshot it also holds.
