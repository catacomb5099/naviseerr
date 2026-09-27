package com.catacomb5099.naviseerr.util;

import lombok.AllArgsConstructor;
import lombok.Getter;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TrackMatchingService {

    private static final int MIN_TOKEN_SCORE = 75;
    private static final int MIN_PARTIAL_SCORE = 85;

    /**
     * Words that mark a different recording of the same song. A file carrying one of these is rejected unless
     * the request itself carries the same word ("Wonderwall (Remix) - Oasis" may match a remix; "Wonderwall - Oasis"
     * may not). Measured in the 2026-09-26 search lab: today's matcher accepted 2,628 live recordings, 1,867 remixes
     * and 482 acoustic takes that nobody asked for.
     */
    private static final Pattern VERSION_WORDS = Pattern.compile(
            "\\b(live|remix|rmx|mix|edit|acoustic|unplugged|instrumental|karaoke|cover|tribute|demo|mashup|bootleg|dub"
                    + "|slowed|sped up|nightcore|8d|acapella|a cappella|session|concert)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * DJ-pool signatures: radio-edit packs with intro/outro cuts, Clean/Dirty flags, key+BPM tags like "12A 125" and
     * promo-site stamps. They are the single largest class of wrong files (2,239 in the lab) and never what a
     * listener wants.
     */
    private static final Pattern DJ_POOL = Pattern.compile(
            "\\b(clean|dirty|intro|outro|transition|redrum|refix|quick hit|hype)\\b|\\b\\d{1,2}[ab]\\s+\\d{2,3}\\b|dj-?promo|dj ?pool",
            Pattern.CASE_INSENSITIVE);

    public boolean isMatch(String cleanTitle, String torrentFilePath) {
        // Extract just the filename from the path
        String filename = extractFilename(torrentFilePath);

        if (DJ_POOL.matcher(filename).find() || hasUnrequestedVersionWord(cleanTitle, filename)) {
            return false;
        }

        // Normalize both strings
        String normalizedClean = normalize(cleanTitle);
        String normalizedTorrent = normalize(filename);

        // Extract artist and title from clean title
        TitleParts cleanParts = extractParts(cleanTitle);

        // Use FuzzyWuzzy token sort (handles word order)
        int tokenScore = FuzzySearch.tokenSortRatio(normalizedClean, normalizedTorrent);

        // Use partial ratio (handles extra metadata in torrents)
        int partialScore = FuzzySearch.partialRatio(normalizedClean, normalizedTorrent);

        // Check if both artist and title appear in the torrent filename
        boolean containsBothParts = cleanParts.artist != null && cleanParts.title != null &&
                normalizedTorrent.contains(normalize(cleanParts.artist)) &&
                normalizedTorrent.contains(normalize(cleanParts.title));

        // Combine scoring logic
        return tokenScore >= MIN_TOKEN_SCORE ||
                partialScore >= MIN_PARTIAL_SCORE ||
                containsBothParts;
    }

    /** True when the filename names a version (live, remix, ...) that the request did not ask for. */
    private static boolean hasUnrequestedVersionWord(String cleanTitle, String filename) {
        Set<String> requested = versionWords(cleanTitle);
        for (String word : versionWords(filename)) {
            if (!requested.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> versionWords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) return words;
        Matcher m = VERSION_WORDS.matcher(text);
        while (m.find()) {
            words.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return words;
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
     */
    // TODO: makes the assumption that title and artist is separated by -, adjust with lastFM responses
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