package com.catacomb5099.naviseerr.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTest {

    /** A clock the test moves by hand. */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final LoginThrottle throttle = new LoginThrottle(clock);

    @Test
    void fiveFailuresBlock_fourDoNot() {
        for (int i = 0; i < 4; i++) throttle.failed("alex");
        assertFalse(throttle.blocked("alex"));
        throttle.failed("alex");
        assertTrue(throttle.blocked("alex"));
        assertFalse(throttle.blocked("bob"), "per name");
    }

    @Test
    void theWindowIsTwentySecondsFromTheFirstFailure_thenACleanStart() {
        for (int i = 0; i < 5; i++) throttle.failed("alex");
        now.set(now.get().plusSeconds(19));
        assertTrue(throttle.blocked("alex"));
        now.set(now.get().plusSeconds(1));
        assertFalse(throttle.blocked("alex"), "the window has passed");
        throttle.failed("alex");
        assertFalse(throttle.blocked("alex"), "one failure in a fresh window");
    }

    @Test
    void aRightPasswordClears_andNamesIgnoreCaseAndSpaces() {
        for (int i = 0; i < 3; i++) throttle.failed("Alex");
        throttle.failed(" alex ");
        throttle.failed("ALEX");
        assertTrue(throttle.blocked("alex"));
        throttle.succeeded("aLeX");
        assertFalse(throttle.blocked("alex"));
    }
}
