-- Splits song metadata (name, artists, image URL) out of `downloads` and `download_tasks` into its
-- own table. `downloads.song_name` cannot express what a future collection/playlist download would
-- put there -- a collection has many songs, not one -- and `download_tasks.song_name` turns the
-- state-machine table into a metadata carrier it was never meant to be. See
-- docs/superpowers/plans/2026-08-31-download-request-metadata.md for the full plan; this migration
-- is Task 1: schema only. No Java in this repo reads or writes `songs` or `download_tasks.song_id`
-- yet, so `song_name` on both tables is kept (NOT NULL dropped, not the column) until a later task
-- migrates the code that reads it. `download_tasks.song_id` itself stays nullable for the same
-- reason -- see the comment at that ALTER TABLE below, it is not the oversight it looks like.
--
-- `artists` is TEXT[], not JSON-in-TEXT (contrast `download_tasks.candidates` in V2): this table is
-- read on the poll path that runs every few seconds, and an array column skips the JSON round-trip
-- that buys candidates nothing since candidates is written once and read whole. Postgres 16 is
-- pinned in compose.yaml, so gen_random_uuid() below needs no extension.
--
-- This is the first migration in this repo to perform a real data backfill rather than an additive
-- CREATE TABLE/ADD COLUMN. That is exactly the case `CREATE TABLE IF NOT EXISTS` /
-- `ADD COLUMN IF NOT EXISTS` cannot express idempotently -- see AGENTS.md's "Schema Management
-- Approach" -- and one of the reasons Flyway was adopted over `schema.sql` in the first place.
CREATE TABLE songs (
    song_id     UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    download_id UUID   NOT NULL REFERENCES downloads (download_id),
    name        TEXT   NOT NULL,
    artists     TEXT[] NOT NULL DEFAULT '{}',
    image_url   TEXT
);

-- Every download's song is looked up by download_id (the join below, and later the read path this
-- plan's Java tasks add), never by song_id alone.
CREATE INDEX idx_songs_download_id ON songs (download_id);

-- Backfill: one songs row per existing download, named from the column it is replacing. Every
-- `downloads` row gets exactly one song here, which is what the join into download_tasks below
-- relies on.
INSERT INTO songs (download_id, name)
SELECT download_id, song_name
FROM downloads;

-- Nullable at first so it can be populated by the join below before being tightened -- adding a
-- NOT NULL column to a non-empty table in one step would fail outright.
ALTER TABLE download_tasks ADD COLUMN song_id UUID REFERENCES songs (song_id);

-- Every `download_tasks` row that exists RIGHT NOW references a `downloads` row (its PK is a FK to
-- downloads.download_id, see V2), and the backfill above just gave that `downloads` row exactly one
-- song. The join below can therefore never leave an EXISTING `download_tasks` row unmatched.
UPDATE download_tasks
SET song_id = songs.song_id
FROM songs
WHERE songs.download_id = download_tasks.download_id;

-- Deliberately NOT `ALTER COLUMN song_id SET NOT NULL` here, despite the plan text calling that
-- safe -- it verified the backfill covers every EXISTING row, but not every FUTURE one.
-- DownloadTaskRepository.ADMIT_SQL is the only INSERT into this table, it runs unmodified until a
-- later task touches Java, and it does not populate song_id (there is no join it could use without
-- a code change). A same-transaction column default cannot look the value up either -- Postgres
-- DEFAULT expressions cannot reference another table's rows. SET NOT NULL here would make every
-- download admitted between this migration and whichever later task updates ADMIT_SQL fail outright
-- with a not-null violation, which contradicts "no Java changes in this task, existing tests stay
-- green." Confirmed by running the full suite with NOT NULL in place: every test that calls
-- admitNewDownloads failed with exactly that violation. The later task that teaches ADMIT_SQL about
-- song_id should add this constraint then, not this migration.
COMMENT ON COLUMN download_tasks.song_id IS
    'Nullable for now -- see V5__song_metadata.sql. Tighten to NOT NULL once ADMIT_SQL populates it.';

-- Expand, don't contract. The columns being replaced stay -- and stay populated by the app, since
-- no Java changes in this task -- but they are no longer the only place a song's name lives, so
-- their NOT NULL is no longer something this schema should still be able to promise on their own.
-- A future migration drops both columns once the code that reads them is gone.
ALTER TABLE downloads ALTER COLUMN song_name DROP NOT NULL;
ALTER TABLE download_tasks ALTER COLUMN song_name DROP NOT NULL;
