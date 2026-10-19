package com.catacomb5099.naviseerr.settings;

import java.time.Instant;

/**
 * The one {@code install_settings} row (V18): what the first-run setup asked. Never serialised as such
 * (the Navidrome password, the Jellyfin key and the slskd key must not leave the server); each
 * settings controller has its own view record. A null column means "not set in the app": the install
 * file's value, when it has one, is what applies.
 */
public record InstallSettings(Instant setupCompletedAt, boolean suggestedPlaylists,
                              String navidromeUrl, String navidromeUser, String navidromePassword,
                              String jellyfinUrl, String jellyfinApiKey,
                              String slskdUrl, String slskdApiKey) {

    /** What an install that never saved anything looks like: every answer is the default. */
    public static final InstallSettings NONE = new InstallSettings(null, false, null, null, null, null, null, null, null);

    public boolean setupCompleted() {
        return setupCompletedAt != null;
    }
}
