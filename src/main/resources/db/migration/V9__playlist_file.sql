-- Playlist file: when a download's collection-level work (the playlist .m3u8) was written. Nullable,
-- written by the finalise step of the download loop once every song of the download has either been
-- filed into the library or given up on, and null forever on installs that leave the organiser off.
--
-- A column rather than a new table, for the same reason as download_tasks.library_path (V8): one fact
-- about a row that already exists, written once per download by the loop that owns those rows.
ALTER TABLE downloads
    ADD COLUMN organised_at TIMESTAMPTZ;
