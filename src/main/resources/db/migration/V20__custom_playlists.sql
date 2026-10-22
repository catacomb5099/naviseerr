-- Your own playlists. A draft (downloaded_at null) is edited freely; "Create" stamps downloaded_at and
-- requests a CUSTOM download keyed by playlist_id, after which the row is never edited again.
CREATE TABLE playlists (
    playlist_id      UUID PRIMARY KEY,
    title            TEXT        NOT NULL,
    description      TEXT        NOT NULL DEFAULT '',
    -- YoutubeSongInfo JSON in your order, one entry per video id. Read whole, like radios.songs (V13);
    -- each edit (add, reorder, remove) is one UPDATE of it.
    songs            JSONB       NOT NULL DEFAULT '[]',
    -- The picture you uploaded: a square JPEG the web app made (at most 4 MB). Null = the mosaic.
    cover            BYTEA,
    cover_updated_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    downloaded_at    TIMESTAMPTZ
);
-- A sixth kind of request: CUSTOM, one of your playlists. Its youtube_id is the playlist's UUID. Same widening as V14.
ALTER TABLE downloads
    DROP CONSTRAINT downloads_download_type_check;
ALTER TABLE downloads
    ADD CONSTRAINT downloads_download_type_check
        CHECK (download_type IN ('SONG', 'ALBUM', 'PLAYLIST', 'CURATED', 'RADIO', 'CUSTOM'));
