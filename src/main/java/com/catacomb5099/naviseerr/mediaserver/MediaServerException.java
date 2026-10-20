package com.catacomb5099.naviseerr.mediaserver;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * One plain sentence about why a music server could not be asked to do something: shown in
 * {@code GET /status}, as the Settings page's "Save and test" answer, and logged once. Never carries
 * the password, the API key or a request URL (a Subsonic URL holds the auth token). {@link #transport}
 * words the "cannot reach it" case for any server the Settings page tests, slskd included.
 */
public class MediaServerException extends RuntimeException {

    public MediaServerException(String message) {
        super(message);
    }

    /**
     * A call that failed below the server's own API: it answered an HTTP error ("Jellyfin at http://nas:8096
     * answered HTTP 500.") or nothing at all ("Could not reach nas:8096: Connection refused. Is Jellyfin running,
     * and can this server reach it?"), the sentence a person fixing the address needs.
     */
    public static MediaServerException transport(String server, String url, Throwable error) {
        if (error instanceof WebClientResponseException http) {
            return new MediaServerException(server + " at " + url + " answered HTTP " + http.getStatusCode().value() + ".");
        }
        return new MediaServerException("Could not reach " + host(url) + ": " + reason(error) + ". Is " + server
                + " running, and can this server reach it?");
    }

    /** {@code nas:8096} out of {@code http://nas:8096}; the whole address when it cannot be parsed. */
    static String host(String url) {
        try {
            String authority = URI.create(url).getAuthority();
            return authority == null ? url : authority;
        } catch (IllegalArgumentException odd) {
            return url;
        }
    }

    /** The transport-level reason, without the URL WebClient puts in its own messages. */
    static String reason(Throwable error) {
        return switch (error) {
            case WebClientResponseException http -> "HTTP " + http.getStatusCode().value();
            case TimeoutException t -> "no answer in time";
            case WebClientRequestException request -> cause(request);
            default -> error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        };
    }

    /** Netty's ConnectException repeats the address ("Connection refused: /127.0.0.1:1"); the sentence names it once already. */
    private static String cause(WebClientRequestException request) {
        Throwable cause = request.getCause();
        if (cause instanceof UnknownHostException) return "the name could not be found";
        String text = cause != null && cause.getMessage() != null ? cause.getMessage()
                : request.getMessage() != null ? request.getMessage() : "no connection";
        int address = text.indexOf(": /");
        return address > 0 ? text.substring(0, address) : text;
    }
}
