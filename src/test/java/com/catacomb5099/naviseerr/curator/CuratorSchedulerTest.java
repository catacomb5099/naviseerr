package com.catacomb5099.naviseerr.curator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
        scheduler = new CuratorScheduler(client, "http://curator", "token", POLL, BUDGET);
        when(client.triggerRun()).thenReturn(Mono.just(run("queued")));
    }

    @Test
    void offWhenTokenMissing() {
        assertTrue(!new CuratorScheduler(client, "http://curator", "", POLL, BUDGET).isEnabled());
        assertTrue(!new CuratorScheduler(client, "", "token", POLL, BUDGET).isEnabled());
        assertTrue(scheduler.isEnabled());
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

    private static CuratorRun run(String status) {
        return new CuratorRun("r1", status, "2026-09-27T03:00:00Z", null, null,
                List.of(new CuratorCategoryResult("80s-indie-pop", "written", "2026-09-27", 40, null)));
    }
}
