package com.catacomb5099.naviseerr.schema.slskd;

import java.util.List;

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

    public SearchResponseItem(int fileCount, List<SearchFile> files, Boolean hasFreeUploadsSlot, int lockedFileCount, List<SearchFile> lockedFiles, int queueLength, int token, int uploadSpeed, String username) {
        this.fileCount = fileCount;
        this.files = files;
        this.hasFreeUploadsSlot = hasFreeUploadsSlot;
        this.lockedFileCount = lockedFileCount;
        this.lockedFiles = lockedFiles;
        this.queueLength = queueLength;
        this.token = token;
        this.uploadSpeed = uploadSpeed;
        this.username = username;
    }

    public int getFileCount() {
        return fileCount;
    }

    public List<SearchFile> getFiles() {
        return files;
    }

    public Boolean getHasFreeUploadsSlot() {
        return hasFreeUploadsSlot;
    }

    public int getLockedFileCount() {
        return lockedFileCount;
    }

    public List<SearchFile> getLockedFiles() {
        return lockedFiles;
    }

    public int getQueueLength() {
        return queueLength;
    }

    public int getToken() {
        return token;
    }

    public int getUploadSpeed() {
        return uploadSpeed;
    }

    public String getUsername() {
        return username;
    }
}
