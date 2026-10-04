package com.catacomb5099.naviseerr.util;

import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TrackMatchingService {

    private static final int MIN_TOKEN_SCORE = 75;
    private static final int MIN_PARTIAL_SCORE = 85;

    /**
     * Words that mark a different recording of the same song. A file carrying one of these is rejected unless
     * the request itself carries the same word ("Wonderwall (Remix) - Oasis" may match a remix; "Wonderwall - Oasis"
     * may not). Measured in the 2026-09-26 search lab: today's matcher accepted 2,628 live recordings, 1,867 remixes
     * and 482 acoustic takes that nobody asked for.
     */
    private static final Pattern VERSION_WORDS = Pattern.compile(
            "\\b(live|remix|rmx|mix|edit|acoustic|unplugged|instrumental|karaoke|cover|tribute|demo|mashup|bootleg|dub"
                    + "|slowed|sped up|nightcore|8d|acapella|a cappella|session|concert)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * DJ-pool signatures: radio-edit packs with intro/outro cuts, Clean/Dirty flags, key+BPM tags like "12A 125" and
     * promo-site stamps. They are the single largest class of wrong files (2,239 in the lab) and never what a
     * listener wants, unless the request itself carries the word: "Intro - The xx", "Clean - Taylor Swift" and
     * "Rather Be - Clean Bandit" are songs, not DJ edits (see {@link #isDjPoolEdit}).
     */
    private static final Pattern DJ_POOL = Pattern.compile(
            "\\b(clean|dirty|intro|outro|transition|redrum|refix|quick hit|hype)\\b|\\b\\d{1,2}[ab]\\s+\\d{2,3}\\b|dj-?promo|dj ?pool",
            Pattern.CASE_INSENSITIVE);

    /** How a request names the version it wants, checked in this order; the first hit wins. */
    private static final Pattern REQUESTED_REMIX = Pattern.compile("\\b(remix|rmx|mix|edit|dub)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUESTED_ACOUSTIC = Pattern.compile("\\b(acoustic|unplugged|stripped)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUESTED_LIVE = Pattern.compile("\\blive\\b|\\bconcert\\b|\\bsession\\b", Pattern.CASE_INSENSITIVE);

    /** Words a filename may use to say it is that version. */
    private static final Map<String, Set<String>> VERSION_FAMILY = Map.of(
            "remix", Set.of("remix", "rmx", "mix", "edit", "dub", "rework"),
            "acoustic", Set.of("acoustic", "unplugged", "live", "stripped"),
            "live", Set.of("live", "concert", "unplugged"));

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");

    /** Bracketed words in a YouTube title that say nothing about which recording it is. */
    private static final Set<String> QUALIFIER_NOISE = tokens("official video music lyric lyrics audio visualizer hd hq 4k"
            + " remastered remaster version ft feat featuring from the at with topic vevo closed captioned stereo dir bonus"
            + " track show colors movie ver clip full");
    private static final Pattern BRACKETED = Pattern.compile("[\\(\\[]([^\\)\\]]*)[\\)\\]]");
    private static final Pattern CREDIT_BRACKET = Pattern.compile("\\s*(feat|ft|with|dir|prod)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * How well a shared file answers a request. {@code EXACT}: the song, by the artist, in the requested version.
     * {@code OTHER_VERSION}: the song by the artist, but a live take, remix, acoustic or studio version the request
     * did not ask for; kept as a fallback because any version beats no song. {@code UNVERIFIED}: the song by
     * name, but the search did not name the artist and the artist is nowhere in the file's path, so whose
     * recording it is cannot be told; kept only when no file names the artist (a YouTube "artist" that is
     * really a channel, "UPROXX Indie Mixtape", appears in no path at all). {@code NONE}: not the song
     * (DJ-pool edits, album siblings, other artists). Declared in ranking order.
     */
    public enum Match { EXACT, OTHER_VERSION, UNVERIFIED, NONE }

    /** {@link #grade} is {@code EXACT}. */
    public boolean isMatch(String cleanTitle, String torrentFilePath) {
        return grade(cleanTitle, torrentFilePath) == Match.EXACT;
    }

    /**
     * {@link #grade(String, String)}, plus: when the search that found this file did not name the artist,
     * the artist must appear somewhere in the file's path for the grade to stand; otherwise the file is
     * {@code UNVERIFIED}. A search for the title alone ("Believe") returns every artist's "Believe", and the
     * filename by itself cannot tell Cher's from the rest; the folder usually can. When the wording did name
     * the artist, Soulseek has already matched it against the path, so this changes nothing for those
     * results. "The" is not required ("Cars/..." is The Cars), accents are ignored ("Beyonce" is Beyoncé),
     * a name run together ("LadyGaga") still counts, and a name with no Latin letters or digits
     * ("米津玄師") cannot be checked either way, so it is taken as named.
     */
    public Match grade(String cleanTitle, String torrentFilePath, String searchWording) {
        Match match = grade(cleanTitle, torrentFilePath);
        if (match == Match.NONE || artistNamed(cleanTitle, searchWording)) {
            return match;
        }
        return artistInPath(cleanTitle, torrentFilePath) ? match : Match.UNVERIFIED;
    }

    /**
     * The artist as the request writes it: the last part of "title - artist", or for the three-part
     * "artist - title - channel" the first part and the channel (an official channel echoes the artist).
     */
    private static List<String> artistNames(String request) {
        String[] parts = request.split(" - ");
        if (parts.length < 2) return List.of();
        return parts.length == 2 ? List.of(parts[1]) : List.of(parts[0], parts[parts.length - 1]);
    }

    /**
     * Every word of one of the request's artist names is in the wording, so Soulseek matched it already.
     * True as well when there is no artist, or the name has no words this matcher can read.
     */
    private static boolean artistNamed(String request, String searchWording) {
        List<String> names = artistNames(request);
        if (names.isEmpty()) return true;
        Set<String> wording = tokens(searchWording);
        for (String name : names) {
            Set<String> words = tokens(name);
            if (words.isEmpty() || wording.containsAll(words)) return true;
        }
        return false;
    }

    private static boolean artistInPath(String request, String path) {
        Set<String> pathTokens = tokens(path);
        String squashedPath = squash(path);
        for (String name : artistNames(request)) {
            Set<String> words = tokens(name);
            words.remove("the");
            String squashed = squash(name);
            if (!words.isEmpty() && pathTokens.containsAll(words)
                    || squashed.length() >= 5 && squashedPath.contains(squashed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Everything but the version decides whether this is the song at all; the version rules then decide only
     * between {@code EXACT} and {@code OTHER_VERSION}, in either direction: a file carrying a version word the
     * request did not ask for, or a request asking for a version the file does not name. "Remastered" is never a
     * version: it is neither in {@link #VERSION_WORDS} nor demanded by {@link #hasRequestedVersionWord}.
     */
    public Match grade(String cleanTitle, String torrentFilePath) {
        // Extract just the filename from the path
        String filename = extractFilename(torrentFilePath);

        if (isDjPoolEdit(cleanTitle, filename) || !titleInLastSegment(cleanTitle, filename)) {
            return Match.NONE;
        }

        // Normalize both strings
        String normalizedClean = normalize(cleanTitle);
        String normalizedTorrent = normalize(filename);

        // Use FuzzyWuzzy token sort (handles word order)
        int tokenScore = FuzzySearch.tokenSortRatio(normalizedClean, normalizedTorrent);

        // Use partial ratio (handles extra metadata in torrents)
        int partialScore = FuzzySearch.partialRatio(normalizedClean, normalizedTorrent);

        // At least two of the request's dash-separated parts appear in the filename: title and artist
        // for "Polish Girl - Neon Indian"; any two of artist, title and channel for
        // "Neon Indian - Polish Girl - toomainstream", so the uploading channel is never required.
        long partsPresent = Arrays.stream(cleanTitle.split(" - "))
                .map(this::normalize)
                .filter(part -> !part.isEmpty() && normalizedTorrent.contains(part))
                .count();
        boolean containsBothParts = partsPresent >= 2;

        if (tokenScore < MIN_TOKEN_SCORE && partialScore < MIN_PARTIAL_SCORE && !containsBothParts) {
            return Match.NONE;
        }
        boolean requestedVersion = !hasUnrequestedVersionWord(cleanTitle, filename)
                && hasRequestedVersionWord(cleanTitle, filename);
        return requestedVersion ? Match.EXACT : Match.OTHER_VERSION;
    }

    /**
     * The filename carries a {@link #DJ_POOL} signature that is not one of the request's own words: "01 - Intro.flac"
     * answers "Intro - The xx", but "Wonderwall (Clean Intro DJ Edit).mp3" still fails "Wonderwall - Oasis", and
     * "Clean (Dirty Intro).mp3" still fails "Clean - Taylor Swift".
     */
    private static boolean isDjPoolEdit(String request, String filename) {
        Set<String> requested = tokens(request);
        Matcher m = DJ_POOL.matcher(filename);
        while (m.find()) {
            if (!requested.containsAll(tokens(m.group()))) return true;
        }
        return false;
    }

    /** True when the filename names a version (live, remix, ...) that the request did not ask for. */
    private static boolean hasUnrequestedVersionWord(String cleanTitle, String filename) {
        Set<String> requested = versionWords(cleanTitle);
        for (String word : versionWords(filename)) {
            if (!requested.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * When the request asks for a non-original version ("Wonderwall (Live at Wembley) - Oasis"), the filename must
     * say so with a word from that version's family or one of the request's own bracketed qualifiers ("wembley").
     * Requests for the original recording pass untouched. Together with {@link #titleInLastSegment} this moved the
     * right top pick in the search lab from 93% to 97% (PickerLabelledTest).
     */
    private static boolean hasRequestedVersionWord(String request, String filename) {
        String title = requestTitle(request);
        Set<String> wanted = qualifierTokens(title, requestArtist(request));
        String version = requestedVersion(title);
        if (version != null) {
            wanted.addAll(VERSION_FAMILY.get(version));
        }
        if (wanted.isEmpty()) {
            return true;
        }
        Set<String> fileTokens = tokens(filename);
        return wanted.stream().anyMatch(fileTokens::contains);
    }

    /** "remix", "acoustic", "live" or null when the request title does not name a version. */
    private static String requestedVersion(String title) {
        if (REQUESTED_REMIX.matcher(title).find()) return "remix";
        if (REQUESTED_ACOUSTIC.matcher(title).find()) return "acoustic";
        if (REQUESTED_LIVE.matcher(title).find()) return "live";
        return null;
    }

    /**
     * Words the request title adds in brackets ("('95 version)", "[Slowed]", "(Live at X)") that are not noise,
     * not the artist, longer than two letters and not a bare number. Only brackets: SearchQueryTiers.pickerName
     * puts a version-naming dash segment ("- Radio Edit -") in brackets before the picker sees it, and any other
     * dash segment is an artist or a title, never a qualifier.
     */
    private static Set<String> qualifierTokens(String title, String artist) {
        Set<String> qualifiers = new HashSet<>();
        Matcher m = BRACKETED.matcher(title);
        while (m.find()) {
            if (!CREDIT_BRACKET.matcher(m.group(1)).lookingAt()) {
                qualifiers.addAll(tokens(m.group(1)));
            }
        }
        qualifiers.removeAll(QUALIFIER_NOISE);
        qualifiers.removeAll(tokens(artist));
        qualifiers.removeIf(w -> w.length() <= 2 || w.chars().allMatch(Character::isDigit));
        return qualifiers;
    }

    /**
     * Every word of the plain title must sit in the last dash-separated segment of the filename, so album siblings
     * ("Red Hot Chili Peppers - Californication - 09 - Emit Remmus.flac") no longer match on the album name. A
     * request with more than one part before the artist ("Neon Indian - Polish Girl - toomainstream") does not say
     * which part is the title, so any of them will do; the last part, the channel, is never required. A file named
     * the other way round ("6 FEET UNDER - Ruby Waters.mp3", the artist last) is judged on its whole name instead.
     */
    private static boolean titleInLastSegment(String request, String filename) {
        String stem = filename.replaceFirst("(?i)\\.[a-z0-9]+$", "");
        String[] segments = stem.split("\\s+-\\s+");
        String last = null;
        for (int i = segments.length - 1; i >= 0 && last == null; i--) {
            if (!segments[i].isBlank()) last = segments[i];
        }
        if (last == null) {
            return false;
        }
        Set<String> lastTokens = tokens(last);
        Set<String> artist = tokens(requestArtist(request));
        if (!artist.isEmpty() && lastTokens.containsAll(artist) && segments.length > 1) {
            lastTokens = tokens(stem);
        }
        for (String part : requestTitle(request).split(" - ")) {
            Set<String> plain = tokens(part.split("\\(")[0]);
            if (!plain.isEmpty() && lastTokens.containsAll(plain)) {
                return true;
            }
        }
        return false;
    }

    /** The request is "title - artist"; the artist is what follows the last " - ". */
    private static String requestTitle(String request) {
        int i = request.lastIndexOf(" - ");
        return i < 0 ? request : request.substring(0, i);
    }

    private static String requestArtist(String request) {
        int i = request.lastIndexOf(" - ");
        return i < 0 ? "" : request.substring(i + 3);
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = TOKEN.matcher(fold(text));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Lower case, accents gone: "Beyoncé" and "Beyonce" are the same name. */
    private static String fold(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Letters and digits only, so "Lady Gaga" is found inside "LadyGaga" and "lady_gaga". */
    private static String squash(String text) {
        return fold(text).replaceAll("[^a-z0-9]", "");
    }

    private static Set<String> versionWords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) return words;
        Matcher m = VERSION_WORDS.matcher(text);
        while (m.find()) {
            words.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return words;
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
}
