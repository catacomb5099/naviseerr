-- First-run setup (19-10-2026): what the four setup screens asked and the Settings page changes later.
-- One row at most (the login_settings one-row trick). A typed row, not a key/value table: one SELECT,
-- no serialisation. Every column of the setup chain is created here so the later PRs add no DDL.
-- The Soulseek account is NOT here: slskd reads it from its own files (settings/SoulseekAccountFiles).
CREATE TABLE install_settings (
    one                 BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (one),
    -- null: the first-run setup has not been finished (or skipped through) on this install yet, show it.
    setup_completed_at  TIMESTAMPTZ,
    -- "Make weekly playlists for me": off unless the person says yes (see the INSERT below for upgrades).
    suggested_playlists BOOLEAN     NOT NULL DEFAULT FALSE,
    -- The music app that is told to rescan. null = fall back to the install file's NAVIDROME_* / JELLYFIN_* lines.
    -- The password and the API key are stored as typed and never leave the server (the API says only "set").
    navidrome_url       TEXT,
    navidrome_user      TEXT,
    navidrome_password  TEXT,
    jellyfin_url        TEXT,
    jellyfin_api_key    TEXT,
    -- Your own slskd instead of the install's bundled one. null = the install file's SLSKD_URL / SLSKD_API_KEY.
    slskd_url           TEXT,
    slskd_api_key       TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- An install that was already making and downloading suggested playlists keeps doing so: the switch
-- starts ON where a suggested-playlist download exists, OFF everywhere else (a fresh database has none).
-- setup_completed_at stays null on purpose: the setup shows once after the upgrade, pre-filled.
INSERT INTO install_settings (suggested_playlists)
SELECT TRUE
 WHERE EXISTS (SELECT 1 FROM downloads WHERE download_type = 'CURATED');
