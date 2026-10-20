package com.catacomb5099.naviseerr.mediaserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Jellyfin through its REST API with an API key ({@code Authorization: MediaBrowser Token="<key>"}, set on
 * the client; keys carry the Administrator role, so every call below is allowed; the key never travels in a
 * URL, the {@code ?api_key=} form is legacy). The rescan is what Jellyfin's own "Scan Library" button on a
 * library sends: one {@code POST /Items/{libraryId}/Refresh?Recursive=true} per library of type music or of
 * no type (a "Mixed" library, which Jellyfin's audio and playlist resolvers serve too), immediate and queued
 * behind a running scan rather than cancelling it. With no such library at all it falls back to
 * {@code POST /Library/Refresh}, the full scan of every library.
 *
 * <p>Measured 19-10-2026 on Jellyfin 12.2.0 with a fresh mp3 in a new artist folder: this call showed it
 * in 6 s. The alternatives: {@code POST /Library/Media/Updated} (what Lidarr sends and what Jellyfin's
 * own real-time monitor reports) waits out Jellyfin's 60 s monitor delay (70-84 s) and, given the
 * library's own path, imported nothing at all while the library was still empty; {@code POST
 * /Library/Refresh} took 21 s, scans every library and cancels a scan already running.
 *
 * <p>Which Jellyfin: the install file's lines at boot, then whatever the Settings page saves
 * ({@link #reconfigure}), live; {@link #reset} goes back to the install file. {@link #test} is the
 * Settings page's "Save and test": one rescan with the typed values, nothing kept.
 */
@Slf4j
@Service
public class JellyfinClient {

    /** One Jellyfin as reached: address, key and the client carrying the key, in one object so a change is atomic. */
    private record Connection(String url, String apiKey, WebClient http) {
        static Connection to(String url, String apiKey, Duration timeout) {
            String base = MediaServerConfig.baseUrl(url);
            String key = apiKey == null ? "" : apiKey.strip();
            return new Connection(base, key, MediaServerConfig.http(base, timeout)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "MediaBrowser Token=\"" + key + "\"").build());
        }
    }

    private final Duration timeout;
    /** The install file's {@code JELLYFIN_URL} / {@code JELLYFIN_API_KEY}: the fallback. */
    private final Connection installFile;
    private volatile Connection current;

    public JellyfinClient(@Value("${media-servers.jellyfin.url}") String url,
                          @Value("${media-servers.jellyfin.api-key}") String apiKey,
                          @Value("${media-servers.timeout-ms}") Duration timeout) {
        this.timeout = timeout;
        this.installFile = Connection.to(url, apiKey, timeout);
        this.current = installFile;
    }

    /** Talk to this Jellyfin from now on (what the Settings page saved). */
    public void reconfigure(String url, String apiKey) {
        current = Connection.to(url, apiKey, timeout);
    }

    /** Back to the install file's lines, or to none. */
    public void reset() {
        current = installFile;
    }

    /** A URL is set (in the app or the install file). */
    public boolean isConfigured() {
        return !MediaServerConfig.UNSET.equals(current.url());
    }

    /** An API key is set. */
    public boolean hasCredentials() {
        return !current.apiKey().isBlank();
    }

    public String url() {
        return current.url();
    }

    /** Asks every music (or untyped) library to scan for new files; all of Jellyfin when there is none. */
    public Mono<Void> rescan() {
        return rescan(current);
    }

    /** "Save and test": one rescan with these values, which proves the address and the key. Nothing kept. */
    public Mono<Void> test(String url, String apiKey) {
        return rescan(Connection.to(url, apiKey, timeout));
    }

    private Mono<Void> rescan(Connection c) {
        return c.http().get().uri("/Library/MediaFolders")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(JellyfinClient::musicLibraryIds)
                .flatMap(ids -> ids.isEmpty()
                        ? c.http().post().uri("/Library/Refresh").retrieve().toBodilessEntity().then()
                        : Flux.fromIterable(ids)
                                .flatMap(id -> c.http().post()
                                        .uri("/Items/{id}/Refresh?Recursive=true&MetadataRefreshMode=Default&ImageRefreshMode=Default", id)
                                        .retrieve().toBodilessEntity())
                                .then())
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof MediaServerException), error -> translate(c.url(), error));
    }

    /**
     * The id of the playlist Jellyfin made from the playlist file {@code fileName} ({@code <title>.m3u8}):
     * {@code GET /Items?includeItemTypes=Playlist&recursive=true&searchTerm=<title>&fields=Path}, the item
     * whose {@code Path} ends in that file name (Jellyfin's own path to it, whatever its mount). Never by
     * name: with an API key the search also lists playlists made inside Jellyfin, and one that shares the
     * title must keep its own picture. Empty while Jellyfin has not imported the file yet. An API key needs
     * no {@code userId} here.
     */
    public Mono<String> findPlaylist(String fileName) {
        Connection c = current;
        String title = fileName.endsWith(".m3u8") ? fileName.substring(0, fileName.length() - 5) : fileName;
        return c.http().get()
                .uri(b -> b.path("/Items").queryParam("includeItemTypes", "Playlist").queryParam("recursive", "true")
                        .queryParam("searchTerm", "{q}").queryParam("fields", "Path").build(title))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(body -> Mono.justOrEmpty(playlistId(body, fileName)))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof MediaServerException), error -> translate(c.url(), error));
    }

    /** {@code POST /Items/{id}/Images/Primary}: Jellyfin takes the picture as base64 text with the image's content type. */
    public Mono<Void> setPrimaryImage(String itemId, byte[] jpeg) {
        Connection c = current;
        return c.http().post().uri("/Items/{id}/Images/Primary", itemId)
                .contentType(MediaType.IMAGE_JPEG)
                .bodyValue(Base64.getEncoder().encodeToString(jpeg))
                .retrieve()
                .toBodilessEntity()
                .then()
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof MediaServerException), error -> translate(c.url(), error));
    }

    static String playlistId(JsonNode body, String fileName) {
        for (JsonNode item : body.path("Items")) {
            String path = item.path("Path").asString("").replace('\\', '/');
            if (path.endsWith("/" + fileName)) {
                return item.path("Id").asString(null);
            }
        }
        return null;
    }

    /** The ids of the libraries of type music, or of no type ("Mixed"), in a {@code GET /Library/MediaFolders} answer. */
    static List<String> musicLibraryIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : body.path("Items")) {
            String type = item.path("CollectionType").asString("");
            if ((type.isEmpty() || "music".equalsIgnoreCase(type)) && !item.path("Id").asString("").isBlank()) {
                ids.add(item.path("Id").asString(""));
            }
        }
        return ids;
    }

    private static MediaServerException translate(String url, Throwable error) {
        if (error instanceof WebClientResponseException http
                && (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403)) {
            return new MediaServerException("Jellyfin refused the API key.");
        }
        return MediaServerException.transport("Jellyfin", url, error);
    }
}
