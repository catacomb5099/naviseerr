-- A fourth kind of request: CURATED, one edition of a suggested playlist from the playlist curator. Its
-- youtube_id is the curator's category key (e.g. 80s-indie-pop) rather than a YouTube id, and its track
-- list is fetched from the curator at admission the way an album's is fetched from ytmusic-adapter.
-- Widening a CHECK is the same operation V5 did for PARTIAL_SUCCESS; the constraint name is the one
-- Postgres gave the inline CHECK in V5.
ALTER TABLE downloads
    DROP CONSTRAINT downloads_download_type_check;
ALTER TABLE downloads
    ADD CONSTRAINT downloads_download_type_check
        CHECK (download_type IN ('SONG', 'ALBUM', 'PLAYLIST', 'CURATED'));
