package com.catacomb5099.naviseerr.download;

import lombok.Builder;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One {@code album_searches} row (P5): an album download's own search for a sharer holding the whole
 * album, with the album's name and artists joined in from its {@code media_items} row. Only claimed
 * rows are read, so {@code phase} is always {@code SEARCH_INIT} or {@code SEARCH_POLL}; {@code DONE}
 * lives in SQL only. Shaped like {@link DownloadTask} and stepped the same way: claimed under a lease,
 * one slskd call per step, written back whole.
 */
@Builder(toBuilder = true)
public record AlbumSearch(UUID downloadId, DownloadPhase phase, int searchTier, String searchId,
                          Instant phaseEnteredAt, Instant nextAttemptAt, String title, List<String> artists) {

    /** Why an album search ended; stored by name in {@code album_searches.outcome}. */
    public enum Outcome {
        /** A whole folder was found; its songs were handed their files. */
        WHOLE_FOLDER,
        /** P6: no whole folder, but one held at least half; those songs got its files, the rest search on their own. */
        PART_FOLDER,
        /** No folder held every song, nor half of them; they search on their own. */
        NO_WHOLE_FOLDER,
        /** Every song had already started (or finished) on its own; nothing was searched. */
        NOTHING_TO_SEARCH,
        /** slskd would not start the search, or never answered; the songs search on their own. */
        SEARCH_FAILED,
        /** The whole download was cancelled (written by the cancel statement). */
        CANCELLED
    }

    /**
     * "Deluxe Edition", "30th Anniversary Super Deluxe", "Expanded Edition" left at the end of a title
     * once its brackets are gone: the shared folder is usually just the album's name.
     */
    private static final Pattern EDITION = Pattern.compile(
            "\\s+(?:\\d+(?:st|nd|rd|th)\\s+)?(?:anniversary\\s+)?(?:super\\s+)?"
                    + "(?:deluxe|expanded|special|collector'?s|legacy|anniversary)(?:\\s+(?:edition|version))?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The album's name alone, never the artist (the owner's call of 07-10-2026): Soulseek drops every
     * search naming certain artists, a folder can spell the artist differently, and
     * {@link AlbumFolderPicker} checks the artist on every folder that answers anyway. The title loses
     * its brackets, quotes and version words as a song's does ({@link SearchQueryTiers}) and a trailing
     * edition, and is then {@link #plain}. One wording, still a list so {@link #searchQuery} and the
     * stored {@code search_tier} keep their shape.
     */
    public List<String> wordings() {
        String cleanTitle = EDITION.matcher(SearchQueryTiers.of(title).getFirst()).replaceFirst("");
        String plainTitle = plain(cleanTitle).isEmpty() ? cleanTitle : plain(cleanTitle);
        return List.of(plainTitle);
    }

    /**
     * A name as a shared folder is likely to spell it: every word split at punctuation, one-letter
     * pieces left out ("Morning Glory?" is "Morning Glory", "Sgt. Pepper's" is "Sgt Pepper", "AC/DC"
     * is "AC DC"). Soulseek must match every word, and a Windows folder cannot even hold a "?". Empty
     * when no word is left (a title such as "4" is then searched as it is).
     */
    static String plain(String name) {
        return Arrays.stream(name.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() > 1)
                .collect(Collectors.joining(" "));
    }

    public String searchQuery() {
        List<String> wordings = wordings();
        return wordings.get(Math.clamp(searchTier, 0, wordings.size() - 1));
    }

    public boolean isPastBudget(Instant now, Duration budget) {
        return !now.isBefore(phaseEnteredAt.plus(budget));
    }
}
