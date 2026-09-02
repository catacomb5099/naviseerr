# Decisions: song-metadata-table

- **Date:** 31-08-2026
- **Topic:** song-metadata-table
- **Implementation plan:** `docs/superpowers/plans/2026-08-31-download-request-metadata.md`

---

## Decision: A new `songs` table, not more columns on `downloads` or `download_tasks`

**Context:** Before this plan, `downloads.song_name` and `download_tasks.song_name` both held the
same fact, and both tables had to carry it because the client only ever sent one glued-together
string (`"Riptide - Vance Joy"`) that the server split apart on a hyphen to guess the artist.

**Options Considered:**
1. *Keep `song_name` on both tables, add `artists`/`image_url` next to it* - smallest diff, but bakes
   "one song" into the two tables that a future collection download must not assume.
2. *A new `songs` table, one row per song today, joined by both* - the metadata that describes what
   is being downloaded moves to its own place, separate from the two tables that describe the
   lifecycle of the request (`downloads`) and the working state of one song's pipeline
   (`download_tasks`).

**Final Choice:** Option 2.

**Rationale:** `downloads.song_name` is a column a playlist download has nothing correct to put in -
a collection has many songs, not one - so leaving it there means the day collections land, either
`downloads` grows a second, playlist-shaped metadata path alongside the one this column already
occupies, or every existing single-song download has to be migrated off it under pressure.
`download_tasks.song_name` has the sharper problem: it turns the state-machine table, whose entire
reason to exist is to be small, hot, and about *pipeline position*, into a metadata carrier as well.
Giving each table exactly one job - `downloads` owns status and timestamps, `songs` owns name/artists/
image, `download_tasks` owns state-machine position - means a future collection feature can add
collection-shaped columns to `downloads` without dragging per-song fields along, and can let one
download reference many `songs` rows without touching the lifecycle table at all.

---

## Decision: The foreign key sits on `download_tasks`, not on `songs`

**Context:** A song, a download, and a task are three rows describing one request. Something has to
point at something.

**Options Considered:**
1. *`songs` points at both `downloads` and `download_tasks`* - the naive shape, mirroring "a song
   belongs to a download and has a task."
2. *`download_tasks` points at `songs` (`download_tasks.song_id REFERENCES songs`)* - the task looks
   up its own metadata rather than the metadata pointing down at its consumer.

**Final Choice:** Option 2.

**Rationale:** Three reasons, in order of how quickly each one closes off option 1:

- There is no separate task id to point at. `download_tasks.download_id` is both its primary key and
  its foreign key to `downloads` (a decision made back when the state-machine table was designed for
  exactly one task per download). A `songs.download_task_id` column would therefore just store a
  second copy of `songs.download_id`.
- Direction should follow creation order. A song's row is written at request time, atomically with
  its download, by `DownloadService.requestDownload`. A task row is created later, asynchronously, by
  the loop's admit step (`DownloadTaskRepository.ADMIT_SQL`). If `songs` held the pointer to the task,
  admitting a download would have to `UPDATE songs` to fill it in - putting the pipeline loop back in
  the business of writing the metadata table it was just given its own table to get away from.
- It is the shape that survives collections. Once one download can have many songs, and each song has
  its own task, `download_tasks.song_id` is the natural unique key for "which song is this task
  working on" - while `download_tasks.download_id` stays exactly as useful as it is today for the
  capacity counts that already read it (`countActiveDownloads`, `countActiveTransfers`).

---

## Decision: `artists` is `TEXT[]`, not JSON in a `TEXT` column

**Context:** `download_tasks.candidates` already stores a list (of `DownloadCandidate`) as JSON in a
`TEXT` column, so there was a precedent to follow or deliberately not follow.

**Options Considered:**
1. *JSON in `TEXT`, matching `candidates`* - one fewer pattern in the codebase to remember, and a new
   field on the list later needs no migration.
2. *`TEXT[]`, a native Postgres array* - no serialisation step on either side.

**Final Choice:** Option 2.

