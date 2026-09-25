-- Download metadata: what a download IS (title, artists, artwork) moves out of `downloads` and into
-- its own table, keyed by the YouTube id the request already carries. `downloads` goes back to owning
-- only the request and its lifecycle; `download_tasks` keeps owning one song's pipeline position.
--
-- Why a table keyed by youtube_id rather than more columns on `downloads`: a download is a POINTER to
-- a song or a collection, not the thing itself. Two requests for the same album share one set of
-- artwork and one title, and every track of that album is a song in its own right that may later be
-- requested alone. One row per YouTube id, written when ytmusic-adapter answers, refreshed on every
-- later answer for the same id, read by everything that renders a name or a picture.
--
-- Songs and collections share the table. The id spaces do not overlap (a videoId, an MPREb_ browse
-- id, a VL/PL playlist id), and the fields differ by one nullable column each way (`duration_seconds`
-- is per song, `track_count` per collection). Two tables would be two joins in every feed query for
-- one column's worth of difference.
CREATE TABLE media_items (
    youtube_id       TEXT PRIMARY KEY,
    title            TEXT,
    -- TEXT[] rather than JSON: read on a path the client polls every few seconds, and R2DBC maps it
    -- to String[] with no parsing step. Written in bulk via jsonb_to_recordset, which turns a JSON
    -- array into a Postgres array for free -- see DownloadTaskRepository.UPSERT_MEDIA_SQL.
    artists          TEXT[]      NOT NULL DEFAULT '{}',
    image_url        TEXT,
    duration_seconds INT,
    track_count      INT,
    fetched_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The lifecycle timestamps a self-hoster asks about first: when was it asked for (created_at, already
-- there), when did the loop pick it up, when did it finish. `failure_reason` covers the one failure
-- that happens before any task row exists (ytmusic-adapter cannot resolve the id): until now that
-- failure had nowhere to record WHY, so the feed showed FAILED with no code.
ALTER TABLE downloads
    ADD COLUMN admitted_at    TIMESTAMPTZ,
    ADD COLUMN finished_at    TIMESTAMPTZ,
    ADD COLUMN failure_reason TEXT;

-- Backfill, in dependency order.
--
-- 1. Rows from before V5 have no youtube_id at all. Give them a synthetic one so they can point at a
--    media row like everything else; the prefix keeps them recognisable and no real id starts with it.
UPDATE downloads
   SET youtube_id = 'legacy-' || download_id::text
 WHERE youtube_id IS NULL;

-- 2. Every download that ever had a display title gets a media row carrying it, so dropping the column
--    below loses no history. ON CONFLICT: two downloads of the same id share one row, and the first
--    title wins -- they are the same title.
INSERT INTO media_items (youtube_id, title)
SELECT youtube_id, song_name
  FROM downloads
 WHERE song_name IS NOT NULL
ON CONFLICT (youtube_id) DO NOTHING;

-- 3. Tasks created since V5 carry their own videoId; give each a media row too, so the per-song view
--    has a title for them. Pre-V5 tasks have no id and are reached through their download's row.
INSERT INTO media_items (youtube_id, title)
SELECT DISTINCT ON (youtube_id) youtube_id, song_name
  FROM download_tasks
 WHERE youtube_id IS NOT NULL
ON CONFLICT (youtube_id) DO NOTHING;

-- 4. A finished download finished when its last song did.
UPDATE downloads d
   SET finished_at = agg.finished_at
  FROM (SELECT download_id, MAX(finished_at) AS finished_at
          FROM download_tasks
         GROUP BY download_id) agg
 WHERE d.download_id = agg.download_id
   AND d.status IN ('SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS');

-- Now every row has an id, the column can carry the constraint the model always implied.
ALTER TABLE downloads
    ALTER COLUMN youtube_id SET NOT NULL;

-- The column this migration exists to remove. Its two jobs are now split: the DISPLAY title lives in
-- media_items, and the string that words the Soulseek query lives on download_tasks.song_name.
ALTER TABLE downloads
    DROP COLUMN song_name;

-- Where a song sits in its collection, so the per-song view lists an album in track order rather
-- than in whatever order the rows happened to be admitted. Null for rows created before this.
ALTER TABLE download_tasks
    ADD COLUMN position INT;

-- Note on download_tasks.song_name, which this migration deliberately does NOT touch: it is the
-- string handed to slskd as the search, "Title - Primary Artist", the exact shape the client used to
-- send before V5 and the shape TrackMatchingService still parses. It is not a display field. The
-- per-song display title is media_items.title, reached via download_tasks.youtube_id.
