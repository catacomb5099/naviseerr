package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor.Pick;

import java.util.Optional;

/**
 * One (peer, file) candidate, flattened into a persistable shape. Deliberately not
 * {@code Map.Entry<SearchResponseItem, SearchFile>}: that does not serialise cleanly and carries far
 * more of the slskd response than resuming needs.
 *
 * <p>The last three fields are what the sharer advertised about itself at search time. They are
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
        String grade) {

    public static DownloadCandidate from(Pick pick) {
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
                pick.grade().name());
    }

    public SearchFile toSearchFile() {
        return new SearchFile(filename, size, code, isLocked, extension, Optional.ofNullable(bitRate), Optional.empty());
    }
}
