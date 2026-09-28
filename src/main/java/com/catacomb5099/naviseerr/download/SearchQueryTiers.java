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
 * <p>The first wording is the bare title -- the owner's call of 28-09-2026 ("just search for the song
 * name, removing all the fluff"; see {@code docs/decisions/title-first-search-28-09-2026.md}). It is
 * the loosest question Soulseek can be asked, so it also sidesteps the server silently dropping any
 * search that names certain artists (Michael Jackson, Depeche Mode, Linkin Park, Lady Gaga ...: 45 of
 * 809 songs in the lab returned zero peers with the artist and thousands of files without). The
 * picker carries the load: a file found without naming the artist must carry the artist in its path
 * ({@code TrackMatchingService.grade}). When the title alone finds too few acceptable files -- a
 * common title fills slskd's response cap with other artists' songs -- the wordings that name the
 * artist follow, starting with the bare {@code "title - artist"} that the 2026-09-26 search lab
 * (809 songs, 44,008 labelled results, {@code docs/decisions/soulseek-search-lab-26-09-2026.md})
 * found returning the requested song for 708 of the 718 songs it answered. The qualifier is never
 * searched: the bare query already held the requested remix or live take for 42 of the 52 songs that
 * asked for one, while keeping it in the search won only 32 and came back empty 48 times out of 180.
 *
 * <p>A name with three parts is read one of two ways. When a middle part names a version
 * ("Kiss Me - Radio Edit - Sixpence None The Richer") the first part is the title and the last the
 * artist, as before. Otherwise it is YouTube's own "Artist - Title" plus the uploading channel
 * ("Neon Indian - Polish Girl - toomainstream"): searching with the channel as the artist found
 * nothing in all 41 tries of the 27-09-2026 playlists, so the title alone goes first, "Artist Title"
 * second, and the channel wording last (see {@code docs/decisions/playlist-post-mortem-28-09-2026.md}).
 * After those come the same wordings with a shortened title, so the search stays general and the
 * picker does the discriminating; see {@link #of}.
 *
 * <p><b>The search is version-blind, the picker is version-aware.</b> No wording ever carries a
 * version word -- "(Live)", "- Remastered -", "Live at Wembley", "Radio Edit", "'95 version" -- whether
 * it sat in brackets, in its own dash segment or inline at the end of the title. Soulseek matches every
 * word, so a version word in the search only ever loses files. {@link #pickerName} keeps every one of
 * those qualifiers, in brackets, because the picker grades each file by them: the requested version
 * first, any other version as a fallback (see {@code TrackMatchingService.grade}).
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
     * A version phrase written inline at the end of a title, no punctuation: "Wonderwall Live at
     * Wembley", "Wonderwall Remastered 2009", "Kiss Me Radio Edit", "Song '95 Version". Peeled off the
     * end repeatedly ("Wonderwall Acoustic Live"). A title that is nothing but such a word ("Live")
     * is left alone.
     */
    private static final Pattern INLINE_VERSION = Pattern.compile(
            "\\s+(?:live\\s+(?:at|from|in)\\s+.+"
                    + "|(?:'?\\d{2,4}\\s+)?"
                    + "(?:(?:radio|club|single|album|extended|original|dub|vocal|acoustic|live|stripped|piano|slowed|full)\\s+)?"
                    + "(?:remaster(?:ed)?|version|remix|rmx|mix|edit|acoustic|unplugged|stripped|demo|instrumental"
                    + "|karaoke|session|live)(?:\\s+'?\\d{2,4})?)$",
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
     * Distinct queries in the order to try them, the title alone first, at most {@value #MAX_TIERS}.
     * The first wording is left behind when it finds too few files the picker accepts, the later ones
     * only when they find none ({@code DownloadStateMachine.afterSearchPoll}), so a later wording
     * costs nothing when an earlier one hits. Never empty: a name that is nothing but noise
     * ("(Official Video)") falls back to itself rather than to an empty search.
     *
     * <p>"Title - Artist": the title alone, the bare pair, then the same two with the title cut to
     * its first {@value #SHORT_TITLE_WORDS} words and any dash qualifier ("Radio Edit") removed, when
     * that is different. "Artist - Title - channel": the title alone, "Artist Title", the two short
     * forms, and the channel wording last only if there is room.
     */
    public static List<String> of(String songName) {
        String raw = songName == null ? "" : songName;
        Cleaned cleaned = clean(raw);
        List<String> parts = cleaned.parts();
        if (parts.isEmpty()) {
            return List.of(raw);
        }
        LinkedHashSet<String> tiers = new LinkedHashSet<>();
        String artist = parts.getLast();
        List<String> leading = parts.subList(0, parts.size() - 1);
        if (leading.isEmpty()) {
            tiers.add(artist);
        } else if (cleaned.artistTitleChannel()) {
            // The channel wording found nothing in all 41 tries of the 27-09-2026 playlists.
            String artistTitle = String.join(" ", leading);
            String title = String.join(" ", leading.subList(1, leading.size()));
            String shortTitle = shortTitle(title);
            tiers.add(title);
            tiers.add(artistTitle);
            tiers.add(leading.getFirst() + " " + shortTitle);
            tiers.add(shortTitle);
            tiers.add(artistTitle + " - " + artist);
        } else {
            String title = String.join(" ", leading);
            String shortTitle = shortTitle(leading.getFirst());
            tiers.add(title);
            tiers.add(title + " - " + artist);
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
        Cleaned cleaned = clean(raw);
        List<String> parts = cleaned.parts();
        if (parts.isEmpty()) {
            return raw;
        }
        String qualifiers = String.join(" ", cleaned.qualifiers());
        String artist = parts.getLast();
        List<String> leading = parts.subList(0, parts.size() - 1);
        if (leading.isEmpty()) {
            return String.join(" ", artist, qualifiers).strip();
        }
        return String.join(" ", String.join(" - ", leading), qualifiers).strip() + " - " + artist;
    }

    /**
     * The name taken apart. {@code parts}: last the artist, the rest the title (or artist and title
     * for the channel shape), with no version word anywhere -- what the searches are built from.
     * {@code qualifiers}: every version or other qualifier that was taken out, each as "(text)", in
     * the order found -- what the picker adds back. {@code artistTitleChannel}: how a three-part name
     * was read.
     */
    private record Cleaned(List<String> parts, List<String> qualifiers, boolean artistTitleChannel) {}

    /**
     * Every bracket gone (kept as qualifiers when more than platform noise), straight quotes gone
     * (they kill a Soulseek search outright), noise-only middle parts gone, a trailing "Lyrics" gone,
     * a "by Artist" credit made the artist, a "ft. Guest" credit gone from the title, the artist's
     * channel suffix gone, the artist's echo removed from the title, and every version phrase moved
     * out of the title into the qualifiers. One part when there is no artist; empty when nothing
     * survives.
     */
    private static Cleaned clean(String name) {
        List<String> qualifiers = new ArrayList<>();
        String stripped = TOPIC_SUFFIX.matcher(name.replace("\"", "")).replaceFirst("");
        stripped = withoutGroups(stripped, qualifiers);

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
            return new Cleaned(withoutInlineVersions(segments, qualifiers), qualifiers, false);
        }
        for (int i = 0; i < segments.size() - 1; i++) {
            String uncredited = CREDIT.matcher(segments.get(i)).replaceFirst("");
            if (!uncredited.isBlank()) {
                segments.set(i, uncredited);
            }
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
        boolean artistTitleChannel = titles.size() >= 2
                && titles.subList(1, titles.size()).stream().noneMatch(p -> QUALIFIER.matcher(p).find());
        if (titles.size() >= 2 && !artistTitleChannel) {
            // "Kiss Me - Radio Edit - Sixpence None The Richer": the middle names a version, so it is
            // a qualifier for the picker and nothing for the search. Never picked from: picking one
            // segment once turned "Wonderwall - Remastered - Oasis" into "Remastered - Oasis".
            titles.subList(1, titles.size()).forEach(q -> qualifiers.add("(" + q + ")"));
            titles = new ArrayList<>(titles.subList(0, 1));
        }
        titles = withoutInlineVersions(titles, qualifiers);
        titles.add(artist);
        return new Cleaned(titles, qualifiers, artistTitleChannel);
    }

    /** Peels "Live at Wembley", "Remastered 2009", "Radio Edit" off the end of each title part. */
    private static List<String> withoutInlineVersions(List<String> titles, List<String> qualifiers) {
        List<String> out = new ArrayList<>();
        for (String title : titles) {
            Matcher m = INLINE_VERSION.matcher(title);
            while (m.find() && m.start() > 0) {
                qualifiers.add("(" + title.substring(m.start()).strip() + ")");
                title = title.substring(0, m.start()).strip();
                m = INLINE_VERSION.matcher(title);
            }
            out.add(title);
        }
        return out;
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