**Rationale:** The two lists are read on completely different paths. `candidates` is written once per
search and read whole, never inspected by content until then - the JSON round trip happens a handful
of times per download. `artists` is read by `CLAIM_DUE_SQL` every time a task is claimed (every few
seconds per in-flight download, for as long as it is in flight) and by the download feed every time a
client polls it (also every few seconds). Paying a Jackson serialise/deserialise on every one of those
reads buys nothing, since nothing about `artists` needs JSON's flexibility - it is a flat list of
strings, never nested, never nullable at the element level. Postgres 16, already pinned in
`compose.yaml`, supports `TEXT[]` with no extension, and Spring Data R2DBC's Postgres dialect maps it
to `List<String>` directly (`Song.artists`; confirmed by the entity test rather than assumed - see
`SongEntityIT`), so there is no hand-written codec either.

---

## Decision: Reverse V2's `song_name` denormalisation; accept a join in the hot claim query

**Context:** `V2__download_tasks.sql`'s own reasoning, recorded in
`docs/decisions/durable-download-state-machine-13-08-2026.md`, was that `song_name` belonged on
`download_tasks` directly *so the hot due-work query needed no join*. This plan puts that fact back
behind a join.

**Options Considered:**
1. *Keep `song_name` on `download_tasks`, add the new fields elsewhere* - preserves V2's no-join
   property, but is the option the first decision above already rejected: it keeps the state-machine
   table as a metadata carrier.
2. *Move all song metadata to `songs`; `CLAIM_DUE_SQL` joins it for the two fields the loop actually
   needs (`name`, `artists`) to word the Soulseek query* - one job per table, at the cost of a join on
   the query that runs every few seconds.

**Final Choice:** Option 2.

**Rationale:** The table split decided above requires this reversal; it is not a separate cost freely
chosen, it is what the split costs. What it actually costs: `CLAIM_DUE_SQL` claims at most
`batch-size` (default 10) rows per pass, and the join to `songs` is keyed on `songs.song_id`, its
primary key - so per row it is a primary-key point lookup, not a scan. Said plainly, so it is not
mistaken for more rigour than it is: **this cost has not been confirmed with `EXPLAIN` against a
populated table.** `download_tasks.song_id` itself has no index (only `songs.download_id` does, via
`idx_songs_download_id`, which this query does not use) - the join direction that matters is `songs`
being looked up by *its own* primary key, so a missing index on the `download_tasks` side should not
matter, but "should not matter" is an assumption, not a measurement. Whoever revisits this with a
populated table and a real `EXPLAIN ANALYZE` should either confirm the assumption or add an index and
correct this note.

The join arrives through `FROM`, not a subquery in the `RETURNING` list, because `UPDATE ... RETURNING`
can only return columns of the table being updated - `songs.name`/`songs.artists` have to come from
somewhere else in the statement. This shape (`UPDATE ... FROM ... RETURNING`) is used nowhere else in
this codebase. It exists specifically so that `FOR UPDATE SKIP LOCKED` - which still selects from
`download_tasks` alone, inside the `IN (...)` subquery - keeps locking only task rows. A bare
`FOR UPDATE` clause wrapped around the whole joined statement would take row locks in every table in
its `FROM`, which would start locking `songs` rows this statement has never needed to lock.
`ADMIT_SQL` has the mirror-image version of the same problem and the same fix: `FOR UPDATE OF d SKIP
LOCKED`, naming the `downloads` alias explicitly so the join to `songs` it also needs (for `song_id`)
does not widen the lock past the `downloads` rows actually being admitted.

---

## Decision: Expand now, tighten within the release, contract next release

**Context:** `downloads.song_name` and `download_tasks.song_name` need to stop being the only place a
song's name lives, without breaking a self-hoster who rolls back to the previous server image after
upgrading, and without a two-migration gap where nothing has written `download_tasks.song_id` yet yet
the constraint already demands it.

**Options Considered:**
1. *One migration: add `songs`, backfill it, add `download_tasks.song_id` `NOT NULL`, drop both
   `song_name` columns* - fewer files, but a rollback to the previous image would then query a column
   Flyway just deleted, and there is no window in which the admit code could be updated to populate
   `song_id` before the constraint demanded it.
