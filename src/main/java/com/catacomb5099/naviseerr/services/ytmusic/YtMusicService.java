package com.catacomb5099.naviseerr.services.ytmusic;

import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;
import com.catacomb5099.naviseerr.util.YtMusicSearchResponseMapper;
import com.catacomb5099.naviseerr.util.networkcalls.ReactivePoller;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class YtMusicService {
    private static final String SEARCH_PATH = "/v1/search";
    private static final String SEARCH_PATH_PREFIX = SEARCH_PATH + "/";
    private static final String QUERY_PARAM = "q";
    private static final String LIMIT_PARAM = "limit";
    private static final String MIXED_SEARCH_LABEL = "mixed";
    private static final String SONG_PATH_PREFIX = "/v1/songs/";
    private static final String SONG_DETAILS_PATH_SUFFIX = "/details";
    private static final String ALBUM_PATH_PREFIX = "/v1/albums/";
    private static final String PLAYLIST_PATH_PREFIX = "/v1/playlists/";
    private static final String ARTIST_PATH_PREFIX = "/v1/artists/";

    private final WebClient ytMusicWebClient;

    @Value("${yt-music-service.search-result-limit}")
    private int searchResultLimit;
    // Mixed (unfiltered) search returns a single page of shelves -- ytmusicapi ignores `limit`
    // when no filter is set. This is set to the adapter's maximum purely to stop the adapter's
    // own items[:limit] truncation from starving the categories YouTube interleaves late
    // (albums, artists). Lowering it silently drops albums from general search.
    @Value("${yt-music-service.mixed-search-limit}")
    private int mixedSearchLimit;
    @Value("${yt-music-service.timeout-ms}")
    private long timeoutMs;
    @Value("${yt-music-service.retry-count}")
    private int retryCount;
    @Value("${yt-music-service.first-back-off-duration-ms}")
    private long firstBackOffDurationMs;

    public YtMusicService(WebClient ytMusicWebClient) {
        this.ytMusicWebClient = ytMusicWebClient;
    }

    public Mono<SearchResponse> getResults(String query, YtMusicSearchType type) {
        return getResults(query, type, searchResultLimit);
    }

    /**
     * Same typed search with a caller-chosen page size: the category search routes pass the client's
     * {@code ?limit=} (already pulled into 1..100) through here, and the artist page's featured search
     * asks for more than a page because it filters the answer down afterwards.
     */
    public Mono<SearchResponse> getResults(String query, YtMusicSearchType type, int limit) {
        return executeSearch(
                uriBuilder -> uriBuilder
                        .path(SEARCH_PATH_PREFIX + type.getPathSegment())
                        .queryParam(QUERY_PARAM, query)
                        .queryParam(LIMIT_PARAM, limit)
                        .build(),
                type.getPathSegment(),
                query);
    }

    /**
     * Issues one unfiltered search and partitions the mixed response into tracks/albums/artists
     * ({@link YtMusicSearchResponseMapper}) -- replaces the previous three-leg
     * {@code Mono.zip} of typed searches. This trades result volume (YouTube Music returns one
     * page of shelves for a mixed search, not up to {@code searchResultLimit} per type) and
     * blanks {@code Track.albumId} (song items in a mixed response carry no {@code album} field)
     * for a third of the provider load -- see docs/decisions/ytmusic-mixed-search-20-08-2026.md.
     */
    public Mono<SearchResponse> getResults(String query) {
        return executeSearch(
                uriBuilder -> uriBuilder
                        .path(SEARCH_PATH)
                        .queryParam(QUERY_PARAM, query)
                        .queryParam(LIMIT_PARAM, mixedSearchLimit)
                        .build(),
                MIXED_SEARCH_LABEL,
                query);
    }

    /**
     * Resolves one {@code videoId} to the name and artists a Soulseek query is worded from. This is
     * what turns the opaque id a download request carries into something searchable, and it is the
     * only reason the download pipeline talks to this provider at all.
     */
    public Mono<YoutubeSongInfo> getSongInfo(String id) {
        return execute(uriBuilder -> uriBuilder.path(SONG_PATH_PREFIX + id).build(),
                        YtMusicDetailResponse.Song.class, "song", id)
                .map(song -> new YoutubeSongInfo(
                        song.getVideoId() == null ? id : song.getVideoId(),
                        // getSong()'s `author` is one string, not a list -- flattened here so callers
                        // never have to know which of the two provider shapes a song came from.
                        song.getAuthor() == null ? List.of() : List.of(song.getAuthor()),
                        song.getAuthor() == null ? List.of()
                                : List.of(song.getChannelId() == null ? "" : song.getChannelId()),
                        song.getTitle(),
                        song.getThumbnailUrl() == null ? fallbackThumbnail(id) : song.getThumbnailUrl(),
                        song.getLengthSeconds(), null));
    }

    /**
     * How many times this one video was played: the same {@code /v1/songs/{id}} call as
     * {@link #getSongInfo}, reading only {@code viewCount}. Kept apart so {@code YoutubeSongInfo},
     * which belongs to the download pipeline, does not grow a field the pipeline never reads. Empty
     * when the adapter has no count.
     */
    public Mono<Long> getSongViewCount(String id) {
        return execute(uriBuilder -> uriBuilder.path(SONG_PATH_PREFIX + id).build(),
                YtMusicDetailResponse.Song.class, "song-views", id)
                .mapNotNull(YtMusicDetailResponse.Song::getViewCount);
    }

    /**
     * The song page: everything {@link #getSongInfo} knows plus artists with ids, album, year, view
     * count, the explicit flag and the credits panel. Returned as the adapter shape; the only consumer
     * is {@code SongInfoView.from}, and a domain record between the two would be a third copy of the
     * same ten fields.
     */
    public Mono<YtMusicDetailResponse.SongDetails> getSongDetails(String id) {
        return execute(uriBuilder -> uriBuilder.path(SONG_PATH_PREFIX + id + SONG_DETAILS_PATH_SUFFIX).build(),
                YtMusicDetailResponse.SongDetails.class, "song-details", id);
    }

    /**
     * YouTube serves a thumbnail for every videoId at a predictable URL, whether or not the adapter
     * handed us one. Used only when it did not: a track inside a playlist has no artwork of its own in
     * the adapter's response, and fetching each track individually would turn one metadata call into
     * hundreds. For a YouTube Music track this is the album art letterboxed into a 4:3 frame -- not
     * pretty, but a picture rather than a blank. A later single-song request for the same id upserts
     * the real artwork over it.
     */
    public static String fallbackThumbnail(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    /**
     * One generic collection type for albums and playlists — see {@link YoutubeCollectionInfo} for
     * why they are not split. Both are one GET returning a track list.
     */
    public Mono<YoutubeCollectionInfo> getAlbumInfo(String id) {
        return execute(uriBuilder -> uriBuilder.path(ALBUM_PATH_PREFIX + id).build(),
                        YtMusicDetailResponse.Collection.class, "album", id)
                .map(album -> toCollectionInfo(album, id));
    }

    public Mono<YoutubeCollectionInfo> getPlaylistInfo(String id) {
        return execute(uriBuilder -> uriBuilder.path(PLAYLIST_PATH_PREFIX + id).build(),
                        YtMusicDetailResponse.Collection.class, "playlist", id)
                .map(playlist -> toCollectionInfo(playlist, id));
    }

    /**
     * One artist's page as the adapter describes it: header, top songs, albums, singles and related
     * artists. Returned as the adapter model rather than a domain record because the only reader is
     * {@code ArtistView}, which maps it straight onto the wire; the download pipeline never asks for
     * an artist.
     */
    public Mono<YtMusicDetailResponse.Artist> getArtistInfo(String channelId) {
        return execute(uriBuilder -> uriBuilder.path(ARTIST_PATH_PREFIX + channelId).build(),
                YtMusicDetailResponse.Artist.class, "artist", channelId);
    }

    /**
     * Folds the album and playlist shapes into one. An album reports {@code browseId} and an
     * {@code artists[]}; a playlist reports {@code id} and a single {@code author}. Falling back to
     * the requested id keeps the record's id non-null even when the adapter omits its own.
     *
     * <p>A track the provider marks {@code isAvailable: false} is dropped: it is region-blocked or
     * deleted, so creating a task row for it would spend a full search budget to fail. Only an
     * explicit {@code false} counts — album responses leave the field null.
     *
     * <p>Artwork: an album's tracks ARE that album, so they inherit its cover. A playlist's tracks
     * come from anywhere, so giving them the playlist's cover would be wrong; they get YouTube's
     * per-video thumbnail instead ({@link #fallbackThumbnail}). The two cases are told apart by
     * which id field the adapter filled in.
     */
    private YoutubeCollectionInfo toCollectionInfo(YtMusicDetailResponse.Collection collection,
                                                   String requestedId) {
        String id = collection.getBrowseId() != null ? collection.getBrowseId()
                : collection.getId() != null ? collection.getId() : requestedId;
        List<YtMusicSearchResponse.ArtistRef> authors = collection.getArtists() != null
                ? collection.getArtists()
                : collection.getAuthor() == null ? List.of() : List.of(collection.getAuthor());
        boolean isAlbum = collection.getBrowseId() != null;
        List<YoutubeSongInfo> songs = collection.getTracks() == null ? List.of()
                : collection.getTracks().stream()
                        .filter(track -> !Boolean.FALSE.equals(track.getIsAvailable()))
                        .map(track -> new YoutubeSongInfo(track.getVideoId(),
                                names(track.getArtists()), ids(track.getArtists()), track.getTitle(),
                                isAlbum && collection.getThumbnailUrl() != null
                                        ? collection.getThumbnailUrl()
                                        : track.getVideoId() == null ? null
                                        : fallbackThumbnail(track.getVideoId()),
                                track.getDurationSeconds(), track.getViews()))
                        .toList();
        return new YoutubeCollectionInfo(id, songs,
                collection.getYear() == null ? null : String.valueOf(collection.getYear()),
                collection.getTitle(), names(authors), ids(authors), collection.getThumbnailUrl());
    }

    private static List<String> names(List<YtMusicSearchResponse.ArtistRef> artists) {
        return artists == null ? List.of() : artists.stream()
                .map(YtMusicSearchResponse.ArtistRef::getName)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Same filter as {@link #names}, so the two lists line up entry for entry; "" where YouTube gave no channel. */
    private static List<String> ids(List<YtMusicSearchResponse.ArtistRef> artists) {
        return artists == null ? List.of() : artists.stream()
                .filter(artist -> artist.getName() != null)
                .map(artist -> artist.getChannelId() == null ? "" : artist.getChannelId())
                .toList();
    }

    private Mono<SearchResponse> executeSearch(Function<UriBuilder, URI> uriFunction, String label, String query) {
        return execute(uriFunction, YtMusicSearchResponse.class, label, query)
                .doOnNext(response -> log.debug(
                        "ytmusic-adapter responded for type={} query='{}': reportedType={}, count={}, items={}",
                        label, query, response.getType(), response.getCount(),
                        summarizeItems(response.getItems())))
                .map(YtMusicSearchResponseMapper::mapToSearchResponse);
    }

    /**
     * The one request pipeline every adapter call goes through: timeout, typed error translation,
     * and retry on availability failures only. Extracted from {@code executeSearch} when the
     * metadata calls above were added — AGENTS.md names this handling the pattern new provider
     * calls should follow, and the only way to follow it is to not write it twice.
     *
     * @param subject what is being asked for (a query, or an id), for the log lines only
     */
    private <T> Mono<T> execute(Function<UriBuilder, URI> uriFunction, Class<T> bodyType,
                                String label, String subject) {
        return ytMusicWebClient.get()
                .uri(uriFunction)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .bodyToMono(bodyType)
                .timeout(Duration.ofMillis(timeoutMs))
                // Any failure that isn't already one of our typed exceptions (client-side
                // timeout, connection refused, decode failure) is a provider-availability
                // problem, same as an adapter-side 502/504/429 -- fold it in so the retry
                // filter below and callers only ever see YtMusicException subtypes.
                .onErrorMap(
                        error -> !(error instanceof YtMusicException),
                        error -> new YtMusicUnavailableException("ytmusic-adapter request failed: " + error.getMessage(), error)
                )
                .retryWhen(ReactivePoller.defaultBackoff(Duration.ofMillis(firstBackOffDurationMs), retryCount)
                        .filter(YtMusicUnavailableException.class::isInstance)
                        .doBeforeRetry(signal -> log.warn(
                                "Retrying ytmusic-adapter request for type={} subject='{}' (attempt {}) after: {}",
                                label, subject, signal.totalRetries() + 1, signal.failure().getMessage()))
                        // Reactor's default exhaustion behavior wraps the last failure in an
                        // IllegalStateException; unwrap it so callers only ever see
                        // YtMusicException subtypes, retried or not.
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    /**
     * The adapter's error body is NOT uniform: {@code {"error":{"code","message"}}} for
     * everything it classifies itself, but {@code {"detail": "..."}} (a plain string) for an
     * unsupported {@code type} 400 and its own 503, and {@code {"detail": [...]}} (an array)
     * for a 422 validation failure. Read defensively as a JsonNode and try both shapes.
     */
    private Mono<? extends Throwable> translateError(ClientResponse response) {
        int statusCode = response.statusCode().value();
        return response.bodyToMono(JsonNode.class)
                .defaultIfEmpty(JsonNodeFactory.instance.objectNode())
                .<YtMusicException>map(body -> buildException(statusCode, body))
                .doOnNext(ex -> log.warn("ytmusic-adapter returned {}: {}", statusCode, ex.getMessage()))
                .onErrorReturn(new YtMusicUnavailableException(
                        "ytmusic-adapter returned " + statusCode + " with an unreadable error body"));
    }

    private YtMusicException buildException(int statusCode, JsonNode body) {
        String message = extractMessage(body, statusCode);
        // 400/422: we sent a bad request. 500: the adapter's own internal_auth_misuse case --
        // an adapter bug, not a transient failure. Neither is worth retrying.
        if (statusCode == 400 || statusCode == 422 || statusCode == 500) {
            return new YtMusicBadRequestException(message);
        }
        if (statusCode == 404) {
            // The adapter's own envelope: it looked, and YouTube Music has no such video/album/
            // playlist. Non-retryable in the same sense as a 400 -- retrying cannot make the id
            // exist, and the metadata calls above lean on that distinction to tell "fail this
            // download now" from "try again next pass". Without it, one mistyped id is
            // re-requested every loop interval, forever.
            if (body.hasNonNull("error")) {
                return new YtMusicBadRequestException(message);
            }
            // No envelope means the adapter's handler never ran: FastAPI answered
            // {"detail":"Not Found"} because the route does not exist in the image we are talking
            // to. That is our deployment being stale, not the id being wrong -- on 28-09-2026 an
            // old image made every song "unknown" in the client until someone read the raw body.
            return new YtMusicUnavailableException(
                    "ytmusic-adapter has no route for this request; is the sidecar image up to date?");
        }
        // 429/502/504 and any other unlisted status: provider failed or is unreachable.
        return new YtMusicUnavailableException(message);
    }

    /**
     * DEBUG-only, so this stays on for verifying real-world YouTube Music responses without
     * flooding INFO -- title + resultType per item, never the full raw payload.
     */
    private String summarizeItems(List<YtMusicSearchResponse.Item> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        return items.stream()
                .map(item -> "%s:'%s'".formatted(item.getType(), item.getTitle()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String extractMessage(JsonNode body, int statusCode) {
        String fallback = "ytmusic-adapter returned " + statusCode + " with no decodable error body";
        JsonNode error = body.get("error");
        if (error != null && error.has("message")) {
            // asString(default) never throws, even if the adapter's body doesn't match the
            // shape we expect -- this path builds an error message, so it must not itself fail.
            return error.get("message").asString(fallback);
        }
        JsonNode detail = body.get("detail");
        if (detail != null && !detail.isNull()) {
            return detail.isString() ? detail.asString(fallback) : detail.toString();
        }
        return fallback;
    }
}
