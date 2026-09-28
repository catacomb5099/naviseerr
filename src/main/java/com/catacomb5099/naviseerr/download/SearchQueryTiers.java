package com.catacomb5099.naviseerr.download;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Soulseek queries to try for one song, in order, and the cleaned name the file picker judges
 * results against. Both are derived from the same reading of the YouTube name, so what is searched
 * and what is accepted can never disagree about which words are the song.
 *
 * <p>The queries come from the 2026-09-26 search lab (809 songs, 44,008 labelled results, see
 * {@code docs/decisions/soulseek-search-lab-26-09-2026.md}). The bare {@code "title - artist"}
 * returned the requested song for 708 of the 718 songs it answered, and already held the requested
 * remix or live take for 42 of the 52 songs that asked for one -- keeping the qualifier in the search
 * won only 32 and came back empty 48 times out of 180. So the qualifier is not searched; the picker
 * sees it in {@link #pickerName} and chooses the version locally. The title-only fallback exists
 * because the Soulseek server silently drops any search containing certain artist names (Michael
 * Jackson, Depeche Mode, Linkin Park, MGMT, Two Door Cinema Club ...): 45 of 809 songs returned zero
 * peers with the artist and thousands of files without.
 *
 * <p>A name with three parts is read one of two ways. When a middle part names a version
 * ("Kiss Me - Radio Edit - Sixpence None The Richer") the first part is the title and the last the
 * artist, as before. Otherwise it is YouTube's own "Artist - Title" plus the uploading channel
 * ("Neon Indian - Polish Girl - toomainstream"): searching with the channel as the artist found
 * nothing in all 41 tries of the 27-09-2026 playlists, so "Artist Title" goes first, the title alone
 * second, and the channel wording last (see {@code docs/decisions/playlist-post-mortem-28-09-2026.md}).
 * After those come the same wordings with a shortened title, so the search stays general and the
 * picker does the discriminating; see {@link #of}.
 *
 * <p>Pure and re-derived on every call, never stored: {@code download_tasks} keeps only the index of
 * the tier in use (see {@link DownloadTask#searchTier}), so these rules can change without a data
 * migration. A fallback identical to an earlier query is dropped.
 */
public final class SearchQueryTiers {

    /**
     * Platform words that never appear in a music filename. Applied ONLY to a separator-delimited
     * segment that is nothing but noise ("Wonderwall - Official Video - Oasis") and to a bracket
     * group that is nothing but noise; the first and last segments are title and artist by
     * construction ("Audio - Sia").
     */
    private static final Pattern NOISE = Pattern.compile(
            "\\b(official hd remastered video|official hd music video|official music video|official lyric video"
                    + "|official hd video|official 4k video|official video|original video|video oficial|official audio"
                    + "|remastered video|music video|lyric video|full video song|video song|visuali[sz]er|lyrics|lyrical"
                    + "|closed-captioned|official|audio|video|song|hd|hq|4k)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * "Floette ft. John Glacier": a guest credit written into the title. Soulseek files mostly leave it
     * out, and a search must match every word, so the credit only ever loses results. "with", "&" and
     * "x" are left alone: they are words in too many real titles ("Ebony & Ivory").
     */
    private static final Pattern CREDIT = Pattern.compile(
            "\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", Pattern.CASE_INSENSITIVE);

    /** Longer titles are searched by their first words too; five is enough to be distinctive. */
    private static final int SHORT_TITLE_WORDS = 5;

    /** Never more than this many searches (about 10 s of slskd time each) for one song. */
    private static final int MAX_TIERS = 4;

    /** "Such Great Heights Lyrics", "Anyone Else But You w/ Lyrics": lyric uploads tag the title itself. */
    private static final Pattern TRAILING_LYRICS = Pattern.compile(
            "\\s+(?:w/|with)?\\s*lyrics$", Pattern.CASE_INSENSITIVE);

    /**
     * "Tongue Tied by Grouplove": a lower-case "by" followed by a capitalised name is a credit, so the
     * name becomes its own part. "Stand By Me" and "Blinded by the Light" do not match: the first has
     * a capital B, the second's "the" is lower case.
     */
    private static final Pattern BY_ARTIST = Pattern.compile("^(.*\\S)\\s+by\\s+(\\p{Lu}.*)$");

    /**
     * A middle part that names a version rather than a song: "Radio Edit", "Remastered",
     * "Live At The Florida Theatre". Decides which way a three-part name is read.
     */
    private static final Pattern QUALIFIER = Pattern.compile(
            "\\b(remaster(?:ed)?|version|edit|mix|remix|live|acoustic|unplugged|instrumental|demo|session"
                    + "|radio|single|album|extended|mono|stereo|cover|karaoke)\\b",
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
     * official channels type. A run of separators counts as one. A dash glued to the word before it
     * but not the one after ("The Con- Tegan and Sara") is a separator too; a dash glued on both
     * sides ("Tone-Loc", "Jay-Z", "Metric-Black Sheep") is left alone because the first two are
     * names, and nothing in the text tells the third apart from them.
     */
    private static final Pattern SEGMENT = Pattern.compile(
            "\\s+(?:[-\u2013\u2014|]\\s+)+|(?<=\\S)[-\u2013\u2014]\\s+");

    private SearchQueryTiers() {}

    /**
     * Distinct queries in the order to try them, most specific first, at most {@value #MAX_TIERS}. A
     * wording is only tried when the one before it produced no file the picker accepts, so a looser
     * wording costs nothing when a tighter one hits. Never empty: a name that is nothing but noise
     * ("(Official Video)") falls back to itself rather than to an empty search.
     *
     * <p>"Title - Artist": the bare pair, the title alone (the server drops some artists), then the
     * same two with the title cut to its first {@value #SHORT_TITLE_WORDS} words and any dash
     * qualifier ("Radio Edit") removed, when that is different. "Artist - Title - channel": "Artist
     * Title", the title alone, the two short forms, and the channel wording last only if there is room.
     */
    public static List<String> of(String songName) {
        String raw = songName == null ? "" : songName;
        List<String> parts = parts(raw);
        if (parts.isEmpty()) {
            return List.of(raw);
        }
        LinkedHashSet<String> tiers = new LinkedHashSet<>();
        String artist = parts.getLast();
        List<String> leading = parts.subList(0, parts.size() - 1);
        if (leading.isEmpty()) {
            tiers.add(artist);
        } else if (isArtistTitleChannel(parts)) {
            // The channel wording found nothing in all 41 tries of the 27-09-2026 playlists.
            String artistTitle = String.join(" ", leading);
            String title = String.join(" ", leading.subList(1, leading.size()));
            String shortTitle = shortTitle(title);
            tiers.add(artistTitle);
            tiers.add(title);
            tiers.add(leading.getFirst() + " " + shortTitle);
            tiers.add(shortTitle);
            tiers.add(artistTitle + " - " + artist);
        } else {
            // ponytail: what is left is joined, never picked from. Picking one segment turned
            // "Wonderwall - Remastered - Oasis" into "Remastered - Oasis", a different Oasis song.
            String title = String.join(" ", leading);
            String shortTitle = shortTitle(leading.getFirst());
            tiers.add(title + " - " + artist);
            tiers.add(title);
            tiers.add(shortTitle + " - " + artist);
            tiers.add(shortTitle);
        }
        return tiers.stream().limit(MAX_TIERS).toList();
    }

    /** The first {@value #SHORT_TITLE_WORDS} words of a longer title; a shorter title as it is. */
    private static String shortTitle(String title) {
        String[] words = title.split(" ");
        return words.length <= SHORT_TITLE_WORDS ? title : String.join(" ", Arrays.copyOf(words, SHORT_TITLE_WORDS));
    }

    /**
     * The name the picker judges files against: the same parts the searches are built from, joined
     * with " - ", plus every bracketed qualifier the YouTube name carried that is not platform noise
     * ("(Live)", "(Acoustic)", "(Remix)"), kept because the picker chooses the version from them.
     * A version-naming middle part is folded into brackets for the same reason:
     * "Kiss Me - Radio Edit - Sixpence None The Richer" becomes "Kiss Me (Radio Edit) - Sixpence
     * None The Richer". A name nothing survives from is returned as it is.
     */
    public static String pickerName(String songName) {
        String raw = songName == null ? "" : songName;
        List<String> parts = parts(raw);
        if (parts.isEmpty()) {
            return raw;
        }
        List<String> qualifiers = new ArrayList<>();
        withoutGroups(raw.replace("\"", ""), qualifiers);
        String artist = parts.getLast();
        List<String> leading = new ArrayList<>(parts.subList(0, parts.size() - 1));
        if (leading.isEmpty()) {
            return String.join(" ", artist, String.join(" ", qualifiers)).strip();
        }
        if (!isArtistTitleChannel(parts)) {
            qualifiers.addAll(0, leading.subList(1, leading.size()).stream().map(q -> "(" + q + ")").toList());
            leading = leading.subList(0, 1);
        }
        String title = String.join(" ", String.join(" - ", leading), String.join(" ", qualifiers)).strip();
        return title + " - " + artist;
    }

    /** True for "Artist - Title - channel": three or more parts, none of the middle ones a version. */
    private static boolean isArtistTitleChannel(List<String> parts) {
        return parts.size() >= 3
                && parts.subList(1, parts.size() - 1).stream().noneMatch(p -> QUALIFIER.matcher(p).find());
    }

    /**
     * The name taken apart, last part the artist, with every bracket gone, straight quotes gone (they
     * kill a Soulseek search outright), noise-only middle parts gone, a trailing "Lyrics" gone, a
     * "by Artist" credit made the artist, a "ft. Guest" credit gone from the title, the artist's
     * channel suffix gone and the artist's echo removed from the title. One part when there is no artist; empty when nothing survives.
     */
    static List<String> parts(String name) {
        String stripped = TOPIC_SUFFIX.matcher(name.replace("\"", "")).replaceFirst("");
        stripped = withoutGroups(stripped, null);

        List<String> segments = new ArrayList<>(Arrays.stream(SEGMENT.split(stripped))
                .map(SearchQueryTiers::collapse)
                .map(SearchQueryTiers::withoutTrailingLyrics)
                .filter(segment -> !segment.isEmpty())
                .toList());
        if (segments.size() > 2) {
            segments.subList(1, segments.size() - 1).removeIf(segment -> stripNoise(segment).isEmpty());
        }
        if (!segments.isEmpty()) {
            // "Tongue Tied by Grouplove - Hyde": the credit names the artist, so whoever uploaded it
            // is dropped and the name is a plain "Title - Artist".
            Matcher by = BY_ARTIST.matcher(segments.getFirst());
            if (by.matches()) {
                segments = new ArrayList<>(List.of(by.group(1), by.group(2)));
            }
        }
        if (segments.size() < 2) {
            return segments;
        }
        for (int i = 0; i < segments.size() - 1; i++) {
            String uncredited = CREDIT.matcher(segments.get(i)).replaceFirst("");
            if (!uncredited.isBlank()) {
                segments.set(i, uncredited);
            }
        }
        String artist = collapse(CHANNEL_SUFFIX.matcher(segments.getLast()).replaceFirst(""));
        if (artist.isEmpty()) {
            artist = segments.getLast();
        }
        String finalArtist = artist;
        String reportedArtist = segments.getLast();
        List<String> titles = new ArrayList<>(segments.subList(0, segments.size() - 1));
        // "Oasis - Don't Look Back In Anger - Oasis": YouTube's own "Artist - Title" plus the artist
        // soulseekQuery appended. The artist is the one segment we know; drop its echo from the title.
        titles.removeIf(segment -> segment.equalsIgnoreCase(finalArtist) || segment.equalsIgnoreCase(reportedArtist));
        if (titles.isEmpty()) {
            titles.add(segments.getFirst());
        }
        titles.add(artist);
        return titles;
    }

    /**
     * The text with every bracket group removed, innermost first. When {@code kept} is given, each
     * group whose text is more than platform noise is added to it as "(text)".
     */
    private static String withoutGroups(String text, List<String> kept) {
        String previous;
        do {
            previous = text;
            Matcher m = GROUP.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String inner = collapse(m.group().substring(1, m.group().length() - 1));
                if (kept != null && !stripNoise(inner).isEmpty()) {
                    kept.add("(" + inner + ")");
                }
                m.appendReplacement(out, "");
            }
            text = m.appendTail(out).toString();
        } while (!text.equals(previous));
        return text;
    }

    /** Only when something is left: "Lyrics - Someone" keeps its title. */
    private static String withoutTrailingLyrics(String segment) {
        String cut = TRAILING_LYRICS.matcher(segment).replaceFirst("");
        return cut.isEmpty() ? segment : cut;
    }

    private static String stripNoise(String text) {
        return collapse(NOISE.matcher(collapse(text)).replaceAll(""));
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