2. *Three migrations across this plan's tasks: `V5` adds `songs`, backfills it, adds
   `download_tasks.song_id` (nullable), and drops the `NOT NULL` on both `song_name` columns without
   dropping the columns; `V6` tightens `download_tasks.song_id` to `NOT NULL` once `ADMIT_SQL` is
   rewritten to populate it; a future `V7`, next release, finally drops both `song_name` columns.*

**Final Choice:** Option 2.

**Rationale:** For self-hosted software, "rollback" means pulling the previous container image against
the same database - there is no shared migration story between versions the way there would be in a
service the vendor operates. `V5` keeps both `song_name` columns exactly because the previous image's
code still reads them: every row created before this plan landed still has a correct value in both,
and a downgraded server keeps working against that history. `download_tasks.song_id` has to stay
nullable across `V5` for a narrower reason - at the point `V5` runs, `DownloadTaskRepository.ADMIT_SQL`
does not populate it yet (that rewrite is a later task in the same plan), and a same-transaction column
`DEFAULT` cannot look a value up in another table. Making it `NOT NULL` in `V5` would fail every
download admitted between that migration and the `ADMIT_SQL` rewrite. `V6` is what closes that gap,
once `ADMIT_SQL` joins `songs` and writes `song_id` on every row it inserts - and `V6` also carries a
defensive backfill `UPDATE ... WHERE song_id IS NULL` for the narrow window some installs will have hit
in between (`V5` shipped in an earlier task of the same release than `V6`), not just for brand-new
installs where the two migrations always run back to back.

Dropping the `song_name` columns is deliberately deferred past this release entirely, to `V7`. By the
time `V6` ships, nothing in this codebase reads or writes either column - but the columns still exist
so that a rollback all the way to a pre-`V5` image (not just the previous one) is not this plan's
problem to solve, and because a migration, once committed, is immutable: editing `V5` after it merged
would change its checksum and make Flyway refuse to run against every install that already applied it.

This reasoning covers *reads* cleanly but not *writes*, and that gap matters specifically for `V6`.
A downgraded server (the previous image, from before this plan) still knows how to read
`downloads.song_name`/`download_tasks.song_name` - both columns are still there, still nullable-safe
for that old code path, and still hold correct values for every row created before this plan landed.
But that old image's `ADMIT_SQL` never wrote `download_tasks.song_id` at all - it wrote `song_name`
instead, because `song_id` did not exist yet in its world. If a self-hoster rolls back to that image
*after* their database has already run `V5` and `V6` (not just `V5`), every new download admission
from that point on hits `download_tasks.song_id`'s `NOT NULL` constraint (added by `V6`) with no value
to put there, and the insert fails. Nothing about that failure is visible to the user: the `downloads`
row the old code inserted stays `PENDING` forever, and the only symptom is a repeating error log line
from the failed admit. The fix, if this happens, is to run
`ALTER TABLE download_tasks ALTER COLUMN song_id DROP NOT NULL` against the database before rolling
back - or to roll back Flyway's migration history to before `V6` - so the downgraded image's writes
succeed again. See [gotchas.md](../architecture/gotchas.md) for the same note in the format that gets
found while debugging a stuck `PENDING` download.

---

## Decision: `POST /download/{songName}` survives one release, deprecated

**Context:** The path-based route glues the song name and artist together into one string
(`"Riptide - Vance Joy"`) because that was the only shape the old matcher understood. The new
`POST /download` body route lets a client send what it actually knows.

**Options Considered:**
1. *Replace the route outright* - one endpoint, simplest server code, but breaks every client build
   that has not migrated the moment this ships.
2. *Add `POST /download` and keep the path route working, marked `@Deprecated`, delegating with empty
   artists and no image* - two endpoints for one release.

**Final Choice:** Option 2.

