-- A fifth kind of request: RADIO, every song of one saved radio (V13). Its youtube_id is the radio's
-- own UUID, not a YouTube id; the songs are read from the radios row at admission, never from YouTube,
-- which would answer with a different list. Same widening as V10.
ALTER TABLE downloads
    DROP CONSTRAINT downloads_download_type_check;
ALTER TABLE downloads
    ADD CONSTRAINT downloads_download_type_check
        CHECK (download_type IN ('SONG', 'ALBUM', 'PLAYLIST', 'CURATED', 'RADIO'));
