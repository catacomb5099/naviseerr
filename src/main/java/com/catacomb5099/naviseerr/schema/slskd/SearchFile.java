package com.catacomb5099.naviseerr.schema.slskd;

import java.util.Optional;

public class SearchFile {
    String filename;
    long size;
    long code;
    Boolean isLocked;
    String extension;
    Optional<Integer> bitRate;

    public SearchFile(String filename, long size, long code, Boolean isLocked, String extension, Optional<Integer> bitRate) {
        this.filename = filename;
        this.size = size;
        this.code = code;
        this.isLocked = isLocked;
        this.extension = extension;
        this.bitRate = bitRate;
    }

    public String getFilename() {
        return filename;
    }
    public String getExtension() {
        return extension;
    }

    public Optional<Integer> getBitRate() {
        return bitRate;
    }
}
