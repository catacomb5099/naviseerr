package com.catacomb5099.naviseerr.schema.slskd;

public class SearchFile {
    String filename;
    int size;
    int code;
    Boolean isLocked;
    String extension;

    public SearchFile(String filename, int size, int code, Boolean isLocked, String extension) {
        this.filename = filename;
        this.size = size;
        this.code = code;
        this.isLocked = isLocked;
        this.extension = extension;
    }

    public String getFilename() {
        return filename;
    }

    public int getSize() {
        return size;
    }

    public int getCode() {
        return code;
    }

    public Boolean getLocked() {
        return isLocked;
    }

    public String getExtension() {
        return extension;
    }
}
