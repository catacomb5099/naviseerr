package com.catacomb5099.naviseerr.services.slskd;

/**
 * The download quality a person chooses (the setup's first screen, the first section of Settings; owner's
 * ask of 19-10-2026): Spotify's Audio quality list, Low to Lossless, and no format choice. One setting for
 * every download: songs, playlists and albums alike. Stored as the word in {@code install_settings.download_quality}
 * (V19), null until chosen. Lowest first, so the order of the values is the order of the tiers.
 */
public enum DownloadQuality {
    /** About 128 kbit/s. */
    LOW,
    /** About 192 kbit/s. */
    NORMAL,
    /** About 256 kbit/s. */
    HIGH,
    /** About 320 kbit/s. */
    VERY_HIGH,
    /** FLAC and the other lossless formats. */
    LOSSLESS;

    /**
     * What applies until someone chooses. Until 19-10-2026 the picker took lossless and 320 kbps files as
     * equals and let the quickest sharer decide, landing on lossless 61% of the time (437 of 715 songs of the
     * search lab); Lossless is the nearest single tier, so nothing changes for anyone who never chooses.
     */
    public static final DownloadQuality DEFAULT = LOSSLESS;
}
