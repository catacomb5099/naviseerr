package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;

import java.util.Optional;
import java.util.Set;

/**
 * The download quality a person chooses (the setup's first screen, the first section of Settings; owner's
 * ask of 19-10-2026): Spotify's Audio quality list, Low to Lossless, and no format choice. One setting for
 * every download: songs, playlists and albums alike. Stored as the word in {@code install_settings.download_quality}
 * (V19), null until chosen. Lowest first, so the order of the values is the order of the tiers.
 *
 * <p>A preference, not a floor: the picker takes the chosen tier first and the nearest tier when that has
 * nothing, ties going up ({@link #distance}), and the Downloads page's Quality column shows what was taken.
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

    /** Judged by the file name's suffix: slskd reports no bit rate for these (13,845 of 14,188 FLAC files on 04-10-2026). */
    private static final Set<String> LOSSLESS_FORMATS = Set.of("flac", "wav", "aif", "aiff", "ape", "wv");

    /** The tier a shared file is in; empty for a lossy file whose bit rate slskd did not report (2.7% of files, mostly m4a and opus). */
    public static Optional<DownloadQuality> of(SearchFile file) {
        return of(file.getFilename(), file.getExtension(), file.getBitRate().orElse(null));
    }

    /**
     * The same from a candidate's stored fields. A VBR file's {@code bitRate} is its average, so it lands in
     * the nearest tier by itself (LAME V0 at 260-271 is High, V2 at 190-205 is Normal). The boundaries are
     * the midpoints between the MP3 rungs measured on 44,008 real files (128, 192, 256, 320); 160 is
     * equidistant between 128 and 192 and goes up.
     */
    public static Optional<DownloadQuality> of(String filename, String extension, Integer bitRate) {
        if (LOSSLESS_FORMATS.contains(SlskdSearchResultProcessor.format(filename, extension))) {
            return Optional.of(LOSSLESS);
        }
        if (bitRate == null || bitRate <= 0) {
            return Optional.empty();
        }
        return Optional.of(bitRate >= 288 ? VERY_HIGH : bitRate >= 224 ? HIGH : bitRate >= 160 ? NORMAL : LOW);
    }

    /**
     * How far {@code tier} is from this chosen one: 0 for the tier itself, then the neighbours, the one above
     * nearer than the one below (ties go up). Normal: Normal, High, Low, Very high, Lossless; Lossless:
     * Lossless, Very high, High, Normal, Low.
     */
    public int distance(DownloadQuality tier) {
        return Math.abs(tier.ordinal() - ordinal()) * 2 + (tier.ordinal() < ordinal() ? 1 : 0);
    }
}
