package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;

import java.util.Map;
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
        Integer uploadSpeed) {

    public static DownloadCandidate from(Map.Entry<SearchResponseItem, SearchFile> entry) {
        SearchFile file = entry.getValue();
        return new DownloadCandidate(
                entry.getKey().getUsername(),
                file.getFilename(),
                file.getExtension(),
                file.getBitRate().orElse(null),
                file.getSize(),
                file.getCode(),
                file.getIsLocked(),
                entry.getKey().getHasFreeUploadsSlot(),
                entry.getKey().getQueueLength(),
                entry.getKey().getUploadSpeed());
    }

    public SearchFile toSearchFile() {
        return new SearchFile(filename, size, code, isLocked, extension, Optional.ofNullable(bitRate), Optional.empty());
    }
}
