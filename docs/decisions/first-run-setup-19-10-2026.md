# First-run setup and the Settings page (19-10-2026)

> Status: accepted. Owner's ask 13 of 19-10-2026: "a phone-like startup screen where it runs you through, in order of
> both simplicity and most important things, one by one ... if you choose to skip it, which should always be an option,
> defaults would be set, and someone could come back and change those later through a settings page". Design in the sweep
> notes (`DECISIONS-WIZARD.md`, research `wizard-today.md`, `wizard-reference-apps.md`); this is the server side of it.

## The problem

Everything a person had to decide about their install lived in the compose file or a container log: the Soulseek
account (a made-up name and password printed by `setup`), which music app to tell about new songs (five env lines),
whether weekly suggested playlists are made (always, in the install), and the login (none until the Authentik work).
The owner's words: the compose file "is not navigable by a non-technical person".

## The decision

- **Four screens, one question each, every one skippable, then "You're all set".** Order: Soulseek account → music app
  (Navidrome / Jellyfin / Neither) → weekly suggested playlists (a switch, off) → "On the internet?" (a short plain-words
  pitch, then Continue with Authentik / Not now). Jellyfin's setup wizard is the shape (account, media, remote access
  last, "You're Done!"); Home Assistant's analytics screen for the opt-in switch; Sonarr for "Save and test". The login
  stays LAST and skippable although every reference app asks for its account first: naviseerr has no account of its own
  to create, and every reference app keeps the OUTSIDE login (OAuth, "External") in Settings. The cost is the open window
  below.
- **The first-run signal is a server column**, `install_settings.setup_completed_at` (V18), read by the web app once at
  start (`GET /settings/setup`), stamped by Finish or by skipping every screen (`POST /settings/setup/done`). Not
  `localStorage` (the owner's phone would see the setup again), not derived from the answers (asks again after skip-all).
  Nothing else is remembered about the setup: each answer lives in its own place (`/settings/soulseek` in slskd's files,
  `/settings/media-servers` and `/settings/suggested-playlists` in the same row, `/settings/login` in V16).
- **One typed row, not a key/value table** (`install_settings`, package `settings/`, the `login_settings` pattern): one
  SELECT, no serialisation; every column of the setup chain is created in V18 so the later PRs add no DDL. The Soulseek
  account is not in it: slskd reads its own files.
- **Existing installs see the setup once after upgrading.** The row did not exist before V18, so `completed` is false;
  every screen is pre-filled with the current state, so it is four Nexts. The one data statement in V18 keeps the weekly
  playlists ON where the database already holds a suggested-playlist download (`downloads.download_type = 'CURATED'`: the one
  trace of the feature naviseerr's database holds, since the playlists themselves live in croissant); fresh installs start OFF
  until the screen or Settings turns it on. Narrower than "in use": an install whose maker ran for weeks but whose owner never
  downloaded a suggested playlist starts OFF and finds the switch pre-filled on the setup's third screen (check your own
  database with `SELECT count(*) FROM downloads WHERE download_type = 'CURATED'`).
- **App over install file for the music app** (the row wins; a null column falls back to the env lines, and the API says
  `fromInstallFile: true`). Env stays the ONLY way for paths and plumbing (`LIBRARY_ROOT`, `SLSKD_DOWNLOADS_DIR`,
  `NAVISEERR_DATA_DIR`, `CURATOR_URL`/`CURATOR_TOKEN`). The reverse of the Soulseek files, where the compose file wins:
  nothing re-renders the music-app settings from env at start, so the app can own them.
- **Secrets are write-only**: the Navidrome password, the Jellyfin key and the slskd key are stored as typed (like the
  Authentik client secret) and never leave the server; GET answers `passwordSet` / `apiKeySet`, an empty field on PUT
  keeps the stored one.
- **"Save and test" is one real call** with the typed values before anything is stored: a rescan for the music app
  (proves address, login and admin in one go; an incremental scan is harmless), slskd's `GET /server` for your own slskd.
- **Every `/settings/*` route is behind the login once Authentik is on**: the filter is default-deny, nothing to list
  (pinned by a test). Until Authentik is connected and someone has signed in once, naviseerr, its first-run setup
  included, is open to anyone who can reach its address: finish the setup, the Authentik step included, before pointing
  a public address at it (the README says so).

## Your own slskd (W4)

The owner named the question ("do you have an instance of slskd?"), so the first screen gets a third card,
"Use my own slskd": address and API key, "Save and test" = that slskd's `GET /server` with the typed key (proves
address and key, and says whether Soulseek is in), then `install_settings.slskd_url` / `slskd_api_key` and
`SlskdService.use()` swap the live `WebClient` (built by `SlskdConfig.build()`: one connection pool shared by every
slskd client the process builds, same timeouts). Searches and downloads under way at the switch fail within about a
minute (the new slskd never had their ids) and can be retried. An empty key keeps the saved one for its own host only.
The address may be typed as host and port; `/api/v0/` is added. The install file's
`SLSKD_URL` / `SLSKD_API_KEY` stay the fallback (DELETE goes back to them). The hazard this cannot check and the
screen's one help line carries: an outside slskd whose downloads folder naviseerr cannot see means no filing and
no playlist files (the 30-09 root cause); the install's downloads volume must point at that folder. Sonarr keeps
"your own download client" in Settings the same way. The bundled slskd keeps running and `/settings/soulseek`
keeps describing its account files; the web app shows the own-slskd card instead when `fromInstallFile` is false.
Flip: drop W4/K5, nothing else depends on them.

## Flippable choices (defaults first)

- Upgrade path: the setup shows once (default) / V18 also stamps `setup_completed_at` where `downloads` has rows
  (one line) and the owner finds the new switches in Settings.
- Weekly playlists on upgrade: ON where a suggested-playlist download exists (default) / OFF everywhere (drop the INSERT).
- Login last and skippable (default) / first, as the reference apps do (move the step in the web app).
- Music app: the row wins over env (default) / env wins and the section shows a "set in the install file" note.
- "Run setup again" link in Settings: dropped (no reference app has one; every section is in Settings) / one link to `/setup/1`.

## Addendum, 19-10-2026 evening: a fifth screen, first

The owner asked for the download quality on both the setup and Settings ("that should be first"): a fifth screen BEFORE the
Soulseek account, the counter becomes 1 of 5, and Settings gets it as its first section. Its own record:
[download-quality-19-10-2026.md](download-quality-19-10-2026.md) (W5a stores it, W5b makes the picker honour it; client K6).

## The PRs

Server: #165 (Soulseek account, rebased onto the Authentik login #188) → W1 the setup mark and this table → W2 the music
app in the app → W3 suggested playlists opt-in → W4 your own slskd. Client: K1 the frame, the Soulseek screen and the
done screen → K2 music app → K3 playlists → K4 the internet pitch and Authentik → K5 own slskd. The client may merge
first: a 404 on `GET /settings/setup` counts as completed.
