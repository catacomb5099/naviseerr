package com.catacomb5099.naviseerr.download;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Soulseek queries to try for one song, in order: the bare {@code "title - artist"}, then the
 * title alone.
 *
 * <p>Both come from the 2026-09-26 search lab (809 songs, 44,008 labelled results, see
 * {@code docs/decisions/soulseek-search-lab-26-09-2026.md}). The bare query returned the requested
 * song for 708 of the 718 songs it answered, and already held the requested remix or live take for
 * 42 of the 52 songs that asked for one -- keeping the qualifier in the search won only 32 and came
 * back empty 48 times out of 180. So the qualifier is not searched; the picker sees the full song
 * name and chooses the version locally. The title-only fallback exists because the Soulseek server
 * silently drops any search containing certain artist names (Michael Jackson, Depeche Mode, Linkin
 * Park, ...): 45 of 809 songs returned zero peers with the artist and thousands of files without.
 *
 * <p>Pure and re-derived on every call, never stored: {@code download_tasks} keeps only the index of
 * the tier in use (see {@link DownloadTask#searchTier}), so these rules can change without a data
 * migration. A fallback identical to the first query is dropped.
 */
final class SearchQueryTiers {

    /**
     * Platform words that never appear in a music filename. Applied ONLY to a separator-delimited
     * segment that is nothing but noise ("Wonderwall - Official Video - Oasis"); brackets are dropped
     * whole, and the first and last segments are title and artist by construction ("Audio - Sia").
     */
    private static final Pattern NOISE = Pattern.compile(
            "\\b(official hd remastered video|official hd music video|official music video|official lyric video"
                    + "|official hd video|official 4k video|official video|original video|video oficial|official audio"
                    + "|remastered video|music video|lyric video|full video song|video song|visuali[sz]er|lyrics|lyrical"
                    + "|closed-captioned|official|audio|video|song|hd|hq|4k)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * YouTube channel dressing on an artist name: "BlondieVEVO", "Blondie - Topic", "Oasis Official".
     * The lab found "Maria - BlondieVEVO" returning 8,092 files for the title and none for Blondie.
     */
    private static final Pattern CHANNEL_SUFFIX = Pattern.compile(
            "\\s*(?:vevo|official|records)$", Pattern.CASE_INSENSITIVE);

    /** " - Topic" is its own dashed segment, so it goes before the name is split on dashes. */
    private static final Pattern TOPIC_SUFFIX = Pattern.compile(
            "\\s+[-\u2013\u2014|]\\s+topic$", Pattern.CASE_INSENSITIVE);

    private static final String INNER = "[^()\\[\\]{}]*";

    /** One innermost ( ), [ ] or { } group. Nesting is handled by looping. */
    private static final Pattern GROUP = Pattern.compile(
            "\\(" + INNER + "\\)|\\[" + INNER + "\\]|\\{" + INNER + "\\}");

    /**
     * What separates title, artist and whatever else YouTube put in the name. The hyphen is what
     * {@code DownloadTaskRunner.soulseekQuery} joins with; en dash, em dash and pipe are what
     * official channels type. A run of separators counts as one.
     */
    private static final Pattern SEGMENT = Pattern.compile("\\s+(?:[-\u2013\u2014|]\\s+)+");

    private SearchQueryTiers() {}

    /**
     * Distinct queries in the order to try them. Never empty: a name that is nothing but noise
     * ("(Official Video)") falls back to itself rather than to an empty search.
     */
    static List<String> of(String songName) {
        String raw = songName == null ? "" : songName;
        List<String> segments = bareSegments(raw);
        if (segments.isEmpty()) {
            return List.of(raw);
        }
        LinkedHashSet<String> tiers = new LinkedHashSet<>();
        tiers.add(String.join(" - ", segments));
        if (segments.size() > 1) {
            tiers.add(segments.getFirst());
        }
        return List.copyOf(tiers);
    }

    /**
     * {@code [title, artist]} with every bracket gone, straight quotes gone (they kill a Soulseek
     * search outright), noise-only middle segments gone, the artist's channel suffix gone and the
     * artist's echo removed from the title. {@code [title]} when there is no artist; empty when
     * nothing survives.
     */
    private static List<String> bareSegments(String name) {
        String stripped = TOPIC_SUFFIX.matcher(name.replace("\"", "")).replaceFirst("");
        String previous;
        do {
            previous = stripped;
            stripped = GROUP.matcher(stripped).replaceAll("");
        } while (!stripped.equals(previous));

        List<String> segments = new ArrayList<>(Arrays.stream(SEGMENT.split(stripped))
                .map(SearchQueryTiers::collapse)
                .filter(segment -> !segment.isEmpty())
                .toList());
        if (segments.size() > 2) {
            segments.subList(1, segments.size() - 1).removeIf(segment -> stripNoise(segment).isEmpty());
        }
        if (segments.size() < 2) {
            return segments;
        }
        String artist = collapse(CHANNEL_SUFFIX.matcher(segments.getLast()).replaceFirst(""));
        if (artist.isEmpty()) {
            artist = segments.getLast();
        }
        String finalArtist = artist;
        List<String> titles = new ArrayList<>(segments.subList(0, segments.size() - 1));
        // "Oasis - Don't Look Back In Anger - Oasis": YouTube's own "Artist - Title" plus the artist
        // soulseekQuery appended. The artist is the one segment we know; drop its echo from the title.
        titles.removeIf(segment -> segment.equalsIgnoreCase(finalArtist) || segment.equalsIgnoreCase(segments.getLast()));
        if (titles.isEmpty()) {
            titles.add(segments.getFirst());
        }
        // ponytail: what is left is joined, never picked from. Picking one segment turned
        // "Wonderwall - Remastered - Oasis" into "Remastered - Oasis", a different Oasis song.
        return List.of(String.join(" ", titles), artist);
    }

    private static String stripNoise(String text) {
        return collapse(NOISE.matcher(collapse(text)).replaceAll(""));
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
