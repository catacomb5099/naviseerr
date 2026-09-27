-- Library organiser: where a finished song's file ended up, and when a download's folder was
-- finalised. Both nullable, both written by a step in the download loop after the pipeline is done,
-- both null forever on installs that leave the organiser off (library.root unset).
--
-- Two columns rather than a new table: each is one fact about a row that already exists, read and
-- written by the same loop that owns those rows, and neither adds churn -- one write per song, one per
-- download, after everything else has settled. A `library_files` table would be a join for one column.

-- Absolute path of the file inside library.root once it has been moved there. Null until then, and
-- null for good if the organiser could not find slskd's copy within its grace window.
ALTER TABLE download_tasks
    ADD COLUMN library_path TEXT;

-- When the download's collection-level work (the playlist file) was written. Set once every song of
-- the download has either been filed or given up on.
ALTER TABLE downloads
    ADD COLUMN organised_at TIMESTAMPTZ;
