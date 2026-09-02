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
-- Safe on an existing install: V5's backfill covered every row that existed then, and V5 and V6 ship
-- in the same release, so Flyway applies them back to back at one startup with no application code
-- running in between to insert an un-backfilled row. If this ever does fail with a not-null
-- violation, the cause is `download_tasks` rows created by the pre-Task-3 ADMIT_SQL while V5 was
-- applied but V6 was not -- i.e. an install that ran an intermediate build -- and the repair is to
-- populate them from `songs` by `download_id` (the same UPDATE ... FROM V5 uses) before retrying.
ALTER TABLE download_tasks ALTER COLUMN song_id SET NOT NULL;

-- V5's placeholder said "tighten to NOT NULL once ADMIT_SQL populates it". It does; this is that.
COMMENT ON COLUMN download_tasks.song_id IS
    'The song this task is downloading. NOT NULL as of V6: CLAIM_DUE_SQL inner joins songs on it, so a null would make the row silently unclaimable rather than fail loudly.';
