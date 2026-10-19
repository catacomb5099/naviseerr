package com.catacomb5099.naviseerr.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        logged.start();
        ((Logger) LoggerFactory.getLogger(LoginThrottle.class)).addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        ((Logger) LoggerFactory.getLogger(LoginThrottle.class)).detachAppender(logged);
    }

    private void fail(String name) {
        throttle.failed(name, "203.0.113.9");
    }

    @Test
    void fiveFailuresBlock_fourDoNot() {
        for (int i = 0; i < 4; i++) fail("alex");
        assertFalse(throttle.blocked("alex"));
        fail("alex");
        assertTrue(throttle.blocked("alex"));
        assertFalse(throttle.blocked("bob"), "per name");
        assertFalse(throttle.blockedForEveryone(), "five is far from everyone's thirty");
    }

    @Test
    void theWindowIsTwentySecondsFromTheFirstFailure_thenACleanStart() {
        for (int i = 0; i < 5; i++) fail("alex");
        now.set(now.get().plusSeconds(19));
        assertTrue(throttle.blocked("alex"));
        now.set(now.get().plusSeconds(1));
        assertFalse(throttle.blocked("alex"), "the window has passed");
        fail("alex");
        assertFalse(throttle.blocked("alex"), "one failure in a fresh window");
    }

    @Test
    void aRightPasswordClears_andNamesIgnoreCaseAndSpaces() {
        for (int i = 0; i < 3; i++) fail("Alex");
        fail(" alex ");
        fail("ALEX");
        assertTrue(throttle.blocked("alex"));
        throttle.succeeded("aLeX");
        assertFalse(throttle.blocked("alex"));
    }

    @Test
    void thirtyFailuresUnderAnyNames_blockEveryone_forTheRestOfTheMinute() {
        for (int i = 0; i < 29; i++) fail("bot-" + i);
        assertFalse(throttle.blockedForEveryone());
        fail("bot-29");
        assertTrue(throttle.blockedForEveryone());
        assertFalse(throttle.blocked("alex"), "the per-name count is untouched: alex never failed");

        now.set(now.get().plusSeconds(59));
        assertTrue(throttle.blockedForEveryone());
        now.set(now.get().plusSeconds(1));
        assertFalse(throttle.blockedForEveryone(), "the minute has passed");
        fail("bot-30");
        assertFalse(throttle.blockedForEveryone(), "one failure in a fresh minute");
    }

    @Test
    void aSuccessClearsOneName_notEveryonesCount() {
        for (int i = 0; i < 30; i++) fail("bot-" + i);
        throttle.succeeded("bot-3");
        assertTrue(throttle.blockedForEveryone(), "the owner logging in during an attack does not reset the attack");
    }

    @Test
    void everyFailureIsOneWarnLineWithTheNameAndAddress_andANameCannotForgeALine() {
        throttle.failed("alex", "203.0.113.9");
        throttle.failed("bob\nWARN forged line", "10.0.0.7");

        assertEquals(2, logged.list.size());
        assertEquals(Level.WARN, logged.list.get(0).getLevel());
        assertEquals("Login failed for \"alex\" from 203.0.113.9 (1 of 30 failures this minute for everyone)", logged.list.get(0).getFormattedMessage());
        assertEquals("Login failed for \"bob?WARN forged line\" from 10.0.0.7 (2 of 30 failures this minute for everyone)", logged.list.get(1).getFormattedMessage());
    }

    @Test
    void theThirtiethFailure_addsOneLineSayingEveryoneWaits() {
        for (int i = 0; i < 31; i++) fail("bot-" + i);

        long capLines = logged.list.stream().filter(e -> e.getFormattedMessage().startsWith("Too many failed logins from everyone")).count();
        assertEquals(1, capLines, "said once, when the cap is reached, not on every refused attempt after it");
        assertEquals(32, logged.list.size());
    }
}
