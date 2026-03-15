package com.catacomb5099.naviseerr.util;

import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.springframework.stereotype.Service;

@Service
public class TrackMatchingService {

    public boolean isMatch(String title1, String title2) {
        // 1. Normalize both strings
        String normalized1 = normalize(title1);
        String normalized2 = normalize(title2);

        // 2. Use FuzzyWuzzy token sort (handles word order)
        int tokenScore = FuzzySearch.tokenSortRatio(normalized1, normalized2);

        // 3. Use partial ratio (handles extra metadata in torrents)
        int partialScore = FuzzySearch.partialRatio(normalized1, normalized2);

        // 4. Combine scores
        return tokenScore > 85 || partialScore > 90;
    }

    private String normalize(String title) {
        return title.toLowerCase()
                .replaceAll("\\[.*?\\]", "") // Remove brackets
                .replaceAll("\\(.*?\\)", "") // Remove parentheses
                .replaceAll("\\{.*?\\}", "") // Remove braces
                .replaceAll("(320kbps|flac|mp3|wav|m4a)", "") // Remove formats
                .replaceAll("[^a-z0-9\\s]", "") // Keep only alphanumeric
                .replaceAll("\\s+", " ") // Normalize spaces
                .trim();
    }
}
