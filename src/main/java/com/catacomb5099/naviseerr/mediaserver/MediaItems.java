package com.catacomb5099.naviseerr.mediaserver;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/** Finds a filed song in the media server a player names, through the client naviseerr is connected with. */
@Component
public class MediaItems {

    private final NavidromeClient navidrome;
    private final JellyfinClient jellyfin;
    private final LibraryOrganiser organiser;

    public MediaItems(NavidromeClient navidrome, JellyfinClient jellyfin, LibraryOrganiser organiser) {
        this.navidrome = navidrome;
        this.jellyfin = jellyfin;
        this.organiser = organiser;
    }

    /** naviseerr has an address and a login for that server (Settings, Music app, or the install file). */
    public boolean connected(MediaServer server) {
        return switch (server) {
            case NAVIDROME -> navidrome.isConfigured() && navidrome.hasCredentials();
            case JELLYFIN -> jellyfin.isConfigured() && jellyfin.hasCredentials();
        };
    }

    public static String notConnected(MediaServer server) {
        return "naviseerr is not connected to " + server.displayName + ". Add it in naviseerr's Settings, under Music app.";
    }

    /** The media server's song for this filed file; empty when it does not list it (yet). */
    public Mono<SongFinder.Found> find(MediaServer server, FiledSongs.FiledSong song) {
        SongFinder.Wanted wanted = new SongFinder.Wanted(song.title(), song.artists(), song.durationSeconds(),
                relativePath(song.libraryPath()));
        return switch (server) {
            case NAVIDROME -> navidrome.findSong(wanted);
            case JELLYFIN -> jellyfin.findSong(wanted);
        };
    }

    /** The playlist as naviseerr can see it; empty when that server has no such playlist. {@code userId}: Jellyfin's owner. */
    public Mono<MediaPlaylist> playlist(MediaServer server, String playlistId, String userId) {
        return switch (server) {
            case NAVIDROME -> navidrome.playlist(playlistId);
            case JELLYFIN -> jellyfin.playlist(playlistId, userId);
        };
    }

    /** Adds one media-server song to the playlist; a {@link PlaylistProblem} when the server never will. */
    public Mono<Void> addToPlaylist(MediaServer server, String playlistId, String userId, String songId) {
        return switch (server) {
            case NAVIDROME -> navidrome.addToPlaylist(playlistId, songId);
            case JELLYFIN -> jellyfin.addToPlaylist(playlistId, songId, userId);
        };
    }

    /** {@code Oasis/Album/01 Wonderwall.mp3}: the filed path under the library root, '/'-separated. */
    String relativePath(String libraryPath) {
        Path file = Path.of(libraryPath).normalize();
        String root = organiser.libraryRoot();
        Path relative = root != null && file.startsWith(Path.of(root)) ? Path.of(root).relativize(file) : file.getFileName();
        return relative.toString().replace('\\', '/');
    }
}
