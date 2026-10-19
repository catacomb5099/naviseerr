package com.catacomb5099.naviseerr.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>And a cap per caller's address (19-10-2026, for an install reachable from the internet): after
 * {@link #MAX_FAILURES_PER_ADDRESS} failed logins or setup codes within {@link #ADDRESS_WINDOW} from one
 * address, whatever the names, that address answers 429 for the rest of the window. A bot cycling
 * through made-up names never trips the per-name count; this stops it from burning bcrypt time and
 * filling the log, and it locks out only itself: the owner on another address logs in as usual. (The
 * first shape was one cap shared by everyone; a single bot could then hold the owner's login shut for
 * as long as it ran.) Each failure is one WARN line with the caller's address, see
 * {@link AuthController#clientAddress}.
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

    static final int MAX_FAILURES_PER_ADDRESS = 30;
    static final Duration ADDRESS_WINDOW = Duration.ofMinutes(1);
    static final String TOO_MANY_FROM_ADDRESS = "Too many attempts, wait a minute";

    private record Strikes(Instant since, int count) {
        boolean live(Instant now, Duration window) { return now.isBefore(since.plus(window)); }
        Strikes another(Instant now, Duration window) {
            return live(now, window) ? new Strikes(since, count + 1) : new Strikes(now, 1);
        }
    }

    private final ConcurrentHashMap<String, Strikes> byName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Strikes> byAddress = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** True while this name has used up its attempts. */
    public boolean blocked(String username) {
        return blocked(byName, key(username), MAX_FAILURES, WINDOW);
    }

    /** True while this address has used up its minute's attempts, logins and setup codes together. */
    public boolean blockedAddress(String address) {
        return blocked(byAddress, address, MAX_FAILURES_PER_ADDRESS, ADDRESS_WINDOW);
    }

    private boolean blocked(ConcurrentHashMap<String, Strikes> map, String key, int max, Duration window) {
        Strikes s = map.get(key);
        return s != null && s.count() >= max && s.live(clock.instant(), window);
    }

    /** One wrong password for this name, from this address. */
    public void failed(String username, String address) {
        Instant now = clock.instant();
        // A flood of made-up names would grow the map without end; drop the spent windows first.
        if (byName.size() > 10_000) byName.values().removeIf(s -> !s.live(now, WINDOW));
        byName.compute(key(username), (k, s) -> s == null ? new Strikes(now, 1) : s.another(now, WINDOW));
        strike(address, "Login failed for \"" + printable(username) + "\"", now);
    }

    /** One wrong setup code from this address: there is no account name to count it under. */
    public void failedSetup(String address) {
        strike(address, "Wrong setup code", clock.instant());
    }

    private void strike(String address, String what, Instant now) {
        if (byAddress.size() > 10_000) byAddress.values().removeIf(s -> !s.live(now, ADDRESS_WINDOW));
        Strikes here = byAddress.compute(address, (k, s) -> s == null ? new Strikes(now, 1) : s.another(now, ADDRESS_WINDOW));
        log.warn("{} from {} ({} of {} failures this minute from this address)", what, address, here.count(), MAX_FAILURES_PER_ADDRESS);
        if (here.count() == MAX_FAILURES_PER_ADDRESS) {
            log.warn("Too many failed logins from {}: refusing its login and setup attempts for the rest of the minute", address);
        }
    }

    public void succeeded(String username) {
        byName.remove(key(username));
    }

    /** "Alex" and " alex" are one account, so one count. */
    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** What goes in the log: no longer than a real name can be, and no control characters, so a name can neither forge a line nor flood the log. */
    private static String printable(String username) {
        String cut = username.length() > AuthController.MAX_USERNAME ? username.substring(0, AuthController.MAX_USERNAME) + "..." : username;
        return cut.replaceAll("\\p{Cntrl}", "?");
    }
}
