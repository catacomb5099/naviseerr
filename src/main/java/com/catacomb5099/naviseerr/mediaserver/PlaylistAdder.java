package com.catacomb5099.naviseerr.mediaserver;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Puts songs into the Navidrome/Jellyfin playlists people chose before they had them ({@code playlist_additions},
 * V23): once a song is filed, the media server is searched for it ({@link MediaItems#find}) and, unless the
 * playlist already holds it, it is added. Its own loop, not a step of the download loop: when nothing is
 * waiting a pass costs one indexed SELECT. A song the media server does not list yet is tried again after
 * {@link #BACKOFF} (the third miss also asks for a rescan, in case one was dropped), for up to
 * {@link #GIVE_UP_AFTER} after it was filed. Songs go in as they become ready, oldest request first.
 */
// ponytail: a fixed poll rather than a nudge from the organiser; one indexed SELECT per pass while idle.
@Slf4j
@Component
public class PlaylistAdder {

    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1),
            Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10));
    static final Duration GIVE_UP_AFTER = Duration.ofHours(24);
    static final int RESCAN_ON_MISS = 3;
    private static final int BATCH = 50;

    private final PlaylistAdditionRepository repository;
    private final MediaItems items;
    private final MediaServerNotifier notifier;
    private final Clock clock;
    private final Duration interval;
    private Disposable loop;

    public PlaylistAdder(PlaylistAdditionRepository repository, MediaItems items, MediaServerNotifier notifier, Clock clock,
                         @Value("${media-servers.playlist-additions-interval-ms}") Duration interval) {
        this.repository = repository;
        this.items = items;
        this.notifier = notifier;
        this.clock = clock;
        this.interval = interval;
    }

    @PostConstruct
    void start() {
        loop = Flux.interval(interval)
                .onBackpressureDrop()
                .concatMap(tick -> pass(clock.instant())
                        .onErrorResume(error -> {
                            log.warn("Playlist additions pass failed; next pass in {} s: {}", interval.toSeconds(), error.toString());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (loop != null) {
            loop.dispose();
        }
    }

    Mono<Void> pass(Instant now) {
        return repository.due(now, BATCH).concatMap(row -> handle(row, now)).then();
    }

    private Mono<Void> handle(PlaylistAdditionRepository.Due row, Instant now) {
        if (!items.connected(row.server())) {
            return giveUp(row, "MEDIA_SERVER_NOT_CONNECTED", now);
        }
        if (row.song() == null) {
            return giveUp(row, "NOT_IN_DOWNLOAD", now);
        }
        return items.find(row.server(), row.song())
                .flatMap(found -> items.playlist(row.server(), row.playlistId(), row.jellyfinUserId())
                        .switchIfEmpty(Mono.error(new PlaylistProblem(PlaylistProblem.Reason.PLAYLIST_GONE, "no such playlist")))
                        // Already in it (a crash between the add and the stamp, or added by hand): counts as added.
                        .flatMap(playlist -> playlist.notEditable()
                                ? Mono.<Void>error(new PlaylistProblem(PlaylistProblem.Reason.NOT_EDITABLE, "not editable"))
                                : playlist.songIds().contains(found.id()) ? Mono.<Void>empty()
                                : items.addToPlaylist(row.server(), row.playlistId(), row.jellyfinUserId(), found.id()))
                        .then(repository.added(row.additionId(), found.id(), now))
                        .doOnSuccess(v -> log.info("Added '{}' to {} playlist {}", row.song().title(),
                                row.server().displayName, row.playlistId()))
                        .thenReturn(true))
                .switchIfEmpty(Mono.defer(() -> missed(row, now)).thenReturn(false))
                .then()
                .onErrorResume(PlaylistProblem.class, problem -> giveUp(row, problem.reason.name(), now))
                .onErrorResume(error -> repository.tryLater(row.additionId(), row.tries() + 1, now.plus(backoff(row.tries())),
                        error.getMessage()));
    }

    private Mono<Void> missed(PlaylistAdditionRepository.Due row, Instant now) {
        if (row.filedAt() != null && now.isAfter(row.filedAt().plus(GIVE_UP_AFTER))) {
            return giveUp(row, "NOT_FOUND_IN_MEDIA_SERVER", now);
        }
        if (row.tries() + 1 == RESCAN_ON_MISS) {
            notifier.libraryChanged();
        }
        return repository.tryLater(row.additionId(), row.tries() + 1, now.plus(backoff(row.tries())),
                row.server().displayName + " does not list it yet");
    }

    private Mono<Void> giveUp(PlaylistAdditionRepository.Due row, String reason, Instant now) {
        log.warn("Gave up adding song {} to {} playlist {}: {}", row.youtubeId(), row.server().displayName, row.playlistId(), reason);
        return repository.giveUp(row.additionId(), reason, now);
    }

    static Duration backoff(int tries) {
        return BACKOFF.get(Math.min(tries, BACKOFF.size() - 1));
    }
}
