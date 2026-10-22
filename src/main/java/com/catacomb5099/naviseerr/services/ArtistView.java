package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.response.Album;
import com.catacomb5099.naviseerr.schema.response.Artist;
import com.catacomb5099.naviseerr.schema.response.Playlist;
import com.catacomb5099.naviseerr.schema.response.Track;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import com.catacomb5099.naviseerr.util.YtMusicSearchResponseMapper;

import java.util.List;
import java.util.Objects;

/**
 * An artist as the client browses it: header plus the shelves YouTube Music shows on the artist
 * page, expressed in the same {@code schema.response} DTOs the search routes already emit so the
 * client reuses its search cards unchanged. Every list is non-null and capped at {@value #MAX},
 * except {@code albums}, capped at {@value #MAX_ALBUMS}: since adapter 12-10-2026 it is the artist's
 * whole discography ordered by YouTube's popularity (Black Sabbath: 46, 'Paranoid' first) rather
 * than the page's ten newest releases, and a cap of ten would cut it back to the reissues.
 *
 * @param id             the id the client REQUESTED (the adapter echoes it back verbatim anyway).
 * @param iconURL        capital {@code URL}, like {@code Track}/{@code Album}; {@code ""} when none.
 *                       {@code Artist.iconUrl} inside {@code similarArtists} is lowercase — the
 *                       client mirrors that inconsistency, so both are preserved.
 * @param subscribers    YouTube's own wording ("498K"), null when the adapter has none.
 * @param topSongs       {@code Track.albumId} is always {@code ""}: the adapter names a song's album
 *                       but gives no browseId for it. {@code iconURL} is YouTube's predictable
 *                       per-video thumbnail, since the adapter's artist answer carries no artwork for them.
 *                       {@code plays} is YouTube's wording ("1.7B plays"), null from an adapter that
 *                       sends none. Up to {@value #MAX}: since adapter 20-10-2026 the first ten rows of
 *                       YouTube's "Top songs" playlist, the page's five from an older adapter.
 * @param topSongsPlaylistId that "Top songs" playlist's bare id ({@code OLAK5uy_...}, the {@code VL} browse
 *                       prefix stripped like every {@code Playlist.id}), for the web app's "Show all"; it
 *                       opens through {@code /collections/{id}?type=PLAYLIST}. Null from an older adapter
 *                       and for an artist whose page has no songs shelf.
 * @param albums         {@code artists} is {@code [this artist's name]} and {@code artistIds} is
 *                       {@code [the requested id]}: the shelf lists only the artist's own releases
 *                       and the adapter gives no per-album artists. In the adapter's order, which is
 *                       YouTube's popularity ranking (or the page's newest-first shelf from an older
 *                       adapter, or when the discography lookup failed).
 * @param playlists      YouTube Music's own featured playlists that its search links to this artist,
 *                       minus those titled after the artist or a related artist, kept only when the
 *                       playlist's track list (its first 100 tracks) credits the artist by name.
 *                       YouTube Music exposes no exact "featured on" list, so this is the closest
 *                       thing there is.
 * @param similarArtists {@code iconUrl} is the related artist's thumbnail from the same adapter answer
 *                       (no extra call); {@code ""} when the adapter sends none.
 */
public record ArtistView(String id, String name, String iconURL, String description, String subscribers,
                         List<Track> topSongs, String topSongsPlaylistId, List<Album> albums, List<Album> singles,
                         List<Playlist> playlists, List<Artist> similarArtists) {

    static final int MAX = 10;
    /** The adapter sends at most 100 (its own page size for the discography). */
    static final int MAX_ALBUMS = 100;

    static ArtistView from(YtMusicDetailResponse.Artist artist, List<Playlist> playlists, String requestedId) {
        List<String> ownName = artist.getName() == null ? List.of() : List.of(artist.getName());
        List<String> ownId = ownName.isEmpty() ? List.of() : List.of(requestedId);
        List<Track> topSongs = orEmpty(artist.getTopSongs()).stream()
                .filter(track -> track.getVideoId() != null && !Boolean.FALSE.equals(track.getIsAvailable()))
                .limit(MAX)
                .map(track -> new Track(track.getVideoId(), YtMusicService.fallbackThumbnail(track.getVideoId()),
                        "", orEmpty(track.getTitle()), YtMusicSearchResponseMapper.mapArtistNames(track.getArtists()),
                        YtMusicSearchResponseMapper.mapArtistIds(track.getArtists()),
                        "", 0, YtMusicSearchResponseMapper.plays(track.getViews())))
                .toList();
        List<Artist> similar = orEmpty(artist.getRelated()).stream()
                .filter(related -> related.getBrowseId() != null)
                .limit(MAX)
                .map(related -> new Artist(related.getBrowseId(), orEmpty(related.getThumbnailUrl()),
                        orEmpty(related.getTitle())))
                .toList();
        return new ArtistView(requestedId, orEmpty(artist.getName()), orEmpty(artist.getThumbnailUrl()),
                artist.getDescription(), artist.getSubscribers(), topSongs,
                artist.getSongsPlaylistId() == null ? null : artist.getSongsPlaylistId().replaceFirst("^VL", ""),
                albums(artist.getAlbums(), ownName, ownId, MAX_ALBUMS), albums(artist.getSingles(), ownName, ownId, MAX),
                orEmpty(playlists).stream().limit(MAX).toList(), similar);
    }

    private static List<Album> albums(List<YtMusicDetailResponse.AlbumStub> stubs, List<String> ownName, List<String> ownId, int max) {
        return orEmpty(stubs).stream()
                .filter(stub -> stub.getBrowseId() != null)
                .limit(max)
                .map(stub -> new Album(stub.getBrowseId(), orEmpty(stub.getThumbnailUrl()), orEmpty(stub.getTitle()),
                        ownName, ownId, stub.getYear() == null ? 0 : stub.getYear()))
                .toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String orEmpty(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
