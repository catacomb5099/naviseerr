package com.catacomb5099.naviseerr.mediaserver;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

/**
 * One plain sentence about why a music server could not be asked to do something: shown in
 * {@code GET /status} and logged once. Never carries the password, the API key or a request URL
 * (a Subsonic URL holds the auth token).
 */
public class MediaServerException extends RuntimeException {

    public MediaServerException(String message) {
        super(message);
    }

    /** The transport-level reason, without the URL WebClient puts in its own messages. */
    static String reason(Throwable error) {
        return switch (error) {
            case WebClientResponseException http -> "HTTP " + http.getStatusCode().value();
            case TimeoutException t -> "no answer in time";
            case WebClientRequestException request -> request.getCause() != null && request.getCause().getMessage() != null
                    ? request.getCause().getMessage() : request.getMessage();
            default -> error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        };
    }
}
