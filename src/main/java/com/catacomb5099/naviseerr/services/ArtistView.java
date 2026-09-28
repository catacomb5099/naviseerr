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
 * client reuses its search cards unchanged. Every list is non-null and capped at {@value #MAX}.
 *
 * @param id             the id the client REQUESTED (the adapter echoes it back verbatim anyway).
 * @param iconURL        capital {@code URL}, like {@code Track}/{@code Album}; {@code ""} when none.
 *                       {@code Artist.iconUrl} inside {@code similarArtists} is lowercase — the
 *                       client mirrors that inconsistency, so both are preserved.
 * @param subscribers    YouTube's own wording ("498K"), null when the adapter has none.
 * @param topSongs       {@code Track.albumId} is always {@code ""}: the adapter names a song's album
 *                       but gives no browseId for it. {@code iconURL} is YouTube's predictable
 *                       per-video thumbnail, since the adapter's artist answer carries no artwork for them.
 * @param albums         {@code artists} is {@code [this artist's name]}: the shelf lists only the
 *                       artist's own releases and the adapter gives no per-album artists.
 * @param playlists      NOT "playlists featuring this artist" — YouTube Music exposes no such list.
 *                       A playlist search for the artist's name, which is the closest thing there is.
 * @param similarArtists {@code iconUrl} is the related artist's thumbnail from the same adapter answer
 *                       (no extra call); {@code ""} when the adapter sends none.
 */
public record ArtistView(String id, String name, String iconURL, String description, String subscribers,
                         List<Track> topSongs, List<Album> albums, List<Album> singles,
                         List<Playlist> playlists, List<Artist> similarArtists) {

    static final int MAX = 10;

    static ArtistView from(YtMusicDetailResponse.Artist artist, List<Playlist> playlists, String requestedId) {
        List<String> ownName = artist.getName() == null ? List.of() : List.of(artist.getName());
        List<Track> topSongs = orEmpty(artist.getTopSongs()).stream()
                .filter(track -> track.getVideoId() != null && !Boolean.FALSE.equals(track.getIsAvailable()))
                .limit(MAX)
                .map(track -> new Track(track.getVideoId(), YtMusicService.fallbackThumbnail(track.getVideoId()),
                        "", orEmpty(track.getTitle()), YtMusicSearchResponseMapper.mapArtistNames(track.getArtists()),
                        "", 0))
                .toList();
        List<Artist> similar = orEmpty(artist.getRelated()).stream()
                .filter(related -> related.getBrowseId() != null)
                .limit(MAX)
                .map(related -> new Artist(related.getBrowseId(), orEmpty(related.getThumbnailUrl()),
                        orEmpty(related.getTitle())))
                .toList();
        return new ArtistView(requestedId, orEmpty(artist.getName()), orEmpty(artist.getThumbnailUrl()),
                artist.getDescription(), artist.getSubscribers(), topSongs,
                albums(artist.getAlbums(), ownName), albums(artist.getSingles(), ownName),
                orEmpty(playlists).stream().limit(MAX).toList(), similar);
    }

    private static List<Album> albums(List<YtMusicDetailResponse.AlbumStub> stubs, List<String> ownName) {
        return orEmpty(stubs).stream()
                .filter(stub -> stub.getBrowseId() != null)
                .limit(MAX)
                .map(stub -> new Album(stub.getBrowseId(), orEmpty(stub.getThumbnailUrl()), orEmpty(stub.getTitle()),
                        ownName, stub.getYear() == null ? 0 : stub.getYear()))
                .toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String orEmpty(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
