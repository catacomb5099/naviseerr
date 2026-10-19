package com.catacomb5099.naviseerr.mediaserver;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    private final List<Target> targets = new ArrayList<>();
    private final Duration gap;
    private volatile boolean pending = true;   // the boot check
    private volatile Instant lastSentAt;

    public MediaServerNotifier(NavidromeClient navidrome, JellyfinClient jellyfin, LibraryOrganiser organiser,
                               @Value("${media-servers.rescan-gap-ms}") Duration gap) {
        this.gap = gap;
        if (navidrome.isConfigured()) {
            if (navidrome.hasCredentials()) {
                targets.add(new Target("Navidrome", navidrome.url(), navidrome::rescan));
            } else {
                log.warn("NAVIDROME_URL is set but NAVIDROME_USER / NAVIDROME_PASSWORD are not; Navidrome is not told about new music");
            }
        }
        if (jellyfin.isConfigured()) {
            if (jellyfin.hasCredentials()) {
                targets.add(new Target("Jellyfin", jellyfin.url(), jellyfin::rescan));
            } else {
                log.warn("JELLYFIN_URL is set but JELLYFIN_API_KEY is not; Jellyfin is not told about new music");
            }
        }
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

    /** Sends the pending rescan to every configured server when the gap since the last one has passed. */
    public Mono<Void> flush(Instant now) {
        if (targets.isEmpty() || !pending || (lastSentAt != null && now.isBefore(lastSentAt.plus(gap)))) {
            return Mono.empty();
        }
        pending = false;
        lastSentAt = now;
        return Flux.fromIterable(targets).flatMap(target -> target.send(now)).then();
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
