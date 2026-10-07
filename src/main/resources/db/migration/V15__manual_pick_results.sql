-- Manual file choice (ask 10, 07-10-2026): everything a search found that is the song, kept so a
-- person can pick a different file later without searching again. Written once when the search
-- completes (DownloadStepExecutor), read whole, never by the loop. Rows from before this migration
-- read as empty with no timestamp, which the candidates endpoint reports as BEFORE_CACHE.
ALTER TABLE download_tasks
    ADD COLUMN search_results    JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN search_results_at TIMESTAMPTZ;

-- Every folder the album search judged, best first, with the file it matched to each song
-- (AlbumSearchStep.judge). The album picker shows these; an album pick re-points its songs at one.
ALTER TABLE album_searches
    ADD COLUMN folders    JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN folders_at TIMESTAMPTZ;
