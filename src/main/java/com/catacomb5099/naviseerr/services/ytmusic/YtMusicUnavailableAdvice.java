package com.catacomb5099.naviseerr.services.ytmusic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Every route that reads YouTube Music live answers a provider outage the same way: 502 with
 * {@code {reason, message}}, where {@code message} is the sentence the web app prints. Until
 * 19-10-2026 five controllers each had their own copy of this handler, all answering an empty 502,
 * so the web app could only guess from the URL ("Search is not available") and never named YouTube
 * Music. A controller's own {@code @ExceptionHandler} for this type would win over this one, so
 * none may be added.
 *
 * <p>Logged once, at WARN, without a stack trace: a dependency being down is expected weather, and
 * the Netty trace that used to follow it told a self-hoster nothing the message does not.
 */
@Slf4j
@RestControllerAdvice
public class YtMusicUnavailableAdvice {
    static final String UNAVAILABLE = "YOUTUBE_MUSIC_UNAVAILABLE";
    static final String RATE_LIMITED = "YOUTUBE_MUSIC_RATE_LIMITED";
    static final String UNAVAILABLE_MESSAGE = "YouTube Music is not answering right now. Try again in a minute.";
    static final String RATE_LIMITED_MESSAGE = "YouTube Music is rate-limiting this server. Wait a few minutes, then try again.";

    @ExceptionHandler(YtMusicUnavailableException.class)
    ResponseEntity<Map<String, String>> handleUnavailable(YtMusicUnavailableException ex) {
        log.warn("YouTube Music {}: {}", ex.isRateLimited() ? "is rate-limiting this server" : "is not answering", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "reason", ex.isRateLimited() ? RATE_LIMITED : UNAVAILABLE,
                "message", ex.isRateLimited() ? RATE_LIMITED_MESSAGE : UNAVAILABLE_MESSAGE));
    }
}
