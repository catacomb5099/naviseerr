package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.schema.request.TrackQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TrackMatchingService {

    private static final int MIN_TOKEN_SCORE = 75;
    private static final int MIN_PARTIAL_SCORE = 85;

    // Mirrors SlskdQueryBuilder.useAllArtists exactly -- same property key, same default. It has
    // to be duplicated here too: if an operator sets this to true, SlskdQueryBuilder starts
    // joining every credited artist into the search query, and buildFuzzyComposite below must
    // join them the same way or the fuzzy-scoring composite silently stops matching what was
    // actually searched for. See buildFuzzyComposite.
    @Value("${slskd-service.query-builder.use-all-artists}")
    private boolean useAllArtists;

    public boolean isMatch(TrackQuery query, String torrentFilePath) {
        String filename = extractFilename(torrentFilePath);
        String normalizedTorrent = normalize(filename);

        if (query.artists().isEmpty()) {
            // No artist known -- the deprecated route and every row backfilled by
            // V5__song_metadata.sql land here. This is now the degraded fallback: it survives
            // ONLY to keep those callers working, not because splitting a raw string on "-" is a
            // good way to guess an artist. See extractParts.
            return isMatchByLegacySplit(query.songName(), normalizedTorrent);
        }

        // Build the fuzzy-comparison input from the same "artist song" shape SlskdQueryBuilder
        // produces, so scoring and searching agree on the wording.
        String normalizedComposite = normalize(buildFuzzyComposite(query));

        int tokenScore = FuzzySearch.tokenSortRatio(normalizedComposite, normalizedTorrent);
        int partialScore = FuzzySearch.partialRatio(normalizedComposite, normalizedTorrent);

        // The filename must contain the song name and at least one credited artist -- not all of
        // them. A Soulseek uploader rarely credits every artist on a collab, so requiring all of
        // them would reject a correct match; requiring only one still rules out an unrelated file
        // that happens to share the title.
        boolean containsSongAndAnyArtist =
                normalizedTorrent.contains(normalize(query.songName())) &&
                        query.artists().stream().anyMatch(artist -> normalizedTorrent.contains(normalize(artist)));

        return tokenScore >= MIN_TOKEN_SCORE ||
                partialScore >= MIN_PARTIAL_SCORE ||
                containsSongAndAnyArtist;
    }

    /**
     * Today's original single-string matching logic, kept only for the empty-artists fallback
     * above. Extracted verbatim (aside from taking the already-normalized filename) from the
     * pre-Task-5 {@code isMatch(String, String)}.
     */
    private boolean isMatchByLegacySplit(String cleanTitle, String normalizedTorrent) {
        String normalizedClean = normalize(cleanTitle);
        TitleParts cleanParts = extractParts(cleanTitle);

        int tokenScore = FuzzySearch.tokenSortRatio(normalizedClean, normalizedTorrent);
        int partialScore = FuzzySearch.partialRatio(normalizedClean, normalizedTorrent);

        boolean containsBothParts = cleanParts.artist != null && cleanParts.title != null &&
                normalizedTorrent.contains(normalize(cleanParts.artist)) &&
                normalizedTorrent.contains(normalize(cleanParts.title));

        return tokenScore >= MIN_TOKEN_SCORE ||
                partialScore >= MIN_PARTIAL_SCORE ||
                containsBothParts;
    }

    /**
     * Builds "artist song", space-joined -- the same shape {@code SlskdQueryBuilder.build}
     * produces, including honoring the same {@code useAllArtists} toggle: all credited artists
     * when the toggle is true, primary artist only when false. Duplicated on purpose rather than
     * shared: this class lives in {@code util}, which {@code services.slskd} (home of
     * {@code SlskdQueryBuilder}) already depends on via {@code SlskdSearchResultProcessor}, so
     * calling from here into {@code services.slskd} would make that package dependency circular.
     * The toggle is mirrored in both places for exactly this reason -- so the two wordings stay
     * identical in either configuration, not just the default. Keep them in sync by hand -- see
     * {@code SlskdQueryBuilder.build}.
     */
    private String buildFuzzyComposite(TrackQuery query) {
        String artistPrefix = useAllArtists
                ? String.join(" ", query.artists())
                : query.artists().get(0);
        return artistPrefix + " " + query.songName();
    }

    /**
     * Extract filename from full path (handles both Unix and Windows paths)
     */
    private String extractFilename(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }

        // Handle both forward and backward slashes
        String[] parts = filePath.split("[/\\\\]");
        return parts[parts.length - 1];
    }

    /**
     * Normalize string for comparison
     */
    private String normalize(String title) {
        if (title == null) return "";

        return title.toLowerCase()
                // Remove file extensions
                .replaceAll("\\.(flac|mp3|m4a|aif|wav|ogg|aac|wma)$", "")
                // Remove track numbers (01, 02, etc. at start or with separators)
                .replaceAll("^\\d{1,3}[.\\s-]+", "")
                .replaceAll("[_\\s-]\\d{1,3}[_\\s-]", " ")
                // Remove brackets and their contents
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("\\{.*?\\}", "")
                // Remove common metadata terms
                .replaceAll("(320kbps|flac|mp3|wav|m4a|lossless|cd\\s*\\d+)", "")
                // Remove album/year patterns
                .replaceAll("\\d{4}", "")
                // Remove remix indicators for base matching
                .replaceAll("(remix|edit|version|remaster)", "")
                // Remove underscores and extra separators
                .replaceAll("[_]+", " ")
                .replaceAll("[-]+", " ")
                // Remove special characters except spaces
                .replaceAll("[^a-z0-9\\s]", "")
                // Normalize whitespace
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Extract artist and title from formats like:
     * "Riptide - Vance Joy"
     * "Vance Joy - Riptide"
     *
     * <p>This is now the degraded fallback path, used only from {@code isMatchByLegacySplit} when
     * {@code TrackQuery.artists()} is empty -- it is no longer the primary matching strategy.
     * Splitting an arbitrary string on the first "-" and guessing which side is the artist is a
     * guess, not a parse: a song whose own title contains a hyphen (e.g. "Twenty-One") gets
     * misparsed here. It stays only because some callers (the deprecated route, and rows
     * backfilled by V5__song_metadata.sql) genuinely have no artist metadata to hand in instead.
     */
    private TitleParts extractParts(String cleanTitle) {
        if (cleanTitle == null || !cleanTitle.contains("-")) {
            return new TitleParts(null, cleanTitle);
        }

        String[] parts = cleanTitle.split("-", 2);
        if (parts.length != 2) {
            return new TitleParts(null, cleanTitle);
        }

        String part1 = parts[0].trim();
        String part2 = parts[1].trim();

        // Try to determine which is artist vs title
        // Common pattern: "Title - Artist" or "Artist - Title"
        // We'll store both and check for both in the matching
        return new TitleParts(part1, part2);
    }

    // Helper classes
    private static class TitleParts {
        String artist;
        String title;

        TitleParts(String artist, String title) {
            this.artist = artist;
            this.title = title;
        }
    }

    @Getter
    @AllArgsConstructor
    public static class MatchResult {
        private String matchedPath;
        private int score;
        private boolean isMatch;
    }
}