**Rationale:** Same self-hosting constraint as the migration decision above: a server image can be
newer than the client image it is paired with for a while, and the path route is what keeps that
combination working rather than 404ing every download request. It is not free forever - a song title
containing `/` cannot be represented as a path variable under any encoding, so some songs are simply
unrequestable through it, which is itself a reason the body route exists. It is slated for removal
alongside `V7` and the `extractParts` fallback it is the only real caller of, once the client
(`naviseerr-client`, tracked separately - see the plan's "Not in this plan" section) no longer needs
it.

---

## Decision: Soulseek query wording gets one seam; primary-artist-only is the default

**Context:** Before this plan, there was no single place that decided how a track's name and artist
became the string sent to Soulseek. `DownloadStepExecutor` passed the bare song name with no artist at
all, and separately, `TrackMatchingService` guessed at an artist by splitting that same bare string on
a hyphen - two different, uncoordinated guesses at the same underlying problem, neither of which used
real artist metadata even once it existed elsewhere in the system.

**Options Considered:**
1. *Leave query wording inline in `DownloadStepExecutor`* - no new class, but the decision has no home
   and no test of its own.
2. *A dedicated `SlskdQueryBuilder`, joining all credited artists* - one seam, but a multi-artist collab
   is rarely filed under every credited name on Soulseek, so joining all of them narrows a search too
   hard and can turn a real match into no results at all.
3. *`SlskdQueryBuilder`, primary artist only by default, with `slskd-service.query-builder.use-all-artists`
   as an escape hatch* - covers the common case correctly and lets an operator flip the toggle for a
   catalog or uploader convention that does credit everyone.

**Final Choice:** Option 3. Format: primary artist, then song name, space-joined, punctuation stripped
(`"Vance Joy Riptide"`) - Soulseek matches tokens against file paths, so the old hyphen was a token
that matched nothing in a real filename.

**Rationale:** `SlskdQueryBuilder.build(TrackQuery)` is now the one place this question is answered, and
`DownloadStepExecutor`'s `SEARCH_INIT` step calls it in place of the old bare `task.songName()`. The
matching side of the same problem - `TrackMatchingService.isMatch` - needs the identical wording to
score a candidate filename against what was actually searched for, but cannot call into
`SlskdQueryBuilder` to get it: `SlskdQueryBuilder` lives in `services.slskd`, which already depends on
`util` (home of `TrackMatchingService`, reached via `SlskdSearchResultProcessor`), so the reverse call
would make that package dependency circular. `TrackMatchingService.buildFuzzyComposite` therefore builds
its own copy of the same "primary artist + song name" composite, including its own copy of the
`use-all-artists` toggle, read from the same property key - each class's comment cross-references the
other and explains why the duplication exists, and the toggle is mirrored by hand specifically so a
change to the setting cannot make the two wordings disagree only in the non-default configuration.

---

## Decision: matching requires the song name and at least one credited artist, not all of them

**Context:** The pre-existing `containsBothParts` check (now `containsSongAndAnyArtist`) required the
filename to contain *both* a guessed artist and title substring. With real, possibly multi-valued
`artists`, "both" no longer parses as one rule.

**Options Considered:**
1. *Require every credited artist's name to appear in the filename* - strictest, but a Soulseek
   uploader who credits a track "Song (feat. B)" under the primary artist's folder alone would fail a
   check requiring featured artist B's name too, for a genuinely correct file.
2. *Require the song name and at least one credited artist* - looser on the artist side, unchanged on
   the song-name side.

**Final Choice:** Option 2.

**Rationale:** The same collab reasoning that set the query builder to primary-artist-only applies here
in reverse: a Soulseek filename rarely names every credited artist, so requiring all of them rejects
correct matches for exactly the tracks most likely to have more than one artist to begin with.
Requiring the song name plus *any* one artist still rules out an unrelated file that happens to share
only the title. This rule, like the query wording above, only applies when `TrackQuery.artists()` is
non-empty; when it is empty (the deprecated route, or a row backfilled by `V5__song_metadata.sql` with
no artist to carry over), matching falls through to the pre-existing `extractParts` hyphen-split
heuristic unchanged, now documented as a degraded fallback rather than the primary strategy it used to
be.

---

## Decision: no `position` or `provider_track_id` column, not yet

**Context:** Both are natural-looking additions once a `songs` table exists: `position` for playlist
ordering, `provider_track_id` for reconciling a song against whatever catalog it was searched from.

**Options Considered:**
1. *Add both now, nullable* - ready for collections whenever they land, cheap as a migration.
2. *Add neither now* - defer the decision to whichever task actually designs collections.

**Final Choice:** Option 2.

**Rationale:** The migration cost genuinely is cheap - a nullable column with no backfill is close to
free - but that is not what makes this expensive to add now. Neither column has a real backfill need
today (every existing song is a singleton, with no ordering and no known provider id to carry over),
and `position`'s semantics depend on a decision this plan does not make: whether a collection's
tracklist is expanded by the client (which already displayed the album and knows the order) or looked
up server-side. Guessing that shape now, before the feature that needs it exists, is worse than
deciding it with the feature - a wrong guess made now is a column that has to be reinterpreted or
migrated again later, for no benefit paid today.

---

## Decision: payload size is a non-issue here

**Context:** `artists` and `image_url` add real bytes to every row of `GET /downloads/active`, an
endpoint polled every few seconds, and `AGENTS.md` asks for compact payloads on exactly this kind of
path.

**Final Choice:** Add both fields with no compaction. Not worth designing around.

**Rationale:** The live set this endpoint returns is bounded by `download-task.max-concurrent-downloads`
plus whatever finished inside `download-task.terminal-retention-ms` - single digits in practice for a
self-hosted instance. A few hundred extra bytes per row, on a response that has single-digit rows, is
not a real cost. One sentence here so nobody re-opens this as a problem later without new evidence that
the live set has grown past "single digits."

---

## Note: the read path now returns one row per song, not per download

`ActiveDownloadRepository`'s shared `PROJECTION`, and therefore every one of `GET /downloads/active`,
`GET /downloads?ids=`, and `GET /downloads/all`, now joins `songs` with a plain `JOIN` (not `LEFT
JOIN`) alongside the existing, unchanged `LEFT JOIN`/`JOIN download_tasks`. The inner join is safe
today specifically because a `songs` row is created by the same atomic statement as its `downloads`
row (`DownloadService.requestDownload`'s single `INSERT ... SELECT ... FROM created` CTE) - a download
without a song cannot exist, unlike a download without a task row yet, which is the real, asynchronous
gap the `LEFT JOIN` to `download_tasks` exists to paper over. The two joins in the same query look
similar and are not: do not "fix" the `songs` join to match the `download_tasks` one by analogy: there
is no missing-row window here to compensate for, and turning it into a `LEFT JOIN` would only let a
genuine data-integrity bug (a download somehow missing its song) pass silently as a null-metadata row
instead of surfacing as the bug it is.

The consequence worth flagging now, before it is needed: every query above returns one row per `songs`
row, not per `downloads` row. Today those are identical, because a download has exactly one song - the
change is invisible. It stops being invisible the day a download can have more than one song
(collections). At that point, `ActiveDownloadRepository.ALL_DOWNLOADS_SQL`'s `COUNT(*) OVER ()` - and
the `totalPages` the controller derives from it - will count songs, not downloads, which is probably
not what a paginated "your downloads" list should mean to a client. This is flagged here, not solved:
whoever builds collections needs to either change what that endpoint counts or accept that its
pagination becomes per-song.

---

## Related

- `docs/decisions/durable-download-state-machine-13-08-2026.md` - the ADR whose `song_name`
  denormalisation this plan reverses (see its "New `download_tasks` table" decision); its line about
  needing no join has been updated to point back here.
- `docs/decisions/download-progress-reporting-17-08-2026.md` - the feed shape (`ActiveDownloadView`,
  the retention window) this plan adds two fields to, unchanged otherwise.
- `docs/architecture/persistence.md`, `docs/architecture/download-manager.md`,
  `docs/architecture/slskd-integration.md` - updated in the same task as this ADR to describe the
  `songs` table, the rewritten `ADMIT_SQL`/`CLAIM_DUE_SQL`, and the query-builder/matching changes as
  they now stand.
