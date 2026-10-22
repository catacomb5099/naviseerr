package com.catacomb5099.naviseerr.download;

import java.time.Instant;
import java.util.List;

/**
 * One completed search of one song, as kept in {@code download_tasks.search_log} (V22, search learning 20-10-2026):
 * the words searched, the loop's wording number ({@code tier}, null for a person's typed text), how many sharers
 * answered and how many audio files they offered by the matcher's grade. No sharer names: only counts.
 */
public record SearchLogEntry(String wording, Integer tier, boolean manual, int responses, int files,
                             int exact, int otherVersion, int unverified, int none, Instant at) {

    /** Counts {@code graded} (every audio file the search found, as remembered for the manual picker) by grade. */
    public static SearchLogEntry of(String wording, Integer tier, int responses, List<DownloadCandidate> graded,
                                    Instant at) {
        return new SearchLogEntry(wording, tier, tier == null, responses, graded.size(),
                count(graded, "EXACT"), count(graded, "OTHER_VERSION"), count(graded, "UNVERIFIED"),
                count(graded, "NONE"), at);
    }

    private static int count(List<DownloadCandidate> graded, String grade) {
        return (int) graded.stream().filter(file -> grade.equals(file.grade())).count();
    }
}
