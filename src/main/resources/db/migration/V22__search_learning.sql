-- Search learning (20-10-2026): what each song's searches found, and whether that record was shared.
-- search_log: one entry per completed search of the song (the loop's wordings and a person's typed ones), with how
-- many files each found by grade. Kept across retries. The files each search found are still overwritten per wording
-- in search_results; this keeps the counts of the wordings that lost.
ALTER TABLE download_tasks
    ADD COLUMN search_log       JSONB       NOT NULL DEFAULT '[]',
    -- When the song's report reached the server the install sends to (search-learning.url); null = not sent.
    ADD COLUMN learning_sent_at TIMESTAMPTZ;

-- The sender's question: finished songs not sent yet.
CREATE INDEX download_tasks_learning_unsent ON download_tasks (finished_at)
    WHERE phase = 'SUCCEEDED' AND learning_sent_at IS NULL;

-- "Help improve search": null = off (the default on every install); set = on since then, so turning it on never
-- sends the songs from before.
ALTER TABLE install_settings
    ADD COLUMN search_learning_since TIMESTAMPTZ;
