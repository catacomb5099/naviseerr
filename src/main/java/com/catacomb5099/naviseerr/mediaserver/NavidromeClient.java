package com.catacomb5099.naviseerr.mediaserver;

import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>Which Navidrome: the install file's lines at boot, then whatever the Settings page saves
 * ({@link #reconfigure}), live; {@link #reset} goes back to the install file. {@link #test} is the
 * Settings page's "Save and test": one rescan with the typed values, nothing kept.
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

    /** One Navidrome as reached: address, login and the client for it, in one object so a change is atomic. */
    private record Connection(String url, String username, String password, WebClient http) {
        static Connection to(String url, String username, String password, Duration timeout) {
            String base = MediaServerConfig.baseUrl(url);
            return new Connection(base, username == null ? "" : username.strip(), password == null ? "" : password,
                    MediaServerConfig.http(base, timeout).build());
        }
    }

    private final Duration timeout;
    /** The install file's {@code NAVIDROME_URL} / {@code NAVIDROME_USER} / {@code NAVIDROME_PASSWORD}: the fallback. */
    private final Connection installFile;
    private volatile Connection current;

    public NavidromeClient(@Value("${media-servers.navidrome.url}") String url,
                           @Value("${media-servers.navidrome.username}") String username,
                           @Value("${media-servers.navidrome.password}") String password,
                           @Value("${media-servers.timeout-ms}") Duration timeout) {
        this.timeout = timeout;
        this.installFile = Connection.to(url, username, password, timeout);
        this.current = installFile;
    }

    /** Talk to this Navidrome from now on (what the Settings page saved). */
    public void reconfigure(String url, String username, String password) {
        current = Connection.to(url, username, password, timeout);
    }

    /** Back to the install file's lines, or to none. */
    public void reset() {
        current = installFile;
    }

    /** A URL is set (in the app or the install file). */
    public boolean isConfigured() {
        return !MediaServerConfig.UNSET.equals(current.url());
    }

    /** Both a username and a password are set. */
    public boolean hasCredentials() {
        return !current.username().isBlank() && !current.password().isEmpty();
    }

    public String url() {
        return current.url();
    }

    public String username() {
        return current.username();
    }

    /**
     * {@code GET /rest/startScan}, incremental (Navidrome's default; {@code fullScan} would re-read every
     * file). Completes when Navidrome accepted the request; it scans in the background. A scan already
     * running is "ok" too (Navidrome drops the request), which the notifier's trailing resend covers.
     */
    public Mono<Void> rescan() {
        return rescan(current);
    }

    /** "Save and test": one rescan with these values, which proves the address, the login and that it is an admin. Nothing kept. */
    public Mono<Void> test(String url, String username, String password) {
        return rescan(Connection.to(url, username, password, timeout));
    }

    private Mono<Void> rescan(Connection c) {
        byte[] saltBytes = new byte[8];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return c.http().get()
                .uri(b -> b.path("/rest/startScan")
                        .queryParam("u", "{u}").queryParam("t", "{t}").queryParam("s", "{s}")
                        .queryParam("v", SUBSONIC_VERSION).queryParam("c", CLIENT_NAME).queryParam("f", "json")
                        .build(c.username(), token(c.password(), salt), salt))
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
                        error -> MediaServerException.transport("Navidrome", c.url(), error));
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
            case WRONG_CREDENTIALS -> "Navidrome refused the username or password.";
            case NOT_AUTHORISED -> "Navidrome only lets an admin start a scan: use an admin account.";
            default -> "Navidrome refused the rescan: " + message + " (Subsonic error " + code + ")";
        };
    }
}
