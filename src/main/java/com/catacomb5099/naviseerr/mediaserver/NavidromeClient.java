package com.catacomb5099.naviseerr.mediaserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

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
    static final int NOT_FOUND = 70;

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
        return call(c, "startScan", Map.of())
                .onErrorMap(SubsonicError.class, e -> new MediaServerException(e.code == NOT_AUTHORISED
                        ? "Navidrome only lets an admin start a scan: use an admin account." : describe("rescan", e)))
                .then();
    }

    /**
     * The song in Navidrome that is the file naviseerr filed ({@link SongFinder#byPathOrTags}): {@code search3} for
     * each of {@link SongFinder#searchTerms}, the first term that gives a pick wins. Empty when none does.
     */
    public Mono<SongFinder.Found> findSong(SongFinder.Wanted wanted) {
        Connection c = current;
        return Flux.fromIterable(SongFinder.searchTerms(wanted))
                .concatMap(term -> call(c, "search3", Map.of("query", term, "songCount", "50", "artistCount", "0",
                        "albumCount", "0"))
                        .mapNotNull(response -> SongFinder.byPathOrTags(wanted, songs(response.path("searchResult3"))).orElse(null)))
                .next()
                .onErrorMap(SubsonicError.class, e -> new MediaServerException(describe("search", e)));
    }

    /**
     * {@code getPlaylist}: its songs, and "not editable" when Navidrome marks it read-only for naviseerr's own
     * account and that account owns it (an imported or smart playlist; for another user's playlist an admin sees
     * read-only too, yet may add to it). Empty when Navidrome has no such playlist.
     */
    public Mono<MediaPlaylist> playlist(String playlistId) {
        Connection c = current;
        return call(c, "getPlaylist", Map.of("id", playlistId))
                .map(response -> {
                    JsonNode playlist = response.path("playlist");
                    boolean ours = c.username().equals(playlist.path("owner").asString(""));
                    return new MediaPlaylist(ours && playlist.path("readonly").asBoolean(false),
                            songs(playlist).stream().map(SongFinder.Candidate::id).toList());
                })
                .onErrorResume(SubsonicError.class, e -> e.code == NOT_FOUND ? Mono.empty()
                        : Mono.error(new MediaServerException(describe("playlist", e))));
    }

    /**
     * {@code updatePlaylist?songIdToAdd=}: allowed for any user's playlist with an admin login; refused (Subsonic 50)
     * for an imported or smart one, and "not found" (70) once the playlist is gone. Measured on Navidrome 0.64.2.
     */
    public Mono<Void> addToPlaylist(String playlistId, String songId) {
        return call(current, "updatePlaylist", Map.of("playlistId", playlistId, "songIdToAdd", songId))
                .onErrorMap(SubsonicError.class, e -> switch (e.code) {
                    case NOT_FOUND -> new PlaylistProblem(PlaylistProblem.Reason.PLAYLIST_GONE, "Navidrome has no such playlist any more.");
                    case NOT_AUTHORISED -> new PlaylistProblem(PlaylistProblem.Reason.NOT_EDITABLE,
                            "Navidrome does not let songs be added to this playlist (imported or smart).");
                    default -> new MediaServerException(describe("playlist change", e));
                })
                .then();
    }

    /** The songs of a Subsonic answer ({@code searchResult3}, a playlist), with every artist text each one carries. */
    static List<SongFinder.Candidate> songs(JsonNode parent) {
        List<SongFinder.Candidate> songs = new ArrayList<>();
        for (JsonNode song : parent.path(parent.has("song") ? "song" : "entry")) {
            StringBuilder artists = new StringBuilder();
            for (String field : List.of("artist", "displayArtist", "albumArtist", "displayAlbumArtist")) {
                artists.append(song.path(field).asString("")).append(" / ");
            }
            for (String list : List.of("artists", "albumArtists")) {
                for (JsonNode a : song.path(list)) {
                    artists.append(a.path("name").asString("")).append(" / ");
                }
            }
            songs.add(new SongFinder.Candidate(song.path("id").asString(null), song.path("albumId").asString(null),
                    song.path("path").asString(null), artists.toString(),
                    song.has("duration") ? song.path("duration").asInt() : null, song.path("created").asString(null)));
        }
        return songs;
    }

    /**
     * One Subsonic call with the token auth: the {@code subsonic-response} when its status is "ok", else a
     * {@link SubsonicError} with Navidrome's code; a transport failure is a {@link MediaServerException} sentence.
     */
    private Mono<JsonNode> call(Connection c, String endpoint, Map<String, String> params) {
        byte[] saltBytes = new byte[8];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        Map<String, Object> values = new HashMap<>(Map.of("u", c.username(), "t", token(c.password(), salt), "s", salt));
        return c.http().get()
                .uri(b -> {
                    b.path("/rest/" + endpoint)
                            .queryParam("u", "{u}").queryParam("t", "{t}").queryParam("s", "{s}")
                            .queryParam("v", SUBSONIC_VERSION).queryParam("c", CLIENT_NAME).queryParam("f", "json");
                    params.forEach((name, value) -> {
                        b.queryParam(name, "{p_" + name + "}");
                        values.put("p_" + name, value);
                    });
                    return b.build(values);
                })
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .flatMap(body -> {
                    JsonNode response = body.path("subsonic-response");
                    if ("ok".equals(response.path("status").asString(""))) {
                        return Mono.just(response);
                    }
                    return Mono.error(new SubsonicError(response.path("error").path("code").asInt(0),
                            response.path("error").path("message").asString("no message")));
                })
                .onErrorMap(error -> !(error instanceof MediaServerException),
                        error -> MediaServerException.transport("Navidrome", c.url(), error));
    }

    /** Navidrome answered with a Subsonic error (subsonic.org/pages/api.jsp codes). */
    static final class SubsonicError extends MediaServerException {
        final int code;
        final String detail;

        SubsonicError(int code, String detail) {
            super("Navidrome refused the request: " + detail + " (Subsonic error " + code + ")");
            this.code = code;
            this.detail = detail;
        }
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

    private static String describe(String what, SubsonicError e) {
        return e.code == WRONG_CREDENTIALS ? "Navidrome refused the username or password."
                : "Navidrome refused the " + what + ": " + e.detail + " (Subsonic error " + e.code + ")";
    }
}
