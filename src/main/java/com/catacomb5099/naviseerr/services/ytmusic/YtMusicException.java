package com.catacomb5099.naviseerr.services.ytmusic;

/**
 * Base type for ytmusic-adapter provider failures.
 *
 * Per AGENTS.md ("Model external-provider failures explicitly ... report 'no good match'
 * distinctly from provider errors, timeouts, and cancellations"): a genuinely empty result
 * (HTTP 200, {@code count: 0}) is NOT an error and never reaches this hierarchy -- it maps
 * to empty lists on {@link com.catacomb5099.naviseerr.schema.response.SearchResponse}.
 */
public class YtMusicException extends RuntimeException {
    public YtMusicException(String message) {
        super(message);
    }

    public YtMusicException(String message, Throwable cause) {
        super(message, cause);
    }
}
