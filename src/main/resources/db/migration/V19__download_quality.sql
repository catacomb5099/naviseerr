-- Download quality (19-10-2026): the tier the Soulseek picker prefers for every download (songs, playlists and
-- albums alike), chosen on the setup's first screen or in Settings. null = nothing chosen = the default
-- (DownloadQuality.DEFAULT names it once: Lossless, what the picker effectively preferred until now).
-- TEXT with a CHECK, not a Postgres enum type: an enum type outlives a dropped table on a shared dev database.
ALTER TABLE install_settings
    ADD COLUMN download_quality TEXT CHECK (download_quality IN ('LOW', 'NORMAL', 'HIGH', 'VERY_HIGH', 'LOSSLESS'));
