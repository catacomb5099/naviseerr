package com.catacomb5099.naviseerr.util;

import lombok.AllArgsConstructor;
import lombok.Getter;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TrackMatchingService {

    private static final int MIN_TOKEN_SCORE = 75;
    private static final int MIN_PARTIAL_SCORE = 85;

    public boolean isMatch(String cleanTitle, String torrentFilePath) {
        // Extract just the filename from the path
        String filename = extractFilename(torrentFilePath);

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