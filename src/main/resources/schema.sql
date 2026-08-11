CREATE TABLE IF NOT EXISTS downloads (
    download_id UUID PRIMARY KEY,
    song_name   TEXT NOT NULL,
    status      TEXT NOT NULL
                CHECK (status IN ('PENDING', 'IN_PROGRESS', 'FAILED', 'SUCCEEDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_downloads_status_created_at ON downloads (status, created_at);
