-- Album metadata: what YouTube Music says about each song's album, kept so finished files can later be
-- tagged and filed by album, and so an album can later be fetched from one sharer's folder. Every
-- column is nullable and every table starts empty: rows written before this migration keep NULL and
-- read exactly as before.
--
-- This one migration holds the schema for the whole album work (tagging, joining songs to their
-- album, whole-album downloads), so the later changes need no migration of their own and cannot be
-- applied out of order.

-- The album or playlist ROW's own values, written at admission. Not media_items: the same video id can
-- appear twice inside one album with a different title and length on each row (the Definitely Maybe
-- 30th Anniversary edition lists h7-BHdjeEY0 as both track 21 and track 24), and media_items holds one
-- row per id. track_number is YouTube's own number (albums only; null for playlists and songs), never
-- the list order -- `position` stays list order.
ALTER TABLE download_tasks
    ADD COLUMN track_title TEXT,
    ADD COLUMN track_number INT,
    ADD COLUMN duration_seconds INT;

-- Album rows only: the release year and YouTube's kind of release ('Album', 'EP', 'Single').
ALTER TABLE media_items
    ADD COLUMN year INT,
    ADD COLUMN album_type TEXT;

-- Which YouTube Music album a song belongs to, once looked up. Unused until songs are joined to their
-- album. album_id NULL means "looked, and no album could be trusted".
CREATE TABLE song_albums (
    youtube_id   TEXT PRIMARY KEY,
    album_id     TEXT,
    track_number INT,
    resolved_at  TIMESTAMPTZ NOT NULL
);

-- One album download's search for a sharer holding the whole album: its own small state machine with
-- a lease, shaped like download_tasks. Unused until whole-album downloads land.
CREATE TABLE album_searches (
    download_id      UUID PRIMARY KEY REFERENCES downloads (download_id),
    phase            TEXT        NOT NULL CHECK (phase IN ('SEARCH_INIT', 'SEARCH_POLL', 'DONE')),
    search_tier      INT         NOT NULL DEFAULT 0,
    search_id        TEXT,
    phase_entered_at TIMESTAMPTZ NOT NULL,
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    lease_owner      TEXT,
    lease_expires_at TIMESTAMPTZ,
    outcome          TEXT,
    finished_at      TIMESTAMPTZ
);

-- Same trick as idx_download_tasks_due: only live rows are indexed, so finished history costs nothing.
CREATE INDEX idx_album_searches_due ON album_searches (next_attempt_at) WHERE phase <> 'DONE';
