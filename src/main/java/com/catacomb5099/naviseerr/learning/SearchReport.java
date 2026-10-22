package com.catacomb5099.naviseerr.learning;

import com.catacomb5099.naviseerr.download.DownloadCandidate;
import com.catacomb5099.naviseerr.download.SearchLogEntry;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * What one successful search taught (search learning, 20-10-2026; schema 1): the song as YouTube Music has it, every
 * search tried with its counts, the search whose file was kept and that file. Never a Soulseek user name, the sharer's
 * stats, the library folder or any address: {@link #of} builds it from the chosen file and drops those.
 */
public record SearchReport(UUID id, Instant finishedAt, String type, Song song, List<SearchLogEntry> searches,
                           Won won, File file, String quality) {

    public record Song(String youtubeId, String title, List<String> artists, String album, Integer seconds, String name) {}

    /** {@code source}: SEARCH (the loop picked it), ALBUM_FOLDER (the album's whole-folder search) or MANUAL (a person chose it). */
    public record Won(String wording, String source) {}

    /** {@code attempt}: 1 when the first file tried was the one kept. */
    public record File(String path, String extension, Integer bitRate, Integer seconds, long size, String grade, int attempt) {}

    static SearchReport of(UUID id, Instant finishedAt, String type, Song song, List<SearchLogEntry> searches,
                           DownloadCandidate chosen, String albumWording, int attempt, String quality) {
        String source = chosen.source() == null ? "SEARCH" : chosen.source();
        String wording = chosen.wording() != null ? chosen.wording()
                : DownloadCandidate.ALBUM_FOLDER.equals(chosen.source()) ? albumWording : null;
        return new SearchReport(id, finishedAt, type, song, searches, new Won(wording, source),
                new File(shortPath(chosen.filename(), chosen.username()), chosen.extension(), chosen.bitRate(),
                        chosen.length(), chosen.size(), chosen.grade(), attempt),
                quality);
    }

    /**
     * The last two folders and the file name, without the sharer's share root ({@code @@music}) or a folder named
     * like the sharer: Soulseek paths start with both, and the rest of the path says what the file is.
     */
    static String shortPath(String path, String username) {
        List<String> parts = Arrays.stream(path.split("[\\\\/]"))
                .filter(part -> !part.isBlank() && !part.startsWith("@@") && !part.equalsIgnoreCase(username))
                .toList();
        return String.join("/", parts.subList(Math.max(0, parts.size() - 3), parts.size()));
    }
}
