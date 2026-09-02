-- Finishes what V5 could not. V5 added `download_tasks.song_id` and backfilled it for every row that
-- existed at the time, but deliberately left it nullable: `DownloadTaskRepository.ADMIT_SQL` -- the
-- only INSERT into this table -- did not populate it yet, and no column DEFAULT can look a value up
-- in another table, so a NOT NULL then would have failed every download admitted afterwards. (Task 1
-- confirmed that by running the suite with the constraint in place: every test that calls
-- admitNewDownloads failed with exactly that violation.)
--
-- ADMIT_SQL now joins `songs` and writes `song_id` on every row it inserts, so the constraint can
-- finally hold. It is worth holding rather than leaving to convention: `CLAIM_DUE_SQL` reaches the
-- song name through an INNER join on this column, so a row with a null `song_id` would not error --
-- it would silently never be claimed, and that download would stall with no failure anywhere to
-- explain it. NOT NULL turns that into a write-time rejection, which is the only kind anyone notices.
--
-- Its own migration rather than an edit to V5, because V5 was reviewed and committed before this
-- work started, and a migration is immutable from the moment it is committed -- not merely from the
-- moment it ships. Flyway records a checksum per file; editing V5 in place would make it complain on
-- every install that had already run it.
--
-- Not safe to assume "V5 and V6 ship together, so nothing can slip between them" -- that held for a
-- brand-new install, but not for one that ran an intermediate build: V5 shipped in an earlier task
-- while ADMIT_SQL still did not populate song_id (see V5's own comment on that), so any install that
-- admitted a download in that window has `download_tasks` rows with `song_id IS NULL` on disk right
-- now, before this migration ever runs. Without a backfill here, SET NOT NULL below would hit that
-- row and abort Flyway at startup -- the application would not boot, and no test could have caught
-- it, because every IT starts from a fresh, empty Testcontainer and runs V5 then V6 with no
-- application code in between to leave such a row behind. So repeat V5's own backfill here rather
-- than delegate the repair to a human running SQL by hand: it is idempotent (WHERE song_id IS NULL
-- means rows V5 already backfilled, or admitted after ADMIT_SQL was fixed, are untouched) and uses
-- the same download_id join as V5, since every download_tasks row's download still has exactly one
-- songs row to join to.
UPDATE download_tasks t
   SET song_id = s.song_id
  FROM songs s
 WHERE s.download_id = t.download_id
   AND t.song_id IS NULL;

ALTER TABLE download_tasks ALTER COLUMN song_id SET NOT NULL;

-- V5's placeholder said "tighten to NOT NULL once ADMIT_SQL populates it". It does; this is that.
COMMENT ON COLUMN download_tasks.song_id IS
    'The song this task is downloading. NOT NULL as of V6: CLAIM_DUE_SQL inner joins songs on it, so a null would make the row silently unclaimable rather than fail loudly.';
