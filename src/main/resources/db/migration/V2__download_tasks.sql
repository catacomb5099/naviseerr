-- Working state for one download. Deliberately a separate table from `downloads`: it is written
-- every few seconds, whereas `downloads` is the low-churn user-facing record that history queries
-- read. `song_name` is denormalised so the hot due-work query needs no join.
--
-- Rows are KEPT FOREVER once terminal (phase SUCCEEDED/FAILED), not deleted. Self-hosters need to
-- be able to answer "which peers were tried and how did each fail?" from their own instance, and a
-- few MB of history is a trivial price. The partial index below is what keeps the due-work query
-- fast regardless of how much history accumulates: it only contains rows that are still live.
-- A retention job (delete after N months) can be added later if anyone ever asks.
CREATE TABLE download_tasks (
    download_id       UUID PRIMARY KEY REFERENCES downloads (download_id),
    song_name         TEXT        NOT NULL,
    phase             TEXT        NOT NULL
                                  CHECK (phase IN ('SEARCH_INIT', 'SEARCH_POLL',
                                                   'DOWNLOAD_INIT', 'DOWNLOAD_POLL',
                                                   'SUCCEEDED', 'FAILED')),
    phase_entered_at  TIMESTAMPTZ NOT NULL,
    next_attempt_at   TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ,
    failure_reason    TEXT,
    lease_owner       TEXT,
    lease_expires_at  TIMESTAMPTZ,
    search_id         TEXT,
    -- JSON array of DownloadCandidate. TEXT, not JSONB: written once, read whole, never queried
    -- by content, so JSONB's indexing and operators would buy nothing. Storing it as JSON also
    -- means adding a field to DownloadCandidate later needs NO migration.
    candidates        TEXT        NOT NULL DEFAULT '[]',
    candidate_index   INT         NOT NULL DEFAULT 0,
    retry_index       INT         NOT NULL DEFAULT 0,
    -- No "did we already call enqueue" flag. A crash between slskd accepting an enqueue and this row
    -- recording the transfer id can cause a duplicate download on resume — accepted by explicit
    -- decision (docs/decisions/durable-download-state-machine-13-08-2026.md) rather than guarded
    -- against, because the guard cost more (a column, an extra write before every enqueue, a
    -- dedicated recovery branch) than an occasional duplicate file is worth.
    slskd_username    TEXT,
    slskd_filename    TEXT,
    slskd_transfer_id TEXT,
    last_error        TEXT
);

-- PARTIAL index: only live work is indexed, so the due-work query cost is independent of history
-- size. This is what makes "keep terminal rows forever" free.
CREATE INDEX idx_download_tasks_due ON download_tasks (next_attempt_at)
    WHERE phase NOT IN ('SUCCEEDED', 'FAILED');
