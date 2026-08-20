package com.catacomb5099.naviseerr.services.ytmusic;

/**
 * We sent the adapter something it rejected (its {@code invalid_request}/{@code 422}/
 * {@code internal_auth_misuse} cases). Non-retryable -- retrying an identical request
 * will fail identically.
 */
public class YtMusicBadRequestException extends YtMusicException {
    public YtMusicBadRequestException(String message) {
        super(message);
    }
}
