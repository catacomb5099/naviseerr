package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class SearchResponseItem {
    int fileCount;
    List<SearchFile> files;
    // slskd's exact name. Spelt hasFreeUploadsSlot until 04-10-2026, which silently read null for every sharer.
    Boolean hasFreeUploadSlot;
    int lockedFileCount;
    List<SearchFile> lockedFiles;
    int queueLength;
    int token;
    int uploadSpeed;
    String username;
}
