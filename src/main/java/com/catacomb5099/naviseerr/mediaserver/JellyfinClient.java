package com.catacomb5099.naviseerr.mediaserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Jellyfin through its REST API with an API key ({@code Authorization: MediaBrowser Token="<key>"}, set on
 * the client by {@link MediaServerConfig}; keys carry the Administrator role, so every call below is
 * allowed). The rescan is what Jellyfin's own "Scan Library" button on a library sends: one
 * {@code POST /Items/{libraryId}/Refresh?Recursive=true} per library of type music or of no type (a
 * "Mixed" library, which Jellyfin's audio and playlist resolvers serve too), immediate and queued behind
 * a running scan rather than cancelling it. With no such library at all it falls back to
 * {@code POST /Library/Refresh}, the full scan of every library.
 *
 * <p>Measured 19-10-2026 on Jellyfin 12.2.0 with a fresh mp3 in a new artist folder: this call showed it
 * in 6 s. The alternatives: {@code POST /Library/Media/Updated} (what Lidarr sends and what Jellyfin's
 * own real-time monitor reports) waits out Jellyfin's 60 s monitor delay (70-84 s) and, given the
 * library's own path, imported nothing at all while the library was still empty; {@code POST
 * /Library/Refresh} took 21 s, scans every library and cancels a scan already running.
 */
@Slf4j
@Service
public class JellyfinClient {

    private final WebClient http;
    private final String url;
    private final String apiKey;
    private final Duration timeout;

    public JellyfinClient(@Qualifier("jellyfinWebClient") WebClient http,
                          @Value("${media-servers.jellyfin.url}") String url,
                          @Value("${media-servers.jellyfin.api-key}") String apiKey,
                          @Value("${media-servers.timeout-ms}") Duration timeout) {
        this.http = http;
        this.url = MediaServerConfig.baseUrl(url);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.timeout = timeout;
    }

    /** {@code JELLYFIN_URL} is set. */
    public boolean isConfigured() {
        return !"http://unset.invalid".equals(url);
    }

    /** {@code JELLYFIN_API_KEY} is set. */
    public boolean hasCredentials() {
        return !apiKey.isBlank();
    }

    public String url() {
        return url;
    }

    /** Asks every music (or untyped) library to scan for new files; all of Jellyfin when there is none. */
    public Mono<Void> rescan() {
        return http.get().uri("/Library/MediaFolders")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(JellyfinClient::musicLibraryIds)
                .flatMap(ids -> ids.isEmpty()
                        ? http.post().uri("/Library/Refresh").retrieve().toBodilessEntity().then()
                        : Flux.fromIterable(ids)
                                .flatMap(id -> http.post()
                                        .uri("/Items/{id}/Refresh?Recursive=true&MetadataRefreshMode=Default&ImageRefreshMode=Default", id)
                                        .retrieve().toBodilessEntity())
                                .then())
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof MediaServerException), this::translate);
    }

    /**
     * The id of the playlist Jellyfin made from the playlist file {@code fileName} ({@code <title>.m3u8}):
     * {@code GET /Items?includeItemTypes=Playlist&recursive=true&searchTerm=<title>&fields=Path}, the item
     * whose {@code Path} ends in that file name (Jellyfin's own path to it, whatever its mount), else the
     * one named exactly {@code <title>} (Jellyfin names a file playlist after its file). Empty while
     * Jellyfin has not imported the file yet. An API key needs no {@code userId} here.
     */
    public Mono<String> findPlaylist(String fileName) {
        String title = fileName.endsWith(".m3u8") ? fileName.substring(0, fileName.length() - 5) : fileName;
        return http.get()
                .uri(b -> b.path("/Items").queryParam("includeItemTypes", "Playlist").queryParam("recursive", "true")
                        .queryParam("searchTerm", "{q}").queryParam("fields", "Path").build(title))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(body -> Mono.justOrEmpty(playlistId(body, fileName, title)))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof MediaServerException), this::translate);
    }

    /** {@code POST /Items/{id}/Images/Primary}: Jellyfin takes the picture as base64 text with the image's content type. */
    public Mono<Void> setPrimaryImage(String itemId, byte[] jpeg) {
        return http.post().uri("/Items/{id}/Images/Primary", itemId)
                .contentType(MediaType.IMAGE_JPEG)
                .bodyValue(Base64.getEncoder().encodeToString(jpeg))
                .retrieve()
                .toBodilessEntity()
                .then()
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof MediaServerException), this::translate);
    }

    static String playlistId(JsonNode body, String fileName, String title) {
        String byName = null;
        for (JsonNode item : body.path("Items")) {
            String path = item.path("Path").asString("").replace('\\', '/');
            if (path.endsWith("/" + fileName)) {
                return item.path("Id").asString(null);
            }
            if (byName == null && title.equals(item.path("Name").asString(""))) {
                byName = item.path("Id").asString(null);
            }
        }
        return byName;
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

    private MediaServerException translate(Throwable error) {
        if (error instanceof WebClientResponseException http
                && (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403)) {
            return new MediaServerException("Jellyfin rejected the API key (JELLYFIN_API_KEY)");
        }
        return new MediaServerException("Jellyfin at " + url + " did not answer: " + MediaServerException.reason(error));
    }
}
