-- Which wording of a song's Soulseek query is currently being searched. A YouTube title carries
-- platform noise -- "Hello (Official Lyric Video) - Oasis" -- that never appears in a music filename,
-- so a search that completes with nothing usable is now retried with a cleaner wording, up to three
-- tiers, before it fails as NO_CANDIDATES. See SearchQueryTiers.
--
-- Why an index rather than the derived queries: every tier is a pure function of song_name, so
-- storing the strings would be storing something the code can recompute -- and changing the
-- cleaning rules would then need a data migration to fix rows already written. Storing the tier
-- means a rule change is a code change and nothing else.
--
-- DEFAULT 0 is the raw song_name, which is exactly what every existing row was searching with, so
-- rows written before this migration behave exactly as before. The CHECK is for hand edits: the
-- code only ever counts up from 0, and a negative tier would re-search tier 0 once per step back to
-- it before it could fail.
ALTER TABLE download_tasks
    ADD COLUMN search_tier INT NOT NULL DEFAULT 0 CHECK (search_tier >= 0);
