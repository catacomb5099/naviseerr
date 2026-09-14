-- Collection downloads: one `downloads` row is now one user REQUEST (a song, an album, or a
-- playlist), and `download_tasks` holds one row PER SONG. That makes the relationship 1:N, which is
-- the whole content of this migration -- everything else here follows from it.
--
-- Why the request no longer carries a song name: the client posts a YouTube id and a type, and the
-- server fetches the track list from ytmusic-adapter at admission time. A playlist has nothing to put
-- in a single `song_name` at request time, and it has no track list until someone asks YouTube for it.

-- What was asked for. `youtube_id` is nullable only because rows written before this migration have
-- no id to backfill -- those are all completed history, and admission never looks at them again.
-- `download_type` defaults to SONG because every pre-existing row is one.
ALTER TABLE downloads
    ADD COLUMN download_type TEXT NOT NULL DEFAULT 'SONG'
                            CHECK (download_type IN ('SONG', 'ALBUM', 'PLAYLIST')),
    ADD COLUMN youtube_id    TEXT;

-- `song_name` survives as the download's DISPLAY TITLE -- the song's title, or the album/playlist's
-- title -- which is what the client renders on the card. It is no longer written by the request (the
-- request does not know it); it is written by admission, once the metadata call returns. So it has to
-- become nullable: there is now a window, between the 202 and the first loop pass, where the server
-- genuinely does not know what the user asked for beyond an opaque id.
--
-- The per-song names live on `download_tasks.song_name`, which is what words the Soulseek query. For
-- a single-song download the two hold the same string; for a collection they do not.
ALTER TABLE downloads
    ALTER COLUMN song_name DROP NOT NULL;

-- PARTIAL_SUCCESS is only reachable for a collection: some songs found, some not. Widening a CHECK
-- constraint is one of the four things AGENTS.md gives as the reason `schema.sql` was retired in
-- favour of Flyway -- `CREATE TABLE IF NOT EXISTS` cannot express it idempotently.
ALTER TABLE downloads
    DROP CONSTRAINT downloads_status_check;
ALTER TABLE downloads
    ADD CONSTRAINT downloads_status_check
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'FAILED', 'SUCCEEDED', 'PARTIAL_SUCCESS'));

-- `download_id` was the primary key, which is exactly the 1:1 assumption being removed. Every row
-- already in the table gets a `task_id` from the DEFAULT, so this is safe on a populated database and
-- needs no backfill statement of its own. gen_random_uuid() is built in on Postgres 13+ (16 is pinned
-- in compose.yaml), so no extension.
ALTER TABLE download_tasks
    ADD COLUMN task_id    UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN youtube_id TEXT;

ALTER TABLE download_tasks
    DROP CONSTRAINT download_tasks_pkey;
ALTER TABLE download_tasks
    ADD PRIMARY KEY (task_id);

-- `download_id` lost its index when it lost the primary key, and it is now the join/group key for
-- three separate things: the feed's per-download aggregate, the statement that concludes a download
-- once all of its tasks are terminal, and admission's "does this download have task rows yet" test.
CREATE INDEX idx_download_tasks_download_id ON download_tasks (download_id);
