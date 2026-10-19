package com.catacomb5099.naviseerr.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Slows password guessing: after {@link #MAX_FAILURES} wrong passwords for one username within
 * {@link #WINDOW} of the first, login answers 429 for the rest of that window, right password or not.
 * Navidrome's numbers (AuthRequestLimit 5, AuthWindowLength 20 s). A right password clears the name.
 *
 * <p>And a cap for everyone together (19-10-2026, for an install reachable from the internet): after
 * {@link #GLOBAL_MAX_FAILURES} failures within {@link #GLOBAL_WINDOW}, whatever the names, every login
 * and setup attempt answers 429 for the rest of that window. A bot cycling through made-up names never
 * trips the per-name count; this stops it from burning bcrypt time and filling the log, at the price of
 * the owner waiting out the same minute. Each failure is one WARN line with the caller's address.
 *
 * <p>ponytail: in memory, per process. A restart forgets, and two naviseerr processes would each allow
 * their own counts; a row in Postgres would fix both if anyone ever runs two.
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofSeconds(20);
    static final String TOO_MANY = "Too many attempts, wait a moment";

    static final int GLOBAL_MAX_FAILURES = 30;
    static final Duration GLOBAL_WINDOW = Duration.ofMinutes(1);
    static final String TOO_MANY_FOR_EVERYONE = "Too many attempts, wait a minute";

    private record Strikes(Instant since, int count) {
        boolean live(Instant now, Duration window) { return now.isBefore(since.plus(window)); }
        Strikes another(Instant now, Duration window) {
            return live(now, window) ? new Strikes(since, count + 1) : new Strikes(now, 1);
        }
    }

    private final ConcurrentHashMap<String, Strikes> strikes = new ConcurrentHashMap<>();
    private final AtomicReference<Strikes> everyone = new AtomicReference<>();
    private final Clock clock;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** True while this name has used up its attempts. */
    public boolean blocked(String username) {
        Strikes s = strikes.get(key(username));
        return s != null && s.count() >= MAX_FAILURES && s.live(clock.instant(), WINDOW);
    }

    /** True while everyone together has used up this minute's attempts. */
    public boolean blockedForEveryone() {
        Strikes s = everyone.get();
        return s != null && s.count() >= GLOBAL_MAX_FAILURES && s.live(clock.instant(), GLOBAL_WINDOW);
    }

    /** One wrong password or setup code, from this address (see {@link AuthController#clientAddress}). */
    public void failed(String username, String address) {
        Instant now = clock.instant();
        // A flood of made-up names would grow the map without end; drop the spent windows first.
        if (strikes.size() > 10_000) strikes.values().removeIf(s -> !s.live(now, WINDOW));
        strikes.compute(key(username), (k, s) -> s == null ? new Strikes(now, 1) : s.another(now, WINDOW));
        Strikes all = everyone.updateAndGet(s -> s == null ? new Strikes(now, 1) : s.another(now, GLOBAL_WINDOW));
        log.warn("Login failed for \"{}\" from {} ({} of {} failures this minute for everyone)",
                printable(username), address, all.count(), GLOBAL_MAX_FAILURES);
        if (all.count() == GLOBAL_MAX_FAILURES) {
            log.warn("Too many failed logins from everyone: refusing every login and setup attempt for the rest of the minute");
        }
    }

    public void succeeded(String username) {
        strikes.remove(key(username));
    }

    /** "Alex" and " alex" are one account, so one count. */
    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** What goes in the log: no control characters (a name is anything up to 30 characters), so a name cannot forge a line. */
    private static String printable(String username) {
        return username.replaceAll("\\p{Cntrl}", "?");
    }
}
