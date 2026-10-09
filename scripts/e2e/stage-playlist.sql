-- What the download loop leaves behind for a partly downloaded 4-song playlist: three songs done, their
-- files already in slskd's downloads folder (playlist-file.sh copies them there first), the fourth failed
-- (playlist-file.sh retries it through the API later). The organiser then does the real work: tag, move
-- into the library, write the .m3u8. Column shapes follow the V1-V14 migrations.
BEGIN;
INSERT INTO media_items (youtube_id, title, artists, duration_seconds, track_count) VALUES
  ('e2e-playlist', 'E2E Road Trip: Été', '{}', NULL, 4),
  ('e2e-song-1', 'Alpha Song',  '{"Alpha Artist"}', 2, NULL),
  ('e2e-song-2', 'Beta Tune',   '{"Beta Band"}',    2, NULL),
  ('e2e-song-3', 'Gamma Track', '{"Gamma Group"}',  2, NULL),
  ('e2e-song-4', 'Delta Dance', '{"Delta Duo"}',    2, NULL)
ON CONFLICT (youtube_id) DO NOTHING;

INSERT INTO downloads (download_id, status, download_type, youtube_id, created_at, admitted_at, finished_at)
VALUES ('00000000-0000-4000-8000-00000000e2e1', 'PARTIAL_SUCCESS', 'PLAYLIST', 'e2e-playlist',
        now() - interval '3 minutes', now() - interval '3 minutes', now() - interval '1 minute');

INSERT INTO download_tasks (download_id, youtube_id, song_name, position, phase, phase_entered_at,
                            next_attempt_at, finished_at, slskd_username, slskd_filename, progress_percent)
SELECT '00000000-0000-4000-8000-00000000e2e1', s.youtube_id, s.song_name, s.position, 'SUCCEEDED',
       now() - interval '1 minute', now() - interval '1 minute', now() - interval '1 minute',
       'e2e-sharer', s.remote, 100
  FROM (VALUES
    ('e2e-song-1', 'Alpha Song - Alpha Artist',  1, '@@e2e\Music\E2E Folder\01 - Alpha Artist - Alpha Song.mp3'),
    ('e2e-song-2', 'Beta Tune - Beta Band',      2, '@@e2e\Music\E2E Folder\02 - Beta Band - Beta Tune.mp3'),
    ('e2e-song-3', 'Gamma Track - Gamma Group',  3, '@@e2e\Music\E2E Folder\03 - Gamma Group - Gamma Track.mp3')
  ) AS s(youtube_id, song_name, position, remote);

-- The failed one: every sharer refused, nothing filed. Retry resets exactly this row.
INSERT INTO download_tasks (download_id, youtube_id, song_name, position, phase, phase_entered_at,
                            next_attempt_at, finished_at, failure_reason, progress_percent)
VALUES ('00000000-0000-4000-8000-00000000e2e1', 'e2e-song-4', 'Delta Dance - Delta Duo', 4, 'FAILED',
        now() - interval '1 minute', now() - interval '1 minute', now() - interval '1 minute', 'SOURCES_EXHAUSTED', 0);

-- "Looked, no trusted album": the organiser files at once instead of waiting ALBUM_LOOKUP_GRACE, and the
-- SongAlbumResolver never asks YouTube for these ids (a recent answer is not asked again).
INSERT INTO song_albums (youtube_id, album_id, track_number, resolved_at) VALUES
  ('e2e-song-1', NULL, NULL, now()), ('e2e-song-2', NULL, NULL, now()), ('e2e-song-3', NULL, NULL, now()),
  ('e2e-song-4', NULL, NULL, now())
ON CONFLICT (youtube_id) DO NOTHING;
COMMIT;
