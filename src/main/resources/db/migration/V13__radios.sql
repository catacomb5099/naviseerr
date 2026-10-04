-- A radio: songs YouTube Music picked as similar to one song, album or playlist (the seed), SAVED at
-- the moment someone started it. Saved rather than asked for again because YouTube builds a radio fresh
-- on every call: the same seed two minutes apart kept 5-18 of 25 songs (measured 04-10-2026). The page a
-- person looks at and the download they then ask for must be the same list, so both read this row.
--
-- V13, not V12: the album stack (#102) brings V12__album_metadata. Flyway refuses a V12 that arrives
-- after a V13 has run, so that stack merges first, or renumbers.
--
-- The songs are one JSON document rather than a child table: they are written once, never updated,
-- and only ever read whole, in order.
CREATE TABLE radios (
    radio_id   UUID PRIMARY KEY,
    seed_id    TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    artists    TEXT[]      NOT NULL DEFAULT '{}',
    artist_ids TEXT[]      NOT NULL DEFAULT '{}',
    image_url  TEXT,
    songs      JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
