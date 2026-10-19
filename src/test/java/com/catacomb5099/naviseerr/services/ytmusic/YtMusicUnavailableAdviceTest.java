package com.catacomb5099.naviseerr.services.ytmusic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class YtMusicUnavailableAdviceTest {

    private final YtMusicUnavailableAdvice advice = new YtMusicUnavailableAdvice();

    @Test
    void outage_is502_namingYouTubeMusic_loggedOnceAtWarnWithoutAStackTrace(CapturedOutput output) {
        ResponseEntity<Map<String, String>> response = advice.handleUnavailable(
                new YtMusicUnavailableException("ytmusic-adapter request failed: Connection refused", new RuntimeException("Connection refused")));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(Map.of("reason", "YOUTUBE_MUSIC_UNAVAILABLE",
                "message", "YouTube Music is not answering right now. Try again in a minute."), response.getBody());
        assertEquals(1, output.getOut().lines().filter(line -> line.contains("WARN")).count());
        assertTrue(output.getOut().contains("YouTube Music is not answering: ytmusic-adapter request failed: Connection refused"));
        assertFalse(output.getOut().contains("\tat "), "no stack trace for an expected outage");
    }

    @Test
    void rateLimit_is502_sayingSo() {
        ResponseEntity<Map<String, String>> response = advice.handleUnavailable(
                new YtMusicUnavailableException("Upstream rate limit exceeded", true));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(Map.of("reason", "YOUTUBE_MUSIC_RATE_LIMITED",
                "message", "YouTube Music is rate-limiting this server. Wait a few minutes, then try again."), response.getBody());
    }
}
