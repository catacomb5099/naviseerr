package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Moves a finished song's file out of slskd's downloads folder into an organised music library
 * that Navidrome and Jellyfin can scan, and removes the partial file slskd leaves behind when a
 * song fails for good. Off unless {@code library.slskd-downloads-dir} and {@code library.root} are
 * both set, in which case slskd's downloads folder must be visible on this machine (same host, or
 * a shared mount) -- see docs/decisions/library-organiser-27-09-2026.md.
 *
 * <p>Folder scheme (every component sanitised by {@link #sanitise}):
 * <pre>
 *   song              root/&lt;primary artist&gt;/&lt;song title&gt;/&lt;file as downloaded&gt;
 *   album track       root/&lt;album artist&gt;/&lt;album title&gt;/&lt;file as downloaded&gt;
 *   playlist track    filed exactly like a song; the playlist itself is one .m3u8 in root/Playlists/
 *   song or playlist track with a trusted YouTube Music album ({@link SongAlbumResolver})
 *                     root/&lt;album artist&gt;/&lt;album title&gt;/&lt;file as downloaded&gt;, like that album's tracks
 * </pre>
 *
 * <p>Just before the move, {@link SongTagger} writes YouTube Music's details into the file's tags. A
 * song whose album track the library already has is not moved at all: it points at that file and the
 * new copy is removed ({@link Job#filedCopy}).
 *
 * <p>Pure path logic (locating, sanitising, choosing a target) is in static methods so it can be
 * tested without a filesystem; the two entry points {@link #file} and {@link #deletePartials} do the
 * blocking I/O on {@code boundedElastic}. Nothing here ever fails a download: a filing problem is
 * logged and the row is left for the next pass.
 */
@Slf4j
@Component
public class LibraryOrganiser {

    /** A finished song whose file has not turned up in slskd's downloads folder by then is given up on. */
    static final Duration GIVE_UP_AFTER = Duration.ofMinutes(10);
    /**
     * How long a finished song or playlist track waits for its album lookup before it is filed by its
     * own name. The lookup normally answers while the song is still downloading; this covers a busy or
     * slow YouTube.
     */
    static final Duration ALBUM_LOOKUP_GRACE = Duration.ofMinutes(2);

    private static final Pattern SEPARATORS = Pattern.compile("[\\\\/]+");
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:$");
    // Windows-illegal characters plus control characters; the strictest rule set, so the library is
    // safe to share over SMB. slskd on Linux does not do this for us (only NUL and '/').
    private static final Pattern ILLEGAL = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Pattern RESERVED =
            Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(\\..*)?$");
    private static final int MAX_COMPONENT_BYTES = 200;
    /** Where a download's playlist file goes: a folder of nothing but .m3u8s, so Jellyfin never sees it as an album. */
    static final String PLAYLISTS_FOLDER = "Playlists";

    /**
     * One finished song to file, joined with the names its folders are built from. {@code albumTitle}
     * and {@code albumArtists} are the trusted YouTube Music album of a song or playlist track; null and
     * empty when it has none, and always for an album download's track. {@code tags} is what the file's
     * tags are written from; null writes none. {@code filedCopy} is the library's file of this same
     * song, when it already has one; null otherwise. Two jobs with one {@code copyKey} are copies of one
     * song: only one is filed per pass. {@code pickSize} is the picked file's size as the sharer listed
     * it, null when unknown.
     */
    public record Job(UUID taskId, DownloadType type, String slskdFilename, Instant finishedAt,
                      String songTitle, List<String> songArtists,
                      String collectionTitle, List<String> collectionArtists,
                      String albumTitle, List<String> albumArtists, SongTagger.Tags tags,
                      String filedCopy, String copyKey, Long pickSize) {

        /** A song the library does not have yet. */
        public Job(UUID taskId, DownloadType type, String slskdFilename, Instant finishedAt,
                   String songTitle, List<String> songArtists,
                   String collectionTitle, List<String> collectionArtists,
                   String albumTitle, List<String> albumArtists, SongTagger.Tags tags) {
            this(taskId, type, slskdFilename, finishedAt, songTitle, songArtists, collectionTitle,
                    collectionArtists, albumTitle, albumArtists, tags, null, null, null);
        }
    }

    /** A file the library already has for the song at {@code position} (1-based) of a download being admitted. */
    public record FiledCopy(int position, String libraryPath) {}

    /** One finished download whose songs are all filed, ready for its playlist file. */
    public record Collection(UUID downloadId, DownloadType type, String title) {}

    /** One filed song as a playlist line shows it. */
    public record Entry(String libraryPath, String title, List<String> artists, Integer durationSeconds) {}

    private final Path downloadsDir;
    private final Path incompleteDir;
    private final Path root;
    private final Duration loopInterval;
    private final SongTagger tagger;
    private final boolean enabled;

    public LibraryOrganiser(
            @Value("${library.slskd-downloads-dir:}") String downloadsDir,
            @Value("${library.slskd-incomplete-dir:}") String incompleteDir,
            @Value("${library.root:}") String root,
            @Value("${download-task.loop-interval-ms:2000}") Duration loopInterval,
            SongTagger tagger) {
        this.loopInterval = loopInterval;
        this.tagger = tagger;
        if (downloadsDir.isBlank() || root.isBlank()) {
            this.downloadsDir = null;
            this.incompleteDir = null;
            this.root = null;
            this.enabled = false;
            log.info("Library organiser OFF: finished downloads stay where slskd puts them. Set "
                    + "SLSKD_DOWNLOADS_DIR and LIBRARY_ROOT to file them into a music library.");
            return;
        }
        this.downloadsDir = real(Path.of(downloadsDir));
        this.incompleteDir = incompleteDir.isBlank() ? null : real(Path.of(incompleteDir));
        this.root = real(Path.of(root));
        boolean overlaps = overlaps(this.root, this.downloadsDir)
                || (this.incompleteDir != null && overlaps(this.root, this.incompleteDir));
        if (overlaps) {
            log.error("Library organiser DISABLED: library.root ({}) must not be inside slskd's "
                    + "downloads/incomplete folders ({}, {}) or contain them -- it would move files "
                    + "onto themselves.", this.root, this.downloadsDir, this.incompleteDir);
        } else {
            log.info("Library organiser ON: finished files move from {} into {}{}", this.downloadsDir,
                    this.root, this.incompleteDir == null
                            ? "; partial files of failed songs are left alone (SLSKD_INCOMPLETE_DIR unset)"
                            : "; partial files of failed songs are removed from " + this.incompleteDir);
        }
        this.enabled = !overlaps;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Songs that finished before this instant are no longer looked for in the downloads folder. */
    public Instant cutoff(Instant now) {
        return now.minus(GIVE_UP_AFTER);
    }

    /** Songs that finished before this instant no longer wait for their album lookup. */
    public Instant albumCutoff(Instant now) {
        return now.minus(ALBUM_LOOKUP_GRACE);
    }

    /**
     * Tags one song's file and moves it into the library. The album cover is fetched first (once per
     * album, see {@link SongTagger#cover}); a song without one is filed all the same.
     *
     * @return the file's new absolute path, or empty when the file is not in the downloads folder
     *         (slskd may still be moving it out of incomplete; the row is left for the next pass)
     */
    public Mono<Path> file(Job job, Instant now) {
        return tagger.cover(job.tags())
                .defaultIfEmpty(SongTagger.NO_COVER)
                .flatMap(cover -> Mono.fromCallable(() -> fileBlocking(job, cover, now))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Best-effort removal of the partial files slskd keeps for every peer that was tried for a song
     * that has now failed for good. slskd keeps them so a retry can resume; this song will not be
     * retried. A no-op unless {@code library.slskd-incomplete-dir} is set.
     */
    public Mono<Void> deletePartials(DownloadTask task) {
        if (!enabled || incompleteDir == null) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> deletePartialsBlocking(task))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("Could not remove partial files of failed song {}", task.taskId(), error);
                    return Mono.empty();
                });
    }

    /**
     * Writes (or rewrites -- the file is naviseerr's, and regenerating it whole is the idempotent
     * move) {@code root/Playlists/<title>.m3u8} listing the entries relative to that folder, so
     * Navidrome and Jellyfin both show the download as a playlist. Written only once every song is in
     * place: Jellyfin drops entries whose file does not exist at import time.
     */
    public Mono<Path> writePlaylist(String title, List<Entry> entries) {
        return Mono.fromCallable(() -> writePlaylistBlocking(title, entries))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * For each of {@code count} songs being admitted, the first of its {@code copies} (newest first)
     * that is still a file in the library, else null. A file deleted since, or outside the library
     * root, does not count, so that song is downloaded again.
     */
    public Mono<List<String>> stillFiled(int count, List<FiledCopy> copies) {
        return Mono.fromCallable(() -> {
            String[] paths = new String[count];
            for (FiledCopy copy : copies) {
                int i = copy.position() - 1;
                if (paths[i] == null && inLibrary(copy.libraryPath())) {
                    paths[i] = copy.libraryPath();
                }
            }
            return Arrays.asList(paths);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ---- blocking I/O ----------------------------------------------------------------------------

    Path writePlaylistBlocking(String title, List<Entry> entries) throws IOException {
        String name = orElse(title, "Playlist");
        Path folder = guard(root.resolve(PLAYLISTS_FOLDER), root);
        Files.createDirectories(folder);
        Path target = guard(folder.resolve(sanitise(name) + ".m3u8"), folder);
        List<Entry> inside = entries.stream()
                .filter(entry -> {
                    boolean ok = entry.libraryPath() != null && Path.of(entry.libraryPath()).startsWith(root);
                    if (!ok) log.warn("Playlist '{}' skips {}: not inside {}", name, entry.libraryPath(), root);
                    return ok;
                })
                .toList();
        // Staged then renamed, so a scanner never reads a half-written list.
        Path staging = target.resolveSibling(target.getFileName() + ".partial");
        Files.writeString(staging, m3u8(name, inside, folder), UTF_8);
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        log.info("Wrote playlist {} with {} track(s)", target, inside.size());
        return target;
    }

    Path fileBlocking(Job job, byte[] cover, Instant now) throws IOException {
        Path source = locate(job.slskdFilename());
        if (source == null) {
            // ponytail: "one WARN" assumes a regular loop cadence; a pass slower than the interval
            // can skip or double it. Good enough for a log line.
            boolean lastLook = !job.finishedAt().plus(GIVE_UP_AFTER).isAfter(now.plus(loopInterval));
            if (lastLook) {
                log.warn("Giving up on filing song {}: '{}' never appeared under {} within {}. Left "
                        + "wherever slskd put it.", job.taskId(), job.slskdFilename(), downloadsDir,
                        GIVE_UP_AFTER);
            } else {
                log.debug("Song {} not in {} yet ('{}'); will look again next pass", job.taskId(),
                        downloadsDir, job.slskdFilename());
            }
            return null;
        }
        if (inLibrary(job.filedCopy())) {
            // The library has this song already (the recording's other YouTube id, a song request
            // filed into the album's folder first, the same single asked for twice): one file per
            // song, so nothing is listed twice. The new copy is removed only when its size is the
            // picked file's: two songs can share a folder and file name, and the other's must stay.
            if (job.pickSize() != null && Files.size(source) == job.pickSize()) {
                Files.delete(source);
                deleteIfEmpty(source.getParent(), downloadsDir);
                log.info("Song {} is already in the library as {}; removed the new copy {}", job.taskId(),
                        job.filedCopy(), source);
            } else {
                log.info("Song {} is already in the library as {}; left {} alone, it may be another song's",
                        job.taskId(), job.filedCopy(), source);
            }
            return Path.of(job.filedCopy());
        }
        // Tagged where it lies, complete and outside the library, so a scanner never reads it mid-write
        // and the real extension picks the format. A move that fails after this tags it again next
        // pass, which changes nothing the second time.
        tagger.tag(source, job.tags(), cover);
        Path folder = targetFolder(job);
        Files.createDirectories(folder);
        String name = sanitiseFileName(baseName(job.slskdFilename()));
        // Staged under a non-audio name first: across filesystems Files.move is a copy then a
        // delete, and a scanner must never index the half-copied file. The name carries the task id
        // so two songs filing into one folder never share a staging file, which is also why
        // REPLACE_EXISTING is safe: it can only replace this task's own leftover from a crashed pass.
        Path staging = folder.resolve(name + "." + job.taskId() + ".partial");
        Files.move(source, staging, StandardCopyOption.REPLACE_EXISTING);
        // The free name is picked right before the rename, so a copy filed meanwhile gets ' (2)'.
        Path target = unique(folder.resolve(name));
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        deleteIfEmpty(source.getParent(), downloadsDir);
        log.info("Filed song {} into {}", job.taskId(), target);
        return target;
    }

    void deletePartialsBlocking(DownloadTask task) {
        List<DownloadCandidate> candidates = task.candidates() == null ? List.of() : task.candidates();
        // Every candidate up to the current one was attempted, and each attempt may have left a file.
        int tried = Math.min(task.candidateIndex() + 1, candidates.size());
        for (DownloadCandidate candidate : candidates.subList(0, tried)) {
            Path partial = partialPath(candidate.username(), candidate.filename());
            if (partial == null || !Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.delete(partial);
                log.info("Removed partial file {} of failed song {}", partial, task.taskId());
                // slskd only removes the immediate parent; walk up to the base folder ourselves.
                Path dir = partial.getParent();
                while (dir != null && deleteIfEmpty(dir, incompleteDir)) {
                    dir = dir.getParent();
                }
            } catch (IOException e) {
                log.warn("Could not remove partial file {} of failed song {}", partial, task.taskId(), e);
            }
        }
    }

    /**
     * Where slskd put the finished file: {@code <downloads>/<last folder of the remote path>/<file>}
     * (slskd's default {@code ${SOURCE_DIRECTORY}} rule). If that exact path is missing, the one
     * other name slskd's default {@code exists: rename} can produce is looked for in the SAME folder:
     * {@code <stem>_<ticks><ext>}, newest first. Nothing else is searched -- a same-named file in
     * another folder is another song's, and a file slskd put elsewhere (a changed subdirectory
     * pattern) is a give-up, not a guess.
     */
    Path locate(String remoteFilename) throws IOException {
        List<String> segments = segments(remoteFilename);
        if (segments.isEmpty()) {
            return null;
        }
        String name = segments.getLast();
        String parent = segments.size() > 1 ? segments.get(segments.size() - 2) : "";
        Path expected = guard(downloadsDir.resolve(parent).resolve(name), downloadsDir);
        if (Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS)) {
            return expected;
        }
        Path folder = expected.getParent();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        Pattern renamed = Pattern.compile(Pattern.quote(dot > 0 ? name.substring(0, dot) : name)
                + "_\\d+" + Pattern.quote(dot > 0 ? name.substring(dot) : ""));
        try (Stream<Path> files = Files.list(folder)) {
            // The suffix is DateTime.UtcNow.Ticks, fixed width for millennia: the newest sorts last.
            return files.filter(p -> renamed.matcher(p.getFileName().toString()).matches())
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        }
    }

    /** slskd's incomplete layout: {@code <incomplete>/<username>/<full remote folder path>/<file>}. */
    Path partialPath(String username, String remoteFilename) {
        List<String> user = username == null ? List.of() : segments(username);
        List<String> segments = segments(remoteFilename);
        if (user.size() != 1 || segments.isEmpty()) {
            return null;
        }
        Path path = incompleteDir.resolve(user.getFirst());
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return guard(path, incompleteDir);
    }

    Path targetFolder(Job job) {
        String artist;
        String folder;
        if (job.type() == DownloadType.ALBUM) {
            artist = first(job.collectionArtists(), "Unknown Artist");
            folder = orElse(job.collectionTitle(), "Unknown Album");
        } else if (job.albumTitle() != null && !job.albumTitle().isBlank()) {
            // Its album's folder, so it joins that album's other songs: Jellyfin makes one album per folder.
            artist = first(job.albumArtists(), "Unknown Artist");
            folder = job.albumTitle();
        } else {
            // A song, or one track of a playlist. Playlist tracks are NOT kept together in one folder:
            // Jellyfin would show that folder as an album named after whichever track came first.
            artist = first(job.songArtists(), "Unknown Artist");
            folder = orElse(job.songTitle(), stem(baseName(job.slskdFilename())));
        }
        return guard(root.resolve(sanitise(artist)).resolve(sanitise(folder)), root);
    }

    /** Never overwrite: {@code song.flac} taken means {@code song (2).flac}, then {@code (3)}... */
    static Path unique(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return target;
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            Path candidate = target.resolveSibling(stem + " (" + n + ")" + ext);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
    }

    /** A regular file (never a symlink) inside the library root. */
    private boolean inLibrary(String path) {
        if (path == null) {
            return false;
        }
        Path file = Path.of(path).normalize();
        return file.startsWith(root) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    /** @return true if the directory was empty and is now gone */
    private static boolean deleteIfEmpty(Path dir, Path base) {
        if (dir == null || dir.equals(base) || !dir.startsWith(base)) {
            return false;
        }
        try {
            Files.delete(dir);
            return true;
        } catch (DirectoryNotEmptyException | NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            log.debug("Could not remove empty folder {}", dir, e);
            return false;
        }
    }

    // ---- pure path logic -------------------------------------------------------------------------

    /**
     * Extended M3U, UTF-8 without BOM, LF, forward slashes, entries relative to the folder holding the
     * file (both servers join against that folder; absolute container paths break when a mount moves).
     * {@code #PLAYLIST} names it in Navidrome (Jellyfin uses the file name); {@code #EXTINF} is a
     * human-readable comment both servers ignore in favour of the tracks' own tags.
     */
    static String m3u8(String title, List<Entry> entries, Path playlistFolder) {
        StringBuilder out = new StringBuilder("#EXTM3U\n#PLAYLIST:").append(oneLine(title)).append('\n');
        for (Entry entry : entries) {
            Path file = Path.of(entry.libraryPath());
            String relative = playlistFolder.relativize(file).toString().replace(File.separatorChar, '/');
            String stem = stem(file.getFileName().toString());
            String label = first(entry.artists(), null) == null
                    ? orElse(entry.title(), stem)
                    : entry.artists().getFirst() + " - " + orElse(entry.title(), stem);
            out.append("#EXTINF:").append(entry.durationSeconds() == null ? -1 : entry.durationSeconds())
                    .append(',').append(oneLine(label)).append('\n')
                    .append(Normalizer.normalize(relative, Normalizer.Form.NFC)).append('\n');
        }
        return out.toString();
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\p{Cntrl}+", " ").strip();
    }

    /**
     * The remote path as slskd would lay it out locally: split on both separators (Soulseek paths
     * are Windows-style), drop the {@code @@xxxxx} share root, drive letters, and any {@code .}/
     * {@code ..} that could climb out of a base folder.
     */
    static List<String> segments(String remotePath) {
        List<String> out = new ArrayList<>();
        if (remotePath == null) {
            return out;
        }
        for (String segment : SEPARATORS.split(remotePath)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.startsWith("@@") || DRIVE.matcher(segment).matches()) {
                continue;
            }
            out.add(segment);
        }
        return out;
    }

    static String baseName(String remotePath) {
        List<String> segments = segments(remotePath);
        return segments.isEmpty() ? "Unknown" : segments.getLast();
    }

    /**
     * One folder or file-name component that is legal on Linux, Windows and macOS, and that Jellyfin
     * will not skip: a colon before a space or at the end to {@code  -} (so "Road Trip: Summer" reads
     * "Road Trip - Summer" where the file name is the title, as in Jellyfin's playlist list; "4:44" is a
     * time, not a subtitle, and keeps the {@code _} rule), the other illegal characters to {@code _},
     * whitespace collapsed, no leading or trailing dots, no Windows device names, at most 200 bytes of
     * UTF-8, NFC.
     */
    static String sanitise(String name) {
        if (name == null) {
            return "Unknown";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFC);
        s = s.replaceAll(":(?=\\s|$)", " -");
        s = ILLEGAL.matcher(s).replaceAll("_");
        s = s.replaceAll("\\s+", " ").strip();
        s = s.replaceAll("[. ]+$", "").replaceAll("^\\.+", "");
        if (s.isBlank()) {
            return "Unknown";
        }
        if (RESERVED.matcher(s).matches()) {
            s = "_" + s;
        }
        return truncateUtf8(s, MAX_COMPONENT_BYTES);
    }

    /** {@link #sanitise} applied to the stem only, so the extension survives intact. */
    static String sanitiseFileName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return sanitise(name);
        }
        return sanitise(name.substring(0, dot)) + ILLEGAL.matcher(name.substring(dot)).replaceAll("_");
    }

    static String truncateUtf8(String s, int maxBytes) {
        int end = 0;
        int bytes = 0;
        while (end < s.length()) {
            int cp = s.codePointAt(end);
            int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + n > maxBytes) {
                break;
            }
            bytes += n;
            end += Character.charCount(cp);
        }
        return end == s.length() ? s : s.substring(0, end).strip();
    }

    /** Every constructed path is checked against the folder it must stay inside. */
    static Path guard(Path path, Path base) {
        Path normalised = path.normalize();
        if (!normalised.startsWith(base)) {
            throw new IllegalStateException("Refusing to touch " + normalised + " outside " + base);
        }
        return normalised;
    }

    private static boolean overlaps(Path a, Path b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    /**
     * Symlinks resolved through the deepest ancestor that exists, so the overlap check compares real
     * locations even for a library root that has not been created yet.
     */
    private static Path real(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException e) {
            Path parent = absolute.getParent();
            return parent == null ? absolute : real(parent).resolve(absolute.getFileName());
        }
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String first(List<String> values, String fallback) {
        return values == null || values.isEmpty() || values.getFirst() == null
                || values.getFirst().isBlank() ? fallback : values.getFirst();
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
