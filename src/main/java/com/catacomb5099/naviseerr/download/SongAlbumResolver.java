package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.curator.CuratorClient;
import com.catacomb5099.naviseerr.curator.CuratorTrack;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeCollectionInfo;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse.Collection;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse.Track;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the YouTube Music album each song and playlist track belongs to, so the organiser files it in
 * that album's folder and the songs of one album end up together
 * (docs/decisions/youtube-album-tags-04-10-2026.md). Writes one {@code song_albums} row per YouTube id
 * and the album's {@code media_items} row; album downloads need none of this, their album is the one
 * the user picked.
 *
 * <p>An album is TRUSTED only when it is the song artist's own Album or EP: YouTube Music often files a
 * famous song under a single or a Various Artists compilation ("Lose Yourself" under the "Just Lose It"
 * single, "Don't You (Forget About Me)" under the "Driving" compilation), and filing it there would
 * scatter an artist's songs, not join them. Nothing trusted means {@code album_id} NULL, and the song is
 * filed by its own name as before.
 *
 * <p>Its own {@code Flux.interval}, not a step of {@link DownloadTaskRunner#pass()}: one song costs two
 * to six adapter calls of 1-15 s each, and the download loop must never wait on them. One call at a
 * time (parallel YouTube calls raise failures). The writes are idempotent upserts, so two instances
 * looking up the same song only waste calls. Off when the organiser is: nothing else reads the answer.
 */
@Slf4j
@Component
public class SongAlbumResolver {

    /** A "looked, nothing trusted" answer is asked again after this, for a song requested again. */
    static final Duration RELOOK_AFTER = Duration.ofDays(7);
    /** Search rows looked at. YouTube Music lists the song's own releases first; past five it is covers. */
    static final int SEARCH_ROWS = 5;
    /** Two lengths this close are one recording (the album row 324 s, its audio upload 323 s). */
    private static final int SAME_LENGTH_SECONDS = 3;
    /** "(2001 Remastered Version)", "(feat. Rihanna)": the same recording, so no difference in title. */
    private static final Pattern SAME_RECORDING = Pattern.compile(
            "\\((?:[^()]*remaster[^()]*|\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s[^()]*)\\)",
            Pattern.CASE_INSENSITIVE);

    /** One song to look up, as {@code DownloadTaskRepository.songsToResolve} reads it. */
    public record Song(String youtubeId, String songName, Integer durationSeconds, DownloadType type,
                       String downloadYoutubeId) {}

    /** The trusted album a song was found on, and the song's own row there. */
    record Found(String albumId, Collection album, Track track) {}

    /** A YouTube id the song is known by, with that upload's length (an official video runs longer). */
    record Identity(String videoId, Integer durationSeconds) {}

    private final DownloadTaskRepository repository;
    private final YtMusicService ytMusicService;
    private final CuratorClient curatorClient;
    private final LibraryOrganiser organiser;
    private final Clock clock;
    private final Duration interval;
    private final int batchSize;
    private Disposable subscription;

    public SongAlbumResolver(DownloadTaskRepository repository, YtMusicService ytMusicService,
                             CuratorClient curatorClient, LibraryOrganiser organiser, Clock clock,
                             @Value("${download-task.loop-interval-ms:2000}") Duration interval,
                             @Value("${download-task.batch-size:10}") int batchSize) {
        this.repository = repository;
        this.ytMusicService = ytMusicService;
        this.curatorClient = curatorClient;
        this.organiser = organiser;
        this.clock = clock;
        this.interval = interval;
        this.batchSize = batchSize;
    }

    @PostConstruct
    void start() {
        if (!organiser.isEnabled()) {
            return;
        }
        subscription = Flux.interval(interval)
                .onBackpressureDrop()
                .concatMap(tick -> resolveDue())
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    /**
     * Looks up the songs still waiting for an answer, one after another. Only songs still downloading
     * or just finished and not filed yet are asked for ({@code songsToResolve}), never the history. A
     * song the adapter could not answer for (down, timed out) gets no row and is asked again next tick.
     */
    // ponytail: oldest first with no back-off, so ids that time out every time are retried every tick
    // ahead of newer songs; add a next-attempt time to song_albums if that is ever seen.
    Mono<Void> resolveDue() {
        Instant now = clock.instant();
        return repository.songsToResolve(batchSize, organiser.cutoff(now), now.minus(RELOOK_AFTER))
                .concatMap(song -> resolve(song)
                        .flatMap(found -> save(song, found))
                        .onErrorResume(error -> {
                            log.warn("Could not look up the YouTube Music album of song {} ('{}'); will "
                                    + "ask again: {}", song.youtubeId(), song.songName(), error.getMessage());
                            return Mono.empty();
                        }))
                .then()
                .onErrorResume(error -> {
                    log.error("Album lookup pass failed", error);
                    return Mono.empty();
                });
    }

    /**
     * Where to look first: the curator's own album for a suggested-playlist song (it picked the song
     * from that album's page), else the album the song's details name. An official video has none, so
     * the song search finds the audio upload's album instead. An untrusted first album gets the same
     * search as a second chance. Then the plainest edition (see {@link #plainestEdition}).
     *
     * @return empty Optional when nothing can be trusted, which is an answer and is stored
     */
    Mono<Optional<Found>> resolve(Song song) {
        Want want = Want.of(song);
        return firstAlbumId(song)
                .flatMap(first -> first
                        .map(albumId -> found(albumId, want, List.of(want.own()))
                                .switchIfEmpty(Mono.defer(() -> fromSearch(want, song.durationSeconds(), albumId))))
                        .orElseGet(() -> fromSearch(want, null, null)))
                .flatMap(found -> plainest(found, want))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                // The song id itself is unknown to YouTube: no album will ever be found for it.
                .onErrorResume(YtMusicBadRequestException.class, error -> Mono.just(Optional.empty()));
    }

    private Mono<Optional<String>> firstAlbumId(Song song) {
        Mono<Optional<String>> details = Mono.defer(() -> ytMusicService.getSongDetails(song.youtubeId())
                .map(d -> Optional.ofNullable(d.getAlbum()).map(YtMusicSearchResponse.AlbumRef::getBrowseId))
                .defaultIfEmpty(Optional.empty()));
        if (song.type() != DownloadType.CURATED) {
            return details;
        }
        // Read again rather than stored at admission (no column for it). An edition replaced since,
        // or a curator that is down, just means the details are asked instead.
        return curatorClient.getEdition(song.downloadYoutubeId())
                .mapNotNull(edition -> edition.tracks().stream()
                        .filter(track -> song.youtubeId().equals(track.videoId()) && track.albumId() != null)
                        .map(CuratorTrack::albumId)
                        .findFirst().orElse(null))
                .map(Optional::of)
                .onErrorResume(error -> Mono.empty())
                .switchIfEmpty(details);
    }

    /** The album page, if it is a trusted album that has this song on it; empty otherwise. */
    private Mono<Found> found(String albumId, Want want, List<Identity> identities) {
        return ytMusicService.getAlbum(albumId)
                // A stale or wrong album id is just not this song's album; the lookup carries on.
                .onErrorResume(YtMusicBadRequestException.class, error -> Mono.empty())
                .mapNotNull(album -> match(albumId, album, want, identities));
    }

    private Mono<Found> fromSearch(Want want, Integer length, String tried) {
        List<String> wordings = SearchQueryTiers.of(want.song().songName());
        // The wording that names the artist: "Wonderwall - Oasis", not the title alone.
        String wording = wordings.size() > 1 ? wordings.get(1) : wordings.getFirst();
        return ytMusicService.searchSongRows(wording, SEARCH_ROWS)
                .flatMapMany(rows -> Flux.fromIterable(candidates(rows, want, length, tried)))
                .concatMap(row -> found(row.getAlbum().getBrowseId(), want,
                        List.of(want.own(), new Identity(row.getVideoId(), row.getDurationSeconds()))))
                .next();
    }

    /** One more album fetch, only when a plainer edition than the one found exists and has the song. */
    private Mono<Found> plainest(Found found, Want want) {
        String plainest = plainestEdition(found.albumId(), found.album(), want);
        if (plainest.equals(found.albumId())) {
            return Mono.just(found);
        }
        Identity row = new Identity(found.track().getVideoId(), found.track().getDurationSeconds());
        return found(plainest, want, List.of(want.own(), row)).defaultIfEmpty(found);
    }

    /**
     * Stamped when the answer is saved, not when the batch started: "already have it" trusts a song's
     * album only when its answer was there in time for the organiser to file it by it.
     */
    private Mono<Void> save(Song song, Optional<Found> found) {
        if (found.isEmpty()) {
            log.info("Song {} ('{}') has no YouTube Music album naviseerr trusts; it is filed under its own name",
                    song.youtubeId(), song.songName());
            return Mono.defer(() -> repository.saveSongAlbum(song.youtubeId(), null, null, clock.instant())).then();
        }
        Found f = found.get();
        YoutubeCollectionInfo album = YtMusicService.toCollectionInfo(f.album(), f.albumId());
        log.info("Song {} ('{}') is track {} of '{}' ({}) on YouTube Music", song.youtubeId(), song.songName(),
                f.track().getTrackNumber(), album.name(), f.albumId());
        // The album's row first, so the organiser never sees an album id it has no name for.
        return repository.upsertMedia(List.of(new MediaItem(f.albumId(), album.name(), album.authorNames(),
                        album.authorIds(), album.imageUrl(), null,
                        Objects.requireNonNullElse(album.trackCount(), album.songs().size()), album.year(),
                        album.type())))
                .then(Mono.defer(() -> repository.saveSongAlbum(song.youtubeId(), f.albumId(),
                        f.track().getTrackNumber(), clock.instant())))
                .then();
    }

    // ---- pure rules ------------------------------------------------------------------------------

    /**
     * The song's row on a trusted album: the row with one of the song's ids AND its title (one id can
     * sit on two rows of an album: Definitely Maybe's 30th Anniversary edition lists h7-BHdjeEY0 as
     * tracks 21 and 24), else the row with its title and length. Null when the album is not trusted
     * or does not have the song.
     */
    static Found match(String albumId, Collection album, Want want, List<Identity> identities) {
        if (!want.trusts(album.getType(), album.getArtists()) || album.getTracks() == null) {
            return null;
        }
        List<Track> sameTitle = album.getTracks().stream()
                .filter(track -> track.getTrackNumber() != null && want.sameTitle(track.getTitle()))
                .toList();
        return sameTitle.stream()
                .filter(track -> identities.stream().anyMatch(id -> Objects.equals(id.videoId(), track.getVideoId())))
                .findFirst()
                .or(() -> sameTitle.stream()
                        .filter(track -> identities.stream()
                                .anyMatch(id -> sameLength(id.durationSeconds(), track.getDurationSeconds())))
                        .findFirst())
                .map(track -> new Found(albumId, album, track))
                .orElse(null);
    }

    /**
     * The search rows whose album is worth opening: the song itself (same artist, same title) on an
     * album not tried yet, at the song's length. {@code length} null (an official video, whose own
     * length is the video's, or a curated song, which has none): the first such row sets it.
     */
    static List<YtMusicSearchResponse.Item> candidates(List<YtMusicSearchResponse.Item> rows, Want want,
                                                       Integer length, String tried) {
        List<YtMusicSearchResponse.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(tried);
        Integer reference = length;
        for (YtMusicSearchResponse.Item row : rows.subList(0, Math.min(SEARCH_ROWS, rows.size()))) {
            if (row.getAlbum() == null || row.getAlbum().getBrowseId() == null
                    || !want.sameArtist(row.getArtists()) || !want.sameTitle(row.getTitle())) {
                continue;
            }
            if (reference == null) {
                reference = row.getDurationSeconds();
            } else if (!sameLength(reference, row.getDurationSeconds())) {
                continue;
            }
            if (seen.add(row.getAlbum().getBrowseId())) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * The plainest edition of an album: the shortest title among it and its other versions that are
     * the same artist's Album or EP ("Definitely Maybe" over "Definitely Maybe (Deluxe Edition
     * Remastered)"), the smaller id on a tie. Decided from the album page alone, so two songs of one
     * playlist, and two installs, pick the same one whichever edition each was found on.
     */
    static String plainestEdition(String albumId, Collection album, Want want) {
        record Edition(String id, String title) {}
        List<Edition> editions = new ArrayList<>(List.of(new Edition(albumId, Objects.requireNonNullElse(album.getTitle(), ""))));
        if (album.getOtherVersions() != null) {
            album.getOtherVersions().stream()
                    .filter(other -> other.getBrowseId() != null && other.getTitle() != null
                            && want.trusts(other.getType(), other.getArtists()))
                    .forEach(other -> editions.add(new Edition(other.getBrowseId(), other.getTitle())));
        }
        return editions.stream()
                .min(Comparator.comparingInt((Edition e) -> e.title().length()).thenComparing(Edition::id))
                .orElseThrow()
                .id();
    }

    private static boolean sameLength(Integer a, Integer b) {
        return a != null && b != null && Math.abs(a - b) <= SAME_LENGTH_SECONDS;
    }

    /** Lower case, accents and punctuation gone, "&" read as "and", remaster and guest notes dropped. */
    static String key(String text) {
        String s = SAME_RECORDING.matcher(text == null ? "" : text).replaceAll(" ");
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("&", " and ");
        return s.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /**
     * What a song is matched by: its title and artist as {@link SearchQueryTiers} reads them from
     * {@code song_name} (version qualifiers kept, platform noise gone), compared as {@link #key}s.
     */
    record Want(Song song, String artist, String titleKey, String artistKey) {

        static Want of(Song song) {
            SearchQueryTiers.TitleAndArtist names = SearchQueryTiers.titleAndArtist(song.songName());
            return new Want(song, names.artist(), key(names.title()), key(names.artist()));
        }

        Identity own() {
            return new Identity(song.youtubeId(), song.durationSeconds());
        }

        /** A YouTube Music title, read the same way the song's own was ("Title - Artist"). */
        boolean sameTitle(String title) {
            return title != null && titleKey.equals(key(SearchQueryTiers.titleAndArtist(title + " - " + artist).title()));
        }

        boolean sameArtist(List<YtMusicSearchResponse.ArtistRef> artists) {
            return !artistKey.isEmpty() && artists != null
                    && artists.stream().anyMatch(a -> a.getName() != null && artistKey.equals(key(a.getName())));
        }

        /** The trust rule: the song artist's own Album or EP; never a Single, never Various Artists. */
        boolean trusts(String type, List<YtMusicSearchResponse.ArtistRef> artists) {
            return ("Album".equalsIgnoreCase(type) || "EP".equalsIgnoreCase(type)) && sameArtist(artists);
        }
    }
}
