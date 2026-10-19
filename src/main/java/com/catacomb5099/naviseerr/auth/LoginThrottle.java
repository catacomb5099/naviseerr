package com.catacomb5099.naviseerr.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows password guessing: after {@link #MAX_FAILURES} wrong passwords for one username within
 * {@link #WINDOW} of the first, login answers 429 for the rest of that window, right password or not.
 * Navidrome's numbers (AuthRequestLimit 5, AuthWindowLength 20 s). A right password clears the name.
 * ponytail: in memory, per process. A restart forgets, and two naviseerr processes would each allow
 * five; a row in Postgres would fix both if anyone ever runs two.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofSeconds(20);
    static final String TOO_MANY = "Too many attempts, wait a moment";

    private record Strikes(Instant since, int count) {
        boolean live(Instant now) { return now.isBefore(since.plus(WINDOW)); }
    }

    private final ConcurrentHashMap<String, Strikes> strikes = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** True while this name has used up its attempts. */
    public boolean blocked(String username) {
        Strikes s = strikes.get(key(username));
        return s != null && s.count() >= MAX_FAILURES && s.live(clock.instant());
    }

    public void failed(String username) {
        Instant now = clock.instant();
        // A flood of made-up names would grow the map without end; drop the spent windows first.
        if (strikes.size() > 10_000) strikes.values().removeIf(s -> !s.live(now));
        strikes.compute(key(username), (k, s) -> s == null || !s.live(now) ? new Strikes(now, 1) : new Strikes(s.since(), s.count() + 1));
    }

    public void succeeded(String username) {
        strikes.remove(key(username));
    }

    /** "Alex" and " alex" are one account, so one count. */
    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
