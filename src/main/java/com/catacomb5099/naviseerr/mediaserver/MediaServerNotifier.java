package com.catacomb5099.naviseerr.mediaserver;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Tells the self-hoster's music servers to look at the library again once a download has been filed
 * -- the "Update Library" connection Lidarr, Sonarr and Radarr ship, because the servers' own folder
 * watchers see nothing over a NAS share, from another machine or under Docker Desktop (measured
 * 19-10-2026: Navidrome missed a new song for 3+ minutes; an explicit scan found it in under a second).
 *
 * <p>The download loop calls {@link #libraryChanged()} when a download was stamped organised and
 * {@link #flush(Instant)} at the end of every pass. One call per configured server, at most one every
 * {@code media-servers.rescan-gap-ms} (trailing: a change during the gap goes out when it ends, which
 * also covers Navidrome dropping a scan request while it is already scanning). The first flush after
 * start sends one unconditionally: a wrong URL, password or non-admin user shows up in the log and in
 * {@code GET /status} within a minute of starting, not at the first finished download, and anything
 * filed right before a restart is announced. Best effort: a server that cannot be reached costs one
 * WARN when it first fails and one INFO when it works again, never a failed pass.
 *
 * <p>Jellyfin reads no picture file for playlists, so after its rescan the picture the organiser wrote
 * beside a playlist file ({@link #playlistWritten}) is sent through Jellyfin's image API once Jellyfin
 * has imported the playlist (polled, up to {@link #COVER_POLLS} times): fire and forget, off the pass.
 *
 * <p>Off (nothing configured, nothing logged beyond one line at boot) unless {@code NAVIDROME_URL} or
 * {@code JELLYFIN_URL} is set. See docs/decisions/media-server-rescan-19-10-2026.md.
 */
// ponytail: the pending flag and the last outcomes live in memory and are lost on restart; the boot
// mark covers the restart case. A settings table (and the in-app "Connect" screen Lidarr has) waits
// for the login, since a Navidrome admin password must not be shown on an open LAN port.
@Slf4j
@Component
public class MediaServerNotifier {

    /** One configured server as {@code GET /status} shows it: {@code problem} is null while it answers. */
    public record MediaServerStatus(String name, String url, String problem, Instant lastRescanAt) {}

    /** How many times, {@code cover-poll-ms} apart, to ask Jellyfin for a just-written playlist before giving up on its picture (36 x 5 s = 3 min). */
    static final int COVER_POLLS = 36;

    private final List<Target> targets = new ArrayList<>();
    private final Duration gap;
    private final Duration coverPoll;
    /** Set when Jellyfin is configured with a key; the only server whose playlist pictures need pushing. */
    private final JellyfinClient jellyfin;
    private Target jellyfinTarget;
    /** Playlist files written since the last Jellyfin rescan, whose sidecar picture is still to push. */
    private final Set<Path> pendingCovers = ConcurrentHashMap.newKeySet();
    private volatile boolean pending = true;   // the boot check
    private volatile Instant lastSentAt;

    public MediaServerNotifier(NavidromeClient navidrome, JellyfinClient jellyfin, LibraryOrganiser organiser,
                               @Value("${media-servers.rescan-gap-ms}") Duration gap,
                               @Value("${media-servers.cover-poll-ms}") Duration coverPoll) {
        this.gap = gap;
        this.coverPoll = coverPoll;
        if (navidrome.isConfigured()) {
            if (navidrome.hasCredentials()) {
                targets.add(new Target("Navidrome", navidrome.url(), navidrome::rescan));
            } else {
                log.warn("NAVIDROME_URL is set but NAVIDROME_USER / NAVIDROME_PASSWORD are not; Navidrome is not told about new music");
            }
        }
        if (jellyfin.isConfigured()) {
            if (jellyfin.hasCredentials()) {
                jellyfinTarget = new Target("Jellyfin", jellyfin.url(), jellyfin::rescan);
                targets.add(jellyfinTarget);
            } else {
                log.warn("JELLYFIN_URL is set but JELLYFIN_API_KEY is not; Jellyfin is not told about new music");
            }
        }
        this.jellyfin = jellyfinTarget == null ? null : jellyfin;
        if (targets.isEmpty()) {
            log.info("Media servers: none configured (set NAVIDROME_URL or JELLYFIN_URL to have them rescan after a download)");
        } else {
            log.info("Media servers: {}; asked to rescan after each filed download, at most once every {} s",
                    String.join(", ", targets.stream().map(t -> t.name + " " + t.url).toList()), gap.toSeconds());
            if (!organiser.isEnabled()) {
                log.warn("Media servers are configured but the library organiser is OFF: nothing is ever filed, so "
                        + "they are only asked to rescan once at start");
            }
        }
    }

    /** Something new is in the library (a download was stamped organised). Cheap; call it as often as you like. */
    public void libraryChanged() {
        pending = true;
    }

    /**
     * A playlist file was (re)written; its picture, if the organiser wrote one beside it, goes to Jellyfin
     * after the next rescan. Nothing to do without Jellyfin.
     */
    public void playlistWritten(Path m3u8) {
        if (jellyfin != null) {
            pendingCovers.add(m3u8);
        }
    }

    /** Sends the pending rescan to every configured server when the gap since the last one has passed. */
    public Mono<Void> flush(Instant now) {
        if (targets.isEmpty() || !pending || (lastSentAt != null && now.isBefore(lastSentAt.plus(gap)))) {
            return Mono.empty();
        }
        pending = false;
        lastSentAt = now;
        return Flux.fromIterable(targets).flatMap(target -> target.send(now)).then()
                .then(Mono.fromRunnable(this::pushPendingCovers));
    }

    /** Off the pass: Jellyfin needs up to a minute or two to import a new playlist file. Kept for the next rescan when this one failed. */
    private void pushPendingCovers() {
        if (jellyfin == null || pendingCovers.isEmpty() || jellyfinTarget.problem != null) {
            return;
        }
        List<Path> covers = new ArrayList<>(pendingCovers);
        pendingCovers.removeAll(covers);
        covers.forEach(m3u8 -> pushCover(m3u8).subscribe());
    }

    /**
     * Reads {@code <title>.jpg} beside the playlist file (none: nothing to push), waits for Jellyfin to list
     * the playlist, then sets its picture. Best effort: one WARN when Jellyfin never lists it or refuses.
     */
    Mono<Void> pushCover(Path m3u8) {
        String fileName = m3u8.getFileName().toString();
        String title = fileName.endsWith(".m3u8") ? fileName.substring(0, fileName.length() - 5) : fileName;
        Path jpg = m3u8.resolveSibling(title + ".jpg");
        return Mono.fromCallable(() -> Files.readAllBytes(jpg))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(NoSuchFileException.class, none -> {
                    log.debug("Playlist '{}' has no picture file; nothing to send to Jellyfin", title);
                    return Mono.empty();
                })
                // defer: each repeat must ask Jellyfin again, not resubscribe to the first answer.
                .flatMap(bytes -> Mono.defer(() -> jellyfin.findPlaylist(fileName))
                        .repeatWhenEmpty(tries -> tries.take(COVER_POLLS).delayElements(coverPoll))
                        // On the lookup, before the push: the push itself completes empty.
                        .switchIfEmpty(Mono.<String>fromRunnable(() -> log.warn("Jellyfin has not imported playlist '{}' "
                                + "after {}; it keeps Jellyfin's own collage until the playlist is finalised again", title,
                                coverPoll.multipliedBy(COVER_POLLS))))
                        .flatMap(id -> jellyfin.setPrimaryImage(id, bytes)
                                .doOnSuccess(v -> log.info("Jellyfin: set the picture of playlist '{}'", title))))
                .onErrorResume(error -> {
                    log.warn("Could not send the picture of playlist '{}' to Jellyfin: {}", title, error.getMessage());
                    return Mono.empty();
                });
    }

    /** Every configured server, in configuration order; empty when none is. */
    public List<MediaServerStatus> status() {
        return targets.stream().map(t -> new MediaServerStatus(t.name, t.url, t.problem, t.lastRescanAt)).toList();
    }

    private static final class Target {
        final String name;
        final String url;
        final Supplier<Mono<Void>> rescan;
        volatile String problem;
        volatile Instant lastRescanAt;

        Target(String name, String url, Supplier<Mono<Void>> rescan) {
            this.name = name;
            this.url = url;
            this.rescan = rescan;
        }

        Mono<Void> send(Instant now) {
            return Mono.defer(rescan::get)
                    .doOnSuccess(v -> {
                        if (problem != null) {
                            log.info("{} can be reached again", name);
                        }
                        problem = null;
                        lastRescanAt = now;
                        log.info("Asked {} to rescan the library", name);
                    })
                    .onErrorResume(error -> {
                        String reason = error.getMessage() == null ? error.toString() : error.getMessage();
                        if (reason.equals(problem)) {
                            log.debug("Still cannot ask {} to rescan: {}", name, reason);
                        } else {
                            log.warn("Could not ask {} to rescan the library: {}", name, reason);
                        }
                        problem = reason;
                        return Mono.empty();
                    });
        }
    }
}
