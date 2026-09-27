-- Library organiser: where a finished song's file ended up. Nullable, written by a step in the
-- download loop after the pipeline is done, null forever on installs that leave the organiser off
-- (library.root unset).
--
-- A column rather than a new table: it is one fact about a row that already exists, read and written
-- by the same loop that owns those rows, and adds no churn -- one write per song, after everything
-- else has settled. A `library_files` table would be a join for one column.

-- Absolute path of the file inside library.root once it has been moved there. Null until then, and
-- null for good if the organiser could not find slskd's copy within its grace window.
ALTER TABLE download_tasks
    ADD COLUMN library_path TEXT;
