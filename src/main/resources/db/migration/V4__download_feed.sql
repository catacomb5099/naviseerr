-- Supports the client-facing download feed: GET /downloads/active and GET /downloads?ids=...
--
-- `updated_at` is the recency sort key. `phase_entered_at` cannot serve: it deliberately does not move
-- when only progress changes, so a download that has been transferring for ten minutes would sort as
-- if nothing had happened since it started. A warm client tracks changes itself, but a cold one (fresh
-- browser, empty localStorage) has only what this endpoint tells it.
--
-- Free, on the same argument the progress_percent column rests on: every write to this table already
-- goes through an UPDATE (SAVE_SQL, FINISH_DOWNLOAD_SQL), so this is one more SET clause, zero extra
-- statements, and no index churn beyond what those updates already cause. It also leaves a `?since=`
-- cursor available later with no further migration, should polling the whole live set ever get costly.
ALTER TABLE download_tasks
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- PARTIAL index over terminal rows only, for the "recently finished" branch of the feed query.
-- Terminal rows are kept forever by explicit decision (see V2), so without this the retention-window
-- lookup degrades as history accumulates -- the exact cost idx_download_tasks_due exists to avoid on
-- the other side. The predicate matches the query's, so the index holds only what it needs to.
CREATE INDEX idx_download_tasks_recently_finished ON download_tasks (finished_at)
    WHERE phase IN ('SUCCEEDED', 'FAILED');
