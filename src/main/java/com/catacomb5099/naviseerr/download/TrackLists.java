package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.curator.CuratorClient;
import com.catacomb5099.naviseerr.curator.CuratorEdition;
import com.catacomb5099.naviseerr.services.PlaylistRepository;
import com.catacomb5099.naviseerr.services.RadioRepository;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one place that turns a (type, id) into its songs, in order: what a download of it fetches.
 * Admission ({@link DownloadTaskRunner}) asks here, so anything else that needs "the songs of this
 * album" gets exactly the songs a download would.
 */
@Component
public class TrackLists {

    private final YtMusicService ytMusicService;
    private final CuratorClient curatorClient;
    private final RadioRepository radioRepository;
    private final PlaylistRepository playlists;

    public TrackLists(YtMusicService ytMusicService, CuratorClient curatorClient, RadioRepository radioRepository,
                      PlaylistRepository playlists) {
        this.ytMusicService = ytMusicService;
        this.curatorClient = curatorClient;
        this.radioRepository = radioRepository;
        this.playlists = playlists;
    }

    /**
     * One shape for every type. A song is a collection of one -- which is exactly what it becomes in
     * {@code download_tasks} anyway -- and a curated edition is a collection whose track list comes
     * from the curator instead of YouTube.
     */
    public Mono<YoutubeCollectionInfo> of(DownloadType type, String id) {
        return switch (type) {
            case SONG -> ytMusicService.getSongInfo(id)
                    .map(song -> new YoutubeCollectionInfo(song.id(), List.of(song), null,
                            song.name(), song.authorNames(), song.authorIds(), song.imageUrl()));
            case ALBUM -> ytMusicService.getAlbumInfo(id);
            case PLAYLIST -> ytMusicService.getPlaylistInfo(id);
            case CURATED -> curatorClient.getEdition(id).map(edition -> curatedCollection(id, edition));
            case RADIO -> saved(id, radioRepository::find);
            case CUSTOM -> saved(id, uuid -> playlists.find(uuid).map(PlaylistRepository.Playlist::asCollection));
        };
    }

    /**
     * A saved radio or playlist, never a fresh list from YouTube. An id that is not a UUID or names no
     * saved list comes back as a list with no songs, which admission fails as nothing-to-download; an
     * empty Mono would instead leave the row PENDING and retried forever.
     */
    private static Mono<YoutubeCollectionInfo> saved(String id, Function<UUID, Mono<YoutubeCollectionInfo>> find) {
        YoutubeCollectionInfo none = new YoutubeCollectionInfo(id, List.of(), null, null, List.of(), null);
        try {
            return find.apply(UUID.fromString(id)).defaultIfEmpty(none);
        } catch (IllegalArgumentException notAUuid) {
            return Mono.just(none);
        }
    }

    /**
     * An edition of a suggested playlist in the shape every other collection arrives in. The curator
     * stores no artwork, so every song gets YouTube's predictable thumbnail and the playlist borrows
     * its first song's; the author line says who made the list. The curator knows artists by name
     * only, so every id is blank and the names stay plain text.
     */
    static YoutubeCollectionInfo curatedCollection(String category, CuratorEdition edition) {
        List<YoutubeSongInfo> songs = edition.tracks().stream()
                .filter(track -> track.videoId() != null)
                .map(track -> new YoutubeSongInfo(track.videoId(), track.artists(),
                        Collections.nCopies(track.artists().size(), ""), track.title(),
                        YtMusicService.fallbackThumbnail(track.videoId()), null, null))
                .toList();
        String image = songs.isEmpty() ? null : songs.getFirst().imageUrl();
        return new YoutubeCollectionInfo(category, songs, null, edition.title(), List.of("Naviseerr"),
                List.of(""), image);
    }
}
