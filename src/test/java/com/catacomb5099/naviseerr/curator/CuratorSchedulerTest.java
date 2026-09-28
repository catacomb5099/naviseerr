package com.catacomb5099.naviseerr.curator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.DayOfWeek;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CuratorSchedulerTest {

    private static final Duration POLL = Duration.ofMillis(20);
    private static final Duration BUDGET = Duration.ofMillis(200);

    private CuratorClient client;
    private CuratorScheduler scheduler;

    @BeforeEach
    void setUp() {
        client = mock(CuratorClient.class);
        scheduler = new CuratorScheduler(client, "http://curator", "token", "0 0 3 * * MON", POLL, BUDGET);
        when(client.triggerRun()).thenReturn(Mono.just(run("queued")));
    }

    @Test
    void offWhenTokenMissing() {
        assertTrue(!new CuratorScheduler(client, "http://curator", "", "0 0 3 * * MON", POLL, BUDGET).isEnabled());
        assertTrue(!new CuratorScheduler(client, "", "token", "0 0 3 * * MON", POLL, BUDGET).isEnabled());
        assertTrue(scheduler.isEnabled());
    }

    @Test
    void refreshDay_readsAPlainWeeklyCron_andGivesUpOnAnythingElse() {
        assertEquals(DayOfWeek.MONDAY, scheduler.getRefreshDay());
        assertEquals(DayOfWeek.WEDNESDAY, CuratorScheduler.refreshDay("0 30 4 * * wed"));
        assertEquals(DayOfWeek.SUNDAY, CuratorScheduler.refreshDay("0 0 3 * * 0"));
        assertEquals(DayOfWeek.SUNDAY, CuratorScheduler.refreshDay("0 0 3 * * 7"));
        assertEquals(DayOfWeek.FRIDAY, CuratorScheduler.refreshDay("0 0 3 * * 5"));
        assertNull(CuratorScheduler.refreshDay("0 0 3 * * MON-FRI"), "a range is not one day");
        assertNull(CuratorScheduler.refreshDay("0 0 3 * * *"), "every day is not a weekly refresh");
        assertNull(CuratorScheduler.refreshDay("0 0 3 * * MON,THU"), "two days are not one");
        assertNull(CuratorScheduler.refreshDay("0 0 3 * * 1 2026"), "seven fields are not Spring's cron");
        assertNull(CuratorScheduler.refreshDay(""));
        assertNull(CuratorScheduler.refreshDay(null));
    }

    @Test
    void refresh_pollsUntilSucceeded() {
        when(client.getRun("r1")).thenReturn(Mono.just(run("running")), Mono.just(run("running")),
                Mono.just(run("succeeded")));

        StepVerifier.create(scheduler.refresh()).verifyComplete();

        verify(client).triggerRun();
        verify(client, times(3)).getRun("r1");
    }

    @Test
    void refresh_stopsPollingOnPartial() {
        when(client.getRun("r1")).thenReturn(Mono.just(run("running")), Mono.just(run("partial")));

        StepVerifier.create(scheduler.refresh()).verifyComplete();

        verify(client, times(2)).getRun("r1");
    }

    @Test
    void refresh_aFailingPollDoesNotAbort() {
        when(client.getRun("r1")).thenReturn(Mono.just(run("running")),
                Mono.error(new CuratorException("curator poll failed: timeout", true)),
                Mono.just(run("failed")));

        StepVerifier.create(scheduler.refresh()).verifyComplete();

        verify(client, times(3)).getRun("r1");
    }

    @Test
    void refresh_stopsWhenBudgetIsSpent() {
        AtomicInteger polls = new AtomicInteger();
        when(client.getRun("r1")).thenReturn(Mono.fromSupplier(() -> {
            polls.incrementAndGet();
            return run("running");
        }));

        StepVerifier.create(scheduler.refresh())
                .expectComplete()
                .verify(BUDGET.multipliedBy(3));

        // Roughly BUDGET / POLL polls; the exact count depends on timer jitter, so only bound it.
        assertTrue(polls.get() >= 3 && polls.get() <= 12, "polls=" + polls.get());
    }

    @Test
    void refresh_triggerFailureIsLoggedNotThrown() {
        when(client.triggerRun()).thenReturn(Mono.error(new CuratorException("curator rejected the token", false)));

        StepVerifier.create(scheduler.refresh()).verifyComplete();

        verify(client, never()).getRun(any());
    }

    @Test
    void refresh_whileOneIsRunning_isSkipped() {
        when(client.getRun("r1")).thenReturn(Mono.never());

        Disposable first = scheduler.refresh().subscribe();
        StepVerifier.create(scheduler.refresh()).verifyComplete();
        first.dispose();

        verify(client, times(1)).triggerRun();
    }

    @Test
    void refresh_canRunAgainAfterThePreviousOneFinished() {
        when(client.getRun("r1")).thenReturn(Mono.just(run("succeeded")));

        StepVerifier.create(scheduler.refresh()).verifyComplete();
        StepVerifier.create(scheduler.refresh()).verifyComplete();

        verify(client, times(2)).triggerRun();
    }

    @Test
    void refreshNow_returnsTheRunAtOnce_thenFollowsItInTheBackground() {
        when(client.triggerRunOnce()).thenReturn(Mono.just(run("queued")));
        when(client.getRun("r1")).thenReturn(Mono.just(run("running")), Mono.just(run("succeeded")));

        StepVerifier.create(scheduler.refreshNow())
                .assertNext(run -> assertTrue("queued".equals(run.status())))
                .verifyComplete();

        verify(client, never()).triggerRun();
        verify(client, timeout(BUDGET.toMillis() * 3).times(2)).getRun("r1");
    }

    @Test
    void refreshNow_whileTheWeeklyRefreshIsFollowing_doesNotFollowTwice() {
        when(client.triggerRunOnce()).thenReturn(Mono.just(run("running")));
        when(client.getRun("r1")).thenReturn(Mono.never());

        Disposable weekly = scheduler.refresh().subscribe();
        StepVerifier.create(scheduler.refreshNow())
                .assertNext(run -> assertTrue("running".equals(run.status())))
                .verifyComplete();
        // Only the weekly refresh polls; a second follower would be a second getRun.
        verify(client, after(POLL.toMillis() * 5).times(1)).getRun("r1");
        weekly.dispose();
    }

    @Test
    void refreshNow_aRunAlreadyFinished_isNotPolled() {
        when(client.triggerRunOnce()).thenReturn(Mono.just(run("succeeded")));

        StepVerifier.create(scheduler.refreshNow()).expectNextCount(1).verifyComplete();

        verify(client, after(POLL.toMillis() * 3).never()).getRun(any());
    }

    @Test
    void refreshNow_aTriggerFailureReachesTheCaller() {
        when(client.triggerRunOnce()).thenReturn(Mono.error(new CuratorException("curator trigger failed", true)));

        StepVerifier.create(scheduler.refreshNow()).expectError(CuratorException.class).verify();

        verify(client, never()).getRun(any());
    }

    private static CuratorRun run(String status) {
        return new CuratorRun("r1", status, "2026-09-27T03:00:00Z", null, null,
                List.of(new CuratorCategoryResult("80s-indie-pop", "written", "2026-09-27", 40, null)));
    }
}
