package com.catacomb5099.naviseerr.mediaserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Navidrome through its Subsonic API: {@code startScan}, the call behind its own "Rescan" button. Auth
 * is the Subsonic token form ({@code t} = md5(password + salt), fresh {@code s} each call), so the
 * password never travels in a URL or lands in a proxy log. Navidrome only lets an admin start a scan
 * ({@code Scanner.AdminOnly}); the error it answers with is turned into a sentence that says so.
 */
@Slf4j
@Service
public class NavidromeClient {

    static final String SUBSONIC_VERSION = "1.16.1";
    static final String CLIENT_NAME = "naviseerr";
    /** Subsonic error codes (subsonic.org/pages/api.jsp): wrong credentials, and not allowed for this user. */
    static final int WRONG_CREDENTIALS = 40;
    static final int NOT_AUTHORISED = 50;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebClient http;
    private final String url;
    private final String username;
    private final String password;
    private final Duration timeout;

    public NavidromeClient(@Qualifier("navidromeWebClient") WebClient http,
                           @Value("${media-servers.navidrome.url}") String url,
                           @Value("${media-servers.navidrome.username}") String username,
                           @Value("${media-servers.navidrome.password}") String password,
                           @Value("${media-servers.timeout-ms}") Duration timeout) {
        this.http = http;
        this.url = MediaServerConfig.baseUrl(url);
        this.username = username == null ? "" : username.strip();
        this.password = password == null ? "" : password;
        this.timeout = timeout;
    }

    /** {@code NAVIDROME_URL} is set. */
    public boolean isConfigured() {
        return !"http://unset.invalid".equals(url);
    }

    /** Both {@code NAVIDROME_USER} and {@code NAVIDROME_PASSWORD} are set. */
    public boolean hasCredentials() {
        return !username.isBlank() && !password.isEmpty();
    }

    public String url() {
        return url;
    }

    /**
     * {@code GET /rest/startScan}, incremental (Navidrome's default; {@code fullScan} would re-read every
     * file). Completes when Navidrome accepted the request; it scans in the background. A scan already
     * running is "ok" too (Navidrome drops the request), which the notifier's trailing resend covers.
     */
    public Mono<Void> rescan() {
        byte[] saltBytes = new byte[8];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return http.get()
                .uri(b -> b.path("/rest/startScan")
                        .queryParam("u", "{u}").queryParam("t", "{t}").queryParam("s", "{s}")
                        .queryParam("v", SUBSONIC_VERSION).queryParam("c", CLIENT_NAME).queryParam("f", "json")
                        .build(username, token(password, salt), salt))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .flatMap(body -> {
                    JsonNode response = body.path("subsonic-response");
                    if ("ok".equals(response.path("status").asString(""))) {
                        return Mono.<Void>empty();
                    }
                    return Mono.error(new MediaServerException(describe(response.path("error"))));
                })
                .onErrorMap(error -> !(error instanceof MediaServerException),
                        error -> new MediaServerException("Navidrome at " + url + " did not answer: "
                                + MediaServerException.reason(error)));
    }

    /** The Subsonic auth token: hex md5 of password + salt. */
    static String token(String password, String salt) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md5.digest((password + salt).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is part of every JDK", e);
        }
    }

    private static String describe(JsonNode error) {
        int code = error.path("code").asInt(0);
        String message = error.path("message").asString("no message");
        return switch (code) {
            case WRONG_CREDENTIALS -> "Navidrome rejected the username or password (NAVIDROME_USER / NAVIDROME_PASSWORD)";
            case NOT_AUTHORISED -> "Navidrome only lets an admin start a scan: make the NAVIDROME_USER account an admin, "
                    + "or start Navidrome with ND_SCANNER_ADMINONLY=false";
            default -> "Navidrome refused the rescan: " + message + " (Subsonic error " + code + ")";
        };
    }
}
