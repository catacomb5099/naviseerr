package com.catacomb5099.naviseerr.services.ytmusic;

import lombok.Getter;

/**
 * The adapter or upstream YouTube Music failed, rate-limited, or timed out, or the request
 * never reached the adapter at all (connection refused / client-side timeout). Retryable, except
 * when {@link #isRateLimited() rate-limited}: the adapter answered 429, and asking again at once is
 * what a rate limit punishes.
 */
@Getter
public class YtMusicUnavailableException extends YtMusicException {
    private final boolean rateLimited;

    public YtMusicUnavailableException(String message) {
        this(message, false);
    }

    public YtMusicUnavailableException(String message, boolean rateLimited) {
        super(message);
        this.rateLimited = rateLimited;
    }

    public YtMusicUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.rateLimited = false;
    }
}
