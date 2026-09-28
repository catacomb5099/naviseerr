package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor.Pick;

import java.util.Optional;

/**
 * One (peer, file) candidate, flattened into a persistable shape. Deliberately not
 * {@code Map.Entry<SearchResponseItem, SearchFile>}: that does not serialise cleanly and carries far
 * more of the slskd response than resuming needs.
 */
public record DownloadCandidate(
        String username,
        String filename,
        String extension,
        Integer bitRate,
        long size,
        long code,
        Boolean isLocked,
        /**
         * {@code TrackMatchingService.Match} name: "EXACT" or "OTHER_VERSION", so the database shows
         * when a song was fetched as a live take or remix because the requested version was not shared.
         * Null on rows written before 28-09-2026.
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
                pick.grade().name());
    }

    public SearchFile toSearchFile() {
        return new SearchFile(filename, size, code, isLocked, extension, Optional.ofNullable(bitRate), Optional.empty());
    }
}
