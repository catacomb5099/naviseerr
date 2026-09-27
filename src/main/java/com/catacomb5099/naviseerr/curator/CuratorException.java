package com.catacomb5099.naviseerr.curator;

/**
 * A curator call failed. {@code retryable} is the one thing callers need to know: true for a timeout,
 * connection refused or 5xx (try again), false for any 4xx (the request itself is wrong -- most
 * importantly a 401, which means the two CURATOR_TOKEN values do not match).
 */
public class CuratorException extends RuntimeException {
    private final boolean retryable;

    public CuratorException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public CuratorException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
