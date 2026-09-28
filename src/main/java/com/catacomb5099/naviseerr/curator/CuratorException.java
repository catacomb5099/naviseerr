package com.catacomb5099.naviseerr.curator;

/**
 * A curator call failed. {@code retryable} is the one thing callers need to know: true for a timeout,
 * connection refused or 5xx (try again), false for any 4xx (the request itself is wrong -- most
 * importantly a 401, which means the two CURATOR_TOKEN values do not match). {@code status} is the
 * HTTP status the curator answered with, or 0 when it never answered (timeout, connection refused).
 */
public class CuratorException extends RuntimeException {
    private final boolean retryable;
    private final int status;

    public CuratorException(String message, boolean retryable) {
        this(message, retryable, 0);
    }

    public CuratorException(String message, boolean retryable, int status) {
        super(message);
        this.retryable = retryable;
        this.status = status;
    }

    public CuratorException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.status = 0;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** The curator's HTTP status, or 0 when the request never got an answer. */
    public int getStatus() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
