package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;

import java.util.Map;
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
        Boolean isLocked) {

    public static DownloadCandidate from(Map.Entry<SearchResponseItem, SearchFile> entry) {
        SearchFile file = entry.getValue();
        return new DownloadCandidate(
                entry.getKey().getUsername(),
                file.getFilename(),
                file.getExtension(),
                file.getBitRate().orElse(null),
                file.getSize(),
                file.getCode(),
                file.getIsLocked());
    }

    public SearchFile toSearchFile() {
        return new SearchFile(filename, size, code, isLocked, extension, Optional.ofNullable(bitRate));
    }
}
