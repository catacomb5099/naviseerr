package com.catacomb5099.naviseerr.mediaserver;

import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one rule for "which song in Navidrome or Jellyfin is the file naviseerr filed": the key is that file's
 * path relative to the library root, which is a suffix of the media server's own path whatever its mount.
 * Jellyfin shows real paths, so it is matched by path only (exact). Navidrome's Subsonic API hides them
 * unless the player asks for real paths, so there a path suffix still wins, and otherwise the song must
 * carry the first YouTube artist (case and accents folded) and be within the picker's own length rule
 * ({@link SlskdSearchResultProcessor#lengthTolerance}, max(10 s, 3%)) of the filed file's length; the newest
 * such song wins a tie (an album twin is the same song).
 */
// ponytail: Navidrome is matched by artist + length, not by file; its native /api/song (with path) is the
// upgrade if wrong picks ever show up.
public final class SongFinder {

    private static final Pattern TRACK_NUMBER = Pattern.compile("^\\d{1,3}\\s*[-._)]?\\s*");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private SongFinder() {}

    /** A song naviseerr filed: YouTube's title and artists, the file's length (else YouTube's), and its path relative to the library root ('/'-separated). */
    public record Wanted(String title, List<String> artists, Integer durationSeconds, String relativePath) {}

    /** One song a media server listed; {@code artists} is every artist text it gave (song and album artist). */
    public record Candidate(String id, String albumId, String path, String artists, Integer durationSeconds, String created) {}

    /** What a player needs to play it and to open its album. */
    public record Found(String id, String albumId) {}

    /** What to search the media server for, in order: YouTube's title, then the file name without track number and extension. */
    public static List<String> searchTerms(Wanted wanted) {
        List<String> terms = new ArrayList<>();
        if (wanted.title() != null && !wanted.title().isBlank()) {
            terms.add(wanted.title().strip());
        }
        String file = wanted.relativePath().substring(wanted.relativePath().lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        String stem = TRACK_NUMBER.matcher(dot > 0 ? file.substring(0, dot) : file).replaceFirst("").strip();
        if (!stem.isEmpty() && terms.stream().noneMatch(stem::equalsIgnoreCase)) {
            terms.add(stem);
        }
        return terms;
    }

    /** Jellyfin: the song whose path ends with the filed file's relative path; nothing else. */
    public static Optional<Found> byPath(Wanted wanted, List<Candidate> found) {
        return found.stream().filter(c -> pathMatches(wanted, c)).findFirst().map(SongFinder::found);
    }

    /** Navidrome: a path match first, else artist + length, newest first. */
    public static Optional<Found> byPathOrTags(Wanted wanted, List<Candidate> found) {
        Optional<Found> exact = byPath(wanted, found);
        if (exact.isPresent()) {
            return exact;
        }
        String artist = wanted.artists() == null || wanted.artists().isEmpty() ? null : fold(wanted.artists().getFirst());
        return found.stream()
                .filter(c -> artist == null || (c.artists() != null && fold(c.artists()).contains(artist)))
                .filter(c -> wanted.durationSeconds() == null || (c.durationSeconds() != null
                        && Math.abs(c.durationSeconds() - wanted.durationSeconds())
                                <= SlskdSearchResultProcessor.lengthTolerance(wanted.durationSeconds())))
                .max(Comparator.comparing(c -> c.created() == null ? "" : c.created()))
                .map(SongFinder::found);
    }

    private static boolean pathMatches(Wanted wanted, Candidate c) {
        if (c.path() == null) {
            return false;
        }
        String path = c.path().replace('\\', '/');
        return path.equals(wanted.relativePath()) || path.endsWith("/" + wanted.relativePath());
    }

    private static Found found(Candidate c) {
        return new Found(c.id(), c.albumId());
    }

    static String fold(String s) {
        return MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT).strip();
    }
}
