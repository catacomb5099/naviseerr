package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P5 and P6: which sharers' folders hold the album, whole ones first, then the ones holding the most of
 * it. Pure apart from its two collaborators, which supply the rules a single song is already judged by:
 * {@link TrackMatchingService#grade} must call a file the requested version (EXACT) of one track, and
 * the file must pass the same format rule ({@link SlskdSearchResultProcessor#isLosslessOrHighBitRate}).
 * What is new here is only what an album needs and a song does not: files grouped by sharer and folder
 * (disc subfolders merged), each file's length held against the YouTube row's, one file per track and
 * one track per file, and the folder shown to be the album artist's.
 *
 * <p>Measured 04-10-2026 on eight real album searches (understand-slskd.md): a whole folder existed for
 * every one, including Talk Talk's Laughing Stock, with 72 to 230 clean folders each; deluxe editions
 * (Definitely Maybe: 32 folders of 40+ files) sit beside the plain ones, which is why only the matched
 * files are ever downloaded. Some folders are genuinely partial (raphyduck's Definitely Maybe lacks Live
 * Forever), which is what a part folder is for when nobody has the whole album.
 */
@Component
public class AlbumFolderPicker {

    /** A disc subfolder of an album folder: "CD1", "CD 02", "Disc 2 (B-Sides, Etc.)", "CD II", "LP 1". */
    private static final Pattern DISC_FOLDER =
            Pattern.compile("(?i)^(cd|disc|disk|lp|dvd)\\s*[-_.]?\\s*(\\d+|[ivx]+)\\b.*");

    /**
     * Words that make a file another take of the song, beyond the version words grade() already knows
     * (live, demo, remix, session...): "Up In The Sky (Sawmills Outtake)", "Digsy's Dinner (Monnow Valley
     * Version)". Allowed when the YouTube title, the album or the artist has the word too.
     */
    private static final Pattern OTHER_TAKE =
            Pattern.compile("(?i)\\b(outtakes?|takes?|versions?|alternate|rehearsal|mono)\\b");
    /** "Album Version" and its kin name the plain recording, not another one. */
    private static final Pattern PLAIN_VERSION =
            Pattern.compile("(?i)\\b(album|original|lp|stereo|remaster(ed)?)\\s+version\\b");
    private static final Pattern TRACK_NUMBER = Pattern.compile("^(?:\\d[-.](?=\\d\\d))?(\\d{1,3})");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^[a-d]?\\d{1,3}(?:[-.]\\d{1,3})?[\\s.\\-_)]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{2,4}$");
    /** A file this short is a truncated rip or a stub, unless YouTube's track is that short too. */
    private static final int SHORTEST_FILE_SECONDS = 30;
    /** A compilation folder must carry each track's own artist on at least this share of its files. */
    private static final double COMPILATION_ARTIST_SHARE = 0.8;

    private final TrackMatchingService matching;
    private final SlskdSearchResultProcessor files;

    public AlbumFolderPicker(TrackMatchingService matching, SlskdSearchResultProcessor files) {
        this.matching = matching;
        this.files = files;
    }

    /**
     * One sharer's folder holding every track, or (P6) a part of them: the file for each task it holds,
     * and how many usable audio files it has besides (bonus tracks, other takes; never downloaded, but a
     * sign of a deluxe edition).
     */
    public record Folder(SearchResponseItem peer, String path, Map<UUID, SearchFile> files, int extras) {
        /** One sharer's one folder. */
        public String key() {
            return peer.getUsername() + "\u0000" + path;
        }
    }

    /**
     * Every folder that holds all of {@code tracks}, or at least half of them and at least two (P6: a part
     * album from one sharer beats a song from each of many), best first: the most tracks, so every whole
     * folder comes before any part; then the song picker's order -- an overloaded sharer (no free slot and
     * a queue past {@code max-sharer-queue}) last, a sharer that can start now, the shortest queue -- with
     * the fewest extra files slotted in before the sharer's own speed claim. Stalling sharers are left out.
     *
     * @param tracks       the album's songs still waiting, in track order (the last may run long: a
     *                     hidden track)
     * @param albumArtists YouTube's artists for the album; "Various Artists" for a compilation
     */
    public List<Folder> folders(List<SearchResponseItem> responses, List<DownloadTask> tracks,
                                String albumTitle, List<String> albumArtists, Predicate<String> stalling) {
        if (tracks.isEmpty()) {
            return List.of();
        }
        int fewest = Math.min(tracks.size(), Math.max(2, (tracks.size() + 1) / 2));
        boolean compilation = isCompilation(tracks, albumArtists);
        List<Folder> found = new ArrayList<>();
        for (Grouped folder : group(responses).values()) {
            if (folder.usable.size() < fewest || stalling.test(folder.peer.getUsername())
                    || !compilation && albumArtists.stream().noneMatch(folder::names)) {
                continue;
            }
            Map<UUID, SearchFile> assigned = assign(folder.usable, tracks, albumTitle, albumArtists, fewest);
            if (assigned.size() >= fewest && (!compilation || carriesTrackArtists(assigned, tracks))) {
                found.add(new Folder(folder.peer, folder.path, assigned, folder.usable.size() - assigned.size()));
            }
        }
        found.sort(bestFirst());
        return found;
    }

    /**
     * Every folder holding at least one of {@code tracks} by the song rules -- whatever its bit rate,
     * whether or not it names the artist, however few songs it has, stalling sharer or not -- in the
     * order of {@link #folders}. The manual picker's list (every option, 08-10-2026): the search itself
     * keeps using {@link #folders}, and the person is shown which of these it would have taken.
     */
    public List<Folder> allFolders(List<SearchResponseItem> responses, List<DownloadTask> tracks,
                                   String albumTitle, List<String> albumArtists) {
        if (tracks.isEmpty()) {
            return List.of();
        }
        List<Folder> found = new ArrayList<>();
        for (Grouped folder : group(responses).values()) {
            Map<UUID, SearchFile> assigned = assign(folder.audio, tracks, albumTitle, albumArtists, 1);
            if (!assigned.isEmpty()) {
                found.add(new Folder(folder.peer, folder.path, assigned, folder.audio.size() - assigned.size()));
            }
        }
        found.sort(bestFirst());
        return found;
    }

    /** The most tracks, then the song picker's order (overloaded last, a free slot, the shortest queue), the fewest extras, speed. */
    private Comparator<Folder> bestFirst() {
        return Comparator
                .comparingInt((Folder f) -> -f.files().size())
                .thenComparing(f -> files.isOverloaded(f.peer()))
                .thenComparing(f -> !Boolean.TRUE.equals(f.peer().getHasFreeUploadSlot()))
                .thenComparingInt(f -> f.peer().getQueueLength())
                .thenComparingInt(Folder::extras)
                .thenComparingInt(f -> -f.peer().getUploadSpeed());
    }

    /**
     * Each track's candidates: its file in the best folder, then in the next folders of OTHER sharers
     * that hold it, up to {@code perTrack}, so a sharer that stalls costs one candidate and the next
     * folder takes over (P5). Every song the best folder holds gets the same sharer first.
     */
    public static Map<UUID, List<DownloadCandidate>> candidates(List<Folder> ranked, int perTrack) {
        Map<UUID, List<DownloadCandidate>> out = new LinkedHashMap<>();
        Map<UUID, Set<String>> sharers = new HashMap<>();
        for (Folder folder : ranked) {
            folder.files().forEach((taskId, file) -> {
                List<DownloadCandidate> picks = out.computeIfAbsent(taskId, k -> new ArrayList<>());
                if (picks.size() < perTrack
                        && sharers.computeIfAbsent(taskId, k -> new HashSet<>()).add(folder.peer().getUsername())) {
                    picks.add(DownloadCandidate.fromAlbumFolder(folder.peer(), file));
                }
            });
        }
        return out;
    }

    /** One (sharer, folder) with its disc subfolders merged in; {@code usable} passes the format rule. */
    private static final class Grouped {
        final SearchResponseItem peer;
        final String path;
        final List<String> allNames = new ArrayList<>();
        /** Every audio file, for the manual picker's list. */
        final List<SearchFile> audio = new ArrayList<>();
        /** The audio files passing the format rule, for the search's own judgement. */
        final List<SearchFile> usable = new ArrayList<>();

        Grouped(SearchResponseItem peer, String path) {
            this.peer = peer;
            this.path = path;
        }

        /** The folder's path or any of its file names (a cover image named "Daft Punk - Discovery.jpg" counts). */
        boolean names(String artist) {
            return TrackMatchingService.nameInPath(artist, path)
                    || allNames.stream().anyMatch(name -> TrackMatchingService.nameInPath(artist, name));
        }
    }

    private Map<String, Grouped> group(List<SearchResponseItem> responses) {
        Map<String, Grouped> folders = new LinkedHashMap<>();
        for (SearchResponseItem peer : responses) {
            for (SearchFile file : peer.getFiles() == null ? List.<SearchFile>of() : peer.getFiles()) {
                List<String> segments = LibraryOrganiser.segments(file.getFilename());
                if (segments.isEmpty()) {
                    continue;
                }
                List<String> parent = segments.subList(0, segments.size() - 1);
                if (!parent.isEmpty() && DISC_FOLDER.matcher(parent.getLast()).matches()) {
                    parent = parent.subList(0, parent.size() - 1);
                }
                String path = String.join("\\", parent);
                Grouped folder = folders.computeIfAbsent(peer.getUsername() + "\u0000" + path,
                        key -> new Grouped(peer, path));
                folder.allNames.add(segments.getLast());
                if (SlskdSearchResultProcessor.isAudio(file)) {
                    folder.audio.add(file);
                }
                if (files.isLosslessOrHighBitRate(file)) {
                    folder.usable.add(file);
                }
            }
        }
        return folders;
    }

    /** A pairing of one track with one file of the folder, and how good it is. */
    private record Pair(DownloadTask track, SearchFile file, boolean exactTitle, int lengthGap, boolean sameNumber) {}

    /**
     * One file per track, one track per file. Every pairing the song rules accept (EXACT, the length
     * within max(10 s, 3%), no other-take word) is ranked -- the title as written beats the title once
     * brackets are dropped, so "Up In The Sky" takes "04 Up In The Sky.flac" and not "(Sawmills Outtake)";
     * then the smaller length gap; then the file's own track number -- and taken best first. Gives up,
     * empty, as soon as fewer than {@code fewest} tracks can still be found here.
     *
     * <p>ponytail: greedy, not an optimal assignment; an album's tracks rarely compete for one file, and
     * when they do the better pairing wins, which is what an optimal one would mostly do too.
     */
    private Map<UUID, SearchFile> assign(List<SearchFile> usable, List<DownloadTask> tracks,
                                         String albumTitle, List<String> albumArtists, int fewest) {
        List<Pair> pairs = new ArrayList<>();
        int missing = 0;
        String albumWords = albumTitle + " " + String.join(" ", albumArtists);
        for (int i = 0; i < tracks.size(); i++) {
            DownloadTask track = tracks.get(i);
            // Graded on the title alone: the folder test above already stands for the artist, and a
            // file named "1-01 Myrrhman.mp3" scores too low against "Myrrhman - Talk Talk".
            String picker = SearchQueryTiers.pickerName(track.songName());
            int dash = picker.lastIndexOf(" - ");
            String request = dash < 0 ? picker : picker.substring(0, dash);
            String title = track.trackTitle() == null ? request : track.trackTitle();
            String allowed = title + " " + picker + " " + albumWords;
            boolean last = i == tracks.size() - 1;
            boolean any = false;
            for (SearchFile file : usable) {
                Optional<Integer> gap = lengthGap(track.durationSeconds(), file.getLength(), last);
                String leaf = LibraryOrganiser.baseName(file.getFilename());
                if (gap.isEmpty() || !exact(request, picker, file.getFilename()) || namesAnotherTake(leaf, allowed)) {
                    continue;
                }
                pairs.add(new Pair(track, file, sameTitle(title, leaf), gap.get(),
                        track.trackNumber() != null && track.trackNumber().equals(trackNumber(leaf))));
                any = true;
            }
            if (!any && ++missing > tracks.size() - fewest) {
                return Map.of(); // too many tracks nobody in this folder has: stop looking
            }
        }
        pairs.sort(Comparator.comparing((Pair p) -> !p.exactTitle())
                .thenComparingInt(Pair::lengthGap)
                .thenComparing(p -> !p.sameNumber()));
        Map<UUID, SearchFile> assigned = new LinkedHashMap<>();
        Set<SearchFile> used = new HashSet<>();
        for (Pair pair : pairs) {
            if (!assigned.containsKey(pair.track().taskId()) && used.add(pair.file())) {
                assigned.put(pair.track().taskId(), pair.file());
            }
        }
        return assigned;
    }

    /**
     * The title alone first ("1-01 Myrrhman.mp3" scores too low against "Myrrhman - Talk Talk"), else the
     * title with the artist: "01 Rock 'n' Roll Star - Oasis.flac" has the artist, not the title, in its
     * last segment, and only the artist-aware grade reads its whole name (21 such files in one real search).
     */
    private boolean exact(String request, String picker, String filename) {
        return matching.grade(request, filename) == TrackMatchingService.Match.EXACT
                || matching.grade(picker, filename) == TrackMatchingService.Match.EXACT;
    }

    /**
     * How far the file's length is from the YouTube row's, when it is close enough to be the same
     * recording: within max(10 s, 3%), or any longer for the last track (a hidden track in the same file;
     * Nevermind's runs 20 minutes). Empty when it is not. A length slskd did not give (3.5% of files) or
     * YouTube did not give is no evidence either way and ranks behind every known one.
     */
    static Optional<Integer> lengthGap(Integer expected, Optional<Integer> actual, boolean last) {
        if (actual.isEmpty()) {
            return Optional.of(Integer.MAX_VALUE);
        }
        int seconds = actual.get();
        if (seconds < SHORTEST_FILE_SECONDS && (expected == null || expected >= SHORTEST_FILE_SECONDS)) {
            return Optional.empty();
        }
        if (expected == null) {
            return Optional.of(Integer.MAX_VALUE);
        }
        int tolerance = SlskdSearchResultProcessor.lengthTolerance(expected);
        int diff = seconds - expected;
        boolean fits = last ? diff >= -tolerance : Math.abs(diff) <= tolerance;
        return fits ? Optional.of(Math.abs(diff)) : Optional.empty();
    }

    /** The file names another take of the song than the one asked for ("Sawmills Outtake", "Monnow Valley Version"). */
    static boolean namesAnotherTake(String leaf, String allowedText) {
        Set<String> allowed = TrackMatchingService.tokens(allowedText);
        Matcher m = OTHER_TAKE.matcher(PLAIN_VERSION.matcher(leaf).replaceAll(" "));
        while (m.find()) {
            if (!allowed.contains(m.group(1).toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** The file's own title, after its track number, is the YouTube title exactly (brackets and all). */
    static boolean sameTitle(String youtubeTitle, String leaf) {
        String[] segments = EXTENSION.matcher(leaf).replaceFirst("").split("\\s+-\\s+");
        String own = LEADING_NUMBER.matcher(segments[segments.length - 1].strip()).replaceFirst("");
        return TrackMatchingService.squash(own).equals(TrackMatchingService.squash(youtubeTitle));
    }

    /** "04 - Title" is 4, "1-04 Title" and "104 - Title" are 4 (disc 1); null without a leading number. */
    static Integer trackNumber(String leaf) {
        Matcher m = TRACK_NUMBER.matcher(leaf);
        return m.find() ? Integer.parseInt(m.group(1)) % 100 : null;
    }

    /**
     * YouTube says "Various Artists", or no song of the album is by its album artist: then a folder is
     * the album's when its files carry each song's own artist, not when it names the album artist.
     */
    private static boolean isCompilation(List<DownloadTask> tracks, List<String> albumArtists) {
        if (albumArtists.isEmpty() || albumArtists.stream().anyMatch(a -> a.equalsIgnoreCase("Various Artists"))) {
            return true;
        }
        Set<String> album = new HashSet<>(albumArtists.stream().map(TrackMatchingService::squash).toList());
        return tracks.stream().noneMatch(t -> album.contains(TrackMatchingService.squash(artistOf(t))));
    }

    private static boolean carriesTrackArtists(Map<UUID, SearchFile> assigned, List<DownloadTask> tracks) {
        long carrying = tracks.stream()
                .filter(t -> assigned.containsKey(t.taskId())
                        && TrackMatchingService.nameInPath(artistOf(t), assigned.get(t.taskId()).getFilename()))
                .count();
        return carrying >= COMPILATION_ARTIST_SHARE * assigned.size();
    }

    /** The song's first artist: song_name is "Title - Artist" (DownloadTaskRunner.soulseekQuery). */
    private static String artistOf(DownloadTask track) {
        String name = track.songName() == null ? "" : track.songName();
        int dash = name.lastIndexOf(" - ");
        return dash < 0 ? "" : name.substring(dash + 3);
    }
}
