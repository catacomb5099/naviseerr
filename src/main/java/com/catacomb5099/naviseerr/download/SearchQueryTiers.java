package com.catacomb5099.naviseerr.download;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Soulseek queries to try for one song, noisiest first. A YouTube title carries platform noise
 * -- "(Official Lyric Video)", a leading "Artist - " the artist suffix then repeats -- that never
 * appears in a music filename, so the raw name often finds nothing while the bare title does.
 *
 * <p>Pure and re-derived on every call, never stored: {@code download_tasks} keeps only the index of
 * the tier in use (see {@link DownloadTask#searchTier}), so these rules can change without a data
 * migration. Each tier is derived from the one before it, so tier three never re-admits what tier
 * two removed. Tiers that come out identical to an earlier one are dropped, so a title that is
 * already clean is searched once, not three times.
 */
final class SearchQueryTiers {

    /**
     * Applied ONLY inside bracket groups and to a separator-delimited segment that is nothing but
     * noise. A global word strip would butcher real titles -- "Video Games - Lana Del Rey", "Audio -
     * Sia" -- whereas inside brackets, or standing alone between two separators, these words are
     * always the platform talking, not the song. Longer phrases first, so "official video" is
     * consumed whole rather than leaving a stray "official". Musically meaningful qualifiers (remix,
     * live, acoustic, feat., "'95 Version") are deliberately absent: if the title asks for a remix,
     * search the remix.
     */
    private static final Pattern NOISE = Pattern.compile(
            "\\b(official hd remastered video|official music video|official lyric video|official video"
                    + "|official audio|remastered video|music video|lyric video|visuali[sz]er|lyrics"
                    + "|official|audio|video|hd|hq|4k)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String INNER = "[^()\\[\\]{}]*";

    /**
     * One innermost ( ), [ ] or { } group, contents captured. Innermost means no bracket of ANY kind
     * inside, so "(Official Video [HD])" yields the square group first rather than a paren group that
     * swallows it. Nesting is handled by looping.
     */
    private static final Pattern GROUP = Pattern.compile(
            "\\((" + INNER + ")\\)|\\[(" + INNER + ")\\]|\\{(" + INNER + ")\\}");

    /**
     * What separates title, artist and whatever else YouTube put in the name. The hyphen is what
     * {@code DownloadTaskRunner.soulseekQuery} joins with; en dash, em dash and pipe are what
     * official channels type between artist and title. A run of separators counts as one, so a
     * bracket group removed from between two of them leaves no " - - " behind.
     */
    private static final Pattern SEGMENT = Pattern.compile("\\s+(?:[-\u2013\u2014|]\\s+)+");

    private SearchQueryTiers() {}

    /** Distinct queries in the order to try them. Never empty: the raw name is always tier one. */
    static List<String> of(String songName) {
        String raw = songName == null ? "" : songName;
        String noiseless = withoutNoise(raw);
        LinkedHashSet<String> tiers = new LinkedHashSet<>();
        tiers.add(raw);
        for (String derived : List.of(noiseless, bare(noiseless))) {
            if (!derived.isBlank()) {
                tiers.add(derived);
            }
        }
        return List.copyOf(tiers);
    }

    /**
     * Tier two: bracket groups keep what is musically meaningful, a group left empty goes, and so
     * does a segment that is nothing but noise ("Wonderwall - Official Video - Oasis"). Only the
     * middle segments are candidates for that: the first and the last are title and artist by
     * construction, and "Audio - Sia" shows a title can BE a noise word.
     */
    private static String withoutNoise(String name) {
        String cleaned = name;
        String previous;
        do {
            previous = cleaned;
            cleaned = GROUP.matcher(cleaned).replaceAll(SearchQueryTiers::groupWithoutNoise);
        } while (!cleaned.equals(previous));

        List<String> segments = segments(cleaned);
        List<String> kept = new ArrayList<>(segments);
        if (kept.size() > 2) {
            kept.subList(1, kept.size() - 1).removeIf(segment -> stripNoise(segment).isEmpty());
        }
        // Rebuilt with " - " only once something has gone: a title with nothing to strip must come
        // out byte-identical, or an en dash's worth of difference would cost a whole second search.
        return cleaned.equals(name) && kept.size() == segments.size()
                ? name
                : String.join(" - ", kept);
    }

    private static String groupWithoutNoise(MatchResult match) {
        String inner = match.group(1) != null ? match.group(1)
                : match.group(2) != null ? match.group(2) : match.group(3);
        String kept = stripNoise(inner);
        if (kept.isEmpty()) {
            return "";
        }
        String whole = match.group();
        return Matcher.quoteReplacement(whole.charAt(0) + kept + whole.charAt(whole.length() - 1));
    }

    /** Tier three: no brackets at all, and exactly one "title - artist". */
    private static String bare(String name) {
        String stripped = name;
        String previous;
        do {
            previous = stripped;
            stripped = GROUP.matcher(stripped).replaceAll("");
        } while (!stripped.equals(previous));

        List<String> segments = segments(stripped);
        if (segments.size() < 2) {
            return String.join("", segments);
        }
        String artist = segments.getLast();
        List<String> titles = new ArrayList<>(segments.subList(0, segments.size() - 1));
        // "Oasis - Don't Look Back In Anger - Oasis": YouTube's own "Artist - Title" plus the artist
        // soulseekQuery appended. The artist is the one segment we know; drop its echo from the title.
        titles.removeIf(segment -> segment.equalsIgnoreCase(artist));
        if (titles.isEmpty()) {
            titles.add(segments.getFirst());
        }
        // ponytail: what is left is joined, never picked from. No segment is safe to guess as the
        // title -- picking one turned "Wonderwall - Remastered - Oasis" into "Remastered - Oasis",
        // which downloads a different Oasis song. Ceiling: a featured artist YouTube put first stays
        // in the query, and Soulseek wants every word present. Upgrade to smarter picking if that
        // ever matters.
        return String.join(" ", titles) + " - " + artist;
    }

    private static List<String> segments(String name) {
        return Arrays.stream(SEGMENT.split(name))
                .map(SearchQueryTiers::collapse)
                .filter(segment -> !segment.isEmpty())
                .toList();
    }

    /** Collapsed BEFORE matching, so a doubled space cannot hide "Lyric  Video" from the phrase list. */
    private static String stripNoise(String text) {
        return collapse(NOISE.matcher(collapse(text)).replaceAll(""));
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
