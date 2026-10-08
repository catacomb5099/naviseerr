package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor.Pick;
import com.catacomb5099.naviseerr.util.TrackMatchingService;

import java.util.Optional;

/**
 * One (peer, file) candidate, flattened into a persistable shape. Deliberately not
 * {@code Map.Entry<SearchResponseItem, SearchFile>}: that does not serialise cleanly and carries far
 * more of the slskd response than resuming needs.
 *
 * <p>The sharer fields are what the sharer advertised about itself at search time. They are
 * kept only so the database shows WHY a sharer was ranked where it was after the fact (the
 * 27-09-2026 post-mortem could not tell whether the sharer that stalled 40 transfers had claimed a
 * free slot). Boxed and nullable: rows written before they existed read back as null, no migration.
 */
public record DownloadCandidate(
        String username,
        String filename,
        String extension,
        Integer bitRate,
        long size,
        long code,
        Boolean isLocked,
        Boolean hasFreeUploadSlot,
        Integer queueLength,
        Integer uploadSpeed,
        /**
         * {@code TrackMatchingService.Match} name: "EXACT", "OTHER_VERSION" or "UNVERIFIED", so the
         * database shows when a song was fetched as a live take or remix because the requested version
         * was not shared, or from a file whose path never named the artist because nothing better was
         * found. Null on rows written before 28-09-2026.
         */
        String grade,
        /**
         * {@link #ALBUM_FOLDER} when the file came from one sharer's whole-album folder rather than from
         * the song's own search, {@link #MANUAL} when a person chose it; null otherwise. A song whose
         * album-folder files all fail goes back to its own search instead of failing (P5). Null on every
         * row written before 04-10-2026.
         */
        String source,
        /**
         * The file's length in seconds as slskd reported it, so the manual picker can show it; null when
         * slskd gave none (3.5% of files) and on rows written before 07-10-2026.
         */
        Integer length) {

    /** The candidate came from the album search's whole folder (P5), not from the song's own search. */
    public static final String ALBUM_FOLDER = "ALBUM_FOLDER";
    /** A person chose this file (manual pick, 07-10-2026); it is the song's only candidate. */
    public static final String MANUAL = "MANUAL";

    public static DownloadCandidate from(Pick pick) {
        return from(pick, null);
    }

    /** One track's file in a whole-album folder; always the requested version (EXACT). */
    public static DownloadCandidate fromAlbumFolder(SearchResponseItem peer, SearchFile file) {
        return from(new Pick(peer, file, TrackMatchingService.Match.EXACT), ALBUM_FOLDER);
    }

    private static DownloadCandidate from(Pick pick, String source) {
        SearchFile file = pick.file();
        return new DownloadCandidate(
                pick.peer().getUsername(),
                file.getFilename(),
                file.getExtension(),
                file.getBitRate().orElse(null),
                file.getSize(),
                file.getCode(),
                file.getIsLocked(),
                pick.peer().getHasFreeUploadSlot(),
                pick.peer().getQueueLength(),
                pick.peer().getUploadSpeed(),
                pick.grade().name(),
                source,
                file.getLength() == null ? null : file.getLength().orElse(null));
    }

    public SearchFile toSearchFile() {
        return new SearchFile(filename, size, code, isLocked, extension, Optional.ofNullable(bitRate),
                Optional.ofNullable(length));
    }
}
