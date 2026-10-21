-- "Add this song to my Navidrome/Jellyfin playlist once it is downloaded" (21-10-2026, for Feishin).
-- One row per song and playlist; mediaserver.PlaylistAdder adds it after the song is filed and the media
-- server lists it. The waiting states are worked out from the song's task on every read, never stored.
-- See docs/decisions/playlist-additions-21-10-2026.md.
CREATE TABLE playlist_additions (
    addition_id      UUID        PRIMARY KEY,
    media_server     TEXT        NOT NULL CHECK (media_server IN ('NAVIDROME', 'JELLYFIN')),
    playlist_id      TEXT        NOT NULL,          -- the media server's own playlist id
    jellyfin_user_id TEXT,                          -- the playlist owner (Jellyfin only: an API key has no user)
    download_id      UUID        NOT NULL REFERENCES downloads (download_id),
    youtube_id       TEXT        NOT NULL,          -- the song; its task is (download_id, youtube_id)
    position         INT         NOT NULL,          -- order inside one request (album order)
    requested_at     TIMESTAMPTZ NOT NULL,
    next_try_at      TIMESTAMPTZ NOT NULL,
    tries            INT         NOT NULL DEFAULT 0,
    last_error       TEXT,
    media_item_id    TEXT,                          -- the media server's song that went in
    added_at         TIMESTAMPTZ,
    gave_up_at       TIMESTAMPTZ,
    gave_up_reason   TEXT,
    UNIQUE (media_server, playlist_id, youtube_id)  -- asked twice = one row, re-armed
);

CREATE INDEX playlist_additions_due ON playlist_additions (next_try_at)
    WHERE added_at IS NULL AND gave_up_reason IS NULL;
