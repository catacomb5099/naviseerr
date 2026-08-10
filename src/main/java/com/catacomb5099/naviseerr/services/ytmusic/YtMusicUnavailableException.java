package com.catacomb5099.naviseerr.services.ytmusic;

/**
 * The adapter or upstream YouTube Music failed, rate-limited, or timed out, or the request
 * never reached the adapter at all (connection refused / client-side timeout). Retryable.
 */
public class YtMusicUnavailableException extends YtMusicException {
    public YtMusicUnavailableException(String message) {
        super(message);
    }

    public YtMusicUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
