package com.catacomb5099.naviseerr.mediaserver;

/** The media servers a player can ask naviseerr about, as players name them in requests. */
public enum MediaServer {
    NAVIDROME("Navidrome"), JELLYFIN("Jellyfin");

    public final String displayName;

    MediaServer(String displayName) {
        this.displayName = displayName;
    }
}
