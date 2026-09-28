package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soulseek sharers that accepted a request and then kept us waiting past {@code queued-budget-ms}.
 * Shared by every song in flight: once one song has burned ten minutes on a sharer, no other song
 * should burn its own ten minutes on the same one while there is anybody else to ask.
 *
 * <p>Measured 27-09-2026: two sharers (SKYLiGHT_B, musicmasterrdjpool) left 58 transfers at
 * "Queued, Remotely" 0% for the whole evening. One of them alone stalled 23 songs that later
 * succeeded elsewhere, and 12 of the 14 songs that ran out of sources had only those two to try.
 *
 * <p>ponytail: plain in-memory map, so the list is lost on restart and is per process (two
 * naviseerr instances would each learn the same lesson separately). Good enough while the cooldown
 * is hours, not days; a {@code stalling_sharers} table is the upgrade if it ever needs to survive
 * a restart.
 */
@Slf4j
@Component
public class StallingSharers {

    private final Duration cooldown;
    /** Sharer username to the moment its cooldown ends. */
    private final Map<String, Instant> until = new ConcurrentHashMap<>();

    public StallingSharers(@Value("${download-task.stalling-sharer-cooldown-ms:21600000}") Duration cooldown) {
        this.cooldown = cooldown;
    }

    public void markStalled(String username, Instant now) {
        if (username == null) {
            return;
        }
        Instant expiry = now.plus(cooldown);
        if (until.put(username, expiry) == null) {
            log.info("Sharer '{}' accepted a download and never served it; skipping its other files "
                    + "for {} while anyone else has them (until {})", username, cooldown, expiry);
        }
    }

    public boolean isStalling(String username, Instant now) {
        Instant expiry = until.get(username);
        if (expiry == null) {
            return false;
        }
        if (!now.isBefore(expiry)) {
            until.remove(username, expiry);
            return false;
        }
        return true;
    }
}
