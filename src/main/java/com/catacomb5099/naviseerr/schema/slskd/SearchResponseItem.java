package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class SearchResponseItem {
    int fileCount;
    List<SearchFile> files;
    Boolean hasFreeUploadsSlot;
    int lockedFileCount;
    List<SearchFile> lockedFiles;
    int queueLength;
    int token;
    int uploadSpeed;
    String username;
}
