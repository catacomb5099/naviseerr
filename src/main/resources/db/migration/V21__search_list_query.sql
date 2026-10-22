-- What each remembered list was searched with (persistent manual search, 20-10-2026): the words and slskd's id of
-- the search that found the files, written in the same statement as the list. The manual pop-up names these instead
-- of the loop's latest wording, which may have found nothing and left an earlier wording's list in place. Null on
-- rows from before; the pop-up then falls back to the derived wording and the row's search id.
ALTER TABLE download_tasks
    ADD COLUMN search_results_query     TEXT,
    ADD COLUMN search_results_search_id TEXT;

ALTER TABLE album_searches
    ADD COLUMN folders_query     TEXT,
    ADD COLUMN folders_search_id TEXT;
