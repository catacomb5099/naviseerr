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
 * <p>A preference, not a floor: the picker takes the chosen tier first and, when that has nothing, every
 * higher tier before any lower one, nearest first either way ({@link #distance}); the song's Choose-a-file pop-up ({@code GET /downloads/{id}/candidates},
 * the person icon on a Downloads row) shows every file's quality and marks the one taken.
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
     * Where {@code tier} comes in this chosen one's fallback order: 0 for the tier itself, then every higher
     * tier, nearest first, and only then the lower tiers, nearest first (the owner's call of 20-10-2026:
     * "check all higher options before going lower"). High: High, Very high, Lossless, Normal, Low; Normal:
     * Normal, High, Very high, Lossless, Low; Lossless: Lossless, Very high, High, Normal, Low. A lower
     * tier's place is its distance from the top, which is past every higher tier's whatever was chosen.
     * Until 20-10 the nearest tier won, ties going up (High: High, Very high, Normal, Lossless, Low).
     */
    public int distance(DownloadQuality tier) {
        int up = tier.ordinal() - ordinal();
        return up >= 0 ? up : values().length - 1 - tier.ordinal();
    }
}
