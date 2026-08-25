-- Percentage progress for one download's current transfer. Lives on download_tasks, not downloads,
-- because it is written on the same cadence as every other DOWNLOAD_POLL field and by the same
-- statement -- see docs/decisions/download-progress-reporting-17-08-2026.md. NUMERIC(5,2), 0-100,
-- with no exceptions: a succeeded row is normalised to exactly 100 (Task 3); a failed row keeps its
-- last observed value rather than being forced to either end.
ALTER TABLE download_tasks
    ADD COLUMN progress_percent NUMERIC(5, 2) NOT NULL DEFAULT 0;
