package com.catacomb5099.naviseerr.download;

import lombok.Builder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
     * "Title - Artist" first, the same pair a song searches with (brackets, quotes and version words
     * gone, see {@link SearchQueryTiers}); then the title alone, used only when that found nobody at all
     * -- Soulseek drops every search naming certain artists. A compilation searches its title only:
     * nobody's folder says "Various Artists".
     */
    public List<String> wordings() {
        String artist = artists == null || artists.isEmpty() ? "" : artists.getFirst();
        if (artist.isBlank() || artist.equalsIgnoreCase("Various Artists")) {
            return List.of(SearchQueryTiers.of(title).getFirst());
        }
        List<String> song = SearchQueryTiers.of(title + " - " + artist);
        return song.size() < 2 ? song : List.of(song.get(1), song.get(0));
    }

    public String searchQuery() {
        List<String> wordings = wordings();
        return wordings.get(Math.clamp(searchTier, 0, wordings.size() - 1));
    }

    public boolean hasAnotherWording() {
        return searchTier + 1 < wordings().size();
    }

    public boolean isPastBudget(Instant now, Duration budget) {
        return !now.isBefore(phaseEnteredAt.plus(budget));
    }
}
