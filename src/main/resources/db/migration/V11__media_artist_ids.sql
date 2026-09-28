-- Which artist page each name in `artists` belongs to, so the downloads table can link a name
-- instead of printing it. Index-aligned with `artists`: artist_ids[i] is the YouTube Music channel id
-- of artists[i], '' where YouTube gave none. A second array rather than a table of (name, id) pairs
-- because the names are already an array and the client reads both on a path it polls every few
-- seconds; the ids arrive free with the same adapter answer that supplies the names. Rows written
-- before this migration stay empty, so their names render as plain text rather than as dead links.
ALTER TABLE media_items
    ADD COLUMN artist_ids TEXT[] NOT NULL DEFAULT '{}';
