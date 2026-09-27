package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * </pre>
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
    static final String PLAYLISTS_FOLDER = "Playlists";

    private static final Pattern SEPARATORS = Pattern.compile("[\\\\/]+");
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:$");
    // Windows-illegal characters plus control characters; the strictest rule set, so the library is
    // safe to share over SMB. slskd on Linux does not do this for us (only NUL and '/').
    private static final Pattern ILLEGAL = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Pattern RESERVED =
            Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(\\..*)?$");
    private static final int MAX_COMPONENT_BYTES = 200;

    /** One finished song to file, joined with the names its folders are built from. */
    public record Job(UUID taskId, DownloadType type, String slskdFilename, Instant finishedAt,
                      String songTitle, List<String> songArtists,
                      String collectionTitle, List<String> collectionArtists) {}

    private final Path downloadsDir;
    private final Path incompleteDir;
    private final Path root;
    private final Duration loopInterval;
    private final boolean enabled;

    public LibraryOrganiser(
            @Value("${library.slskd-downloads-dir:}") String downloadsDir,
            @Value("${library.slskd-incomplete-dir:}") String incompleteDir,
            @Value("${library.root:}") String root,
            @Value("${download-task.loop-interval-ms:2000}") Duration loopInterval) {
        this.loopInterval = loopInterval;
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

    /**
     * Moves one song's file into the library.
     *
     * @return the file's new absolute path, or empty when the file is not in the downloads folder
     *         (slskd may still be moving it out of incomplete; the row is left for the next pass)
     */
    public Mono<Path> file(Job job, Instant now) {
        return Mono.fromCallable(() -> fileBlocking(job, now))
                .subscribeOn(Schedulers.boundedElastic());
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

    // ---- blocking I/O ----------------------------------------------------------------------------

    Path fileBlocking(Job job, Instant now) throws IOException {
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
        Path folder = targetFolder(job);
        Files.createDirectories(folder);
        Path target = unique(folder.resolve(sanitiseFileName(baseName(job.slskdFilename()))));
        // Staged under a non-audio name first: across filesystems Files.move is a copy then a
        // delete, and a scanner must never index the half-copied file. The final step is a rename
        // within one folder, which is atomic.
        Path staging = target.resolveSibling(target.getFileName() + ".partial");
        Files.move(source, staging, StandardCopyOption.REPLACE_EXISTING);
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
     * (slskd's default {@code ${SOURCE_DIRECTORY}} rule). If that path is missing -- a different
     * subdirectory pattern, or a name collision that made slskd append {@code _<ticks>} -- the
     * downloads folder is searched a few levels deep for the same name, exact match preferred,
     * then the newest.
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
        if (!Files.isDirectory(downloadsDir)) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        Pattern sameOrRenamed = Pattern.compile(Pattern.quote(stem) + "(_\\d+)?" + Pattern.quote(ext));
        try (Stream<Path> files = Files.walk(downloadsDir, 3)) {
            return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> sameOrRenamed.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparing((Path p) -> p.getFileName().toString().equals(name))
                            .thenComparing(LibraryOrganiser::lastModified))
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
        } else {
            // A song, or one track of a playlist. Playlist tracks are NOT kept together in one folder:
            // Jellyfin would show that folder as an album named after whichever track came first.
            // The playlist itself is one .m3u8 in root/Playlists pointing at these files.
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
     * will not skip: illegal characters to {@code _}, whitespace collapsed, no leading or trailing
     * dots, no Windows device names, at most 200 bytes of UTF-8, NFC.
     */
    static String sanitise(String name) {
        if (name == null) {
            return "Unknown";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFC);
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

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
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
