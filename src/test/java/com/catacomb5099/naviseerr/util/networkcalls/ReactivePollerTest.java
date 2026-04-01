package com.catacomb5099.naviseerr.util.networkcalls;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactivePollerTest {

    @Test
    void emptyCallsOrNullReturnsMonoError() {
        Mono<String> mono = ReactivePoller.pollUntilAny(
                Collections.emptyList(),
                t -> false,
                t -> false,
                ReactivePoller.defaultBackoff(Duration.ofMillis(1), 1),
                (Function<String, Supplier<Mono<String>>>) m -> () -> Mono.just(m),
                0
        );

        StepVerifier.create(mono)
                .expectErrorMatches(e -> e instanceof IllegalArgumentException && e.getMessage().contains("No suppliers provided"))
                .verify();
    }

    @SuppressWarnings("unchecked")
    @Test
    void oneCallInListExpectACallToBeMade() {
        Supplier<Mono<String>> supplier = mock(Supplier.class);
        when(supplier.get()).thenReturn(Mono.just("m-result"));

        Supplier<Mono<String>> tSupplier = () -> Mono.just("t-result");
        Function<String, Supplier<Mono<String>>> mToTSupplier = m -> tSupplier;

        Mono<String> mono = ReactivePoller.pollUntilAny(
                Collections.singletonList(supplier),
                "t-result"::equals,
               t -> false,
                ReactivePoller.defaultBackoff(Duration.ofMillis(1), 1),
                mToTSupplier,
                0
        );

        StepVerifier.create(mono)
                .expectNext("t-result")
                .verifyComplete();

        verify(supplier, times(1)).get();
    }

    @SuppressWarnings("unchecked")
    @Test
    void oneCallGivenRetryEqualsZeroFailsDoesntRetry() {
        Supplier<Mono<String>> supplier = mock(Supplier.class);
        when(supplier.get()).thenReturn(Mono.just("m-result"));

        Supplier<Mono<String>> tSupplier = () -> Mono.just("IN_PROGRESS");
        Function<String, Supplier<Mono<String>>> mToTSupplier = m -> tSupplier;

        var retrySpec = ReactivePoller.defaultBackoff(Duration.ofMillis(1), 1);

        Mono<String> mono = ReactivePoller.pollUntilAny(
                Collections.singletonList(supplier),
                "SUCCESS"::equals,
                "FAILURE"::equals,
                retrySpec,
                mToTSupplier,
                0
        );

        StepVerifier.create(mono)
                .expectErrorMatches(e -> e instanceof RuntimeException)
                .verify();
    }

    @SuppressWarnings("unchecked")
    @Test
    void successInMCallTCallIsMade_mToTSupplierWorks() {
        Supplier<Mono<String>> mSupplier = mock(Supplier.class);
        when(mSupplier.get()).thenReturn(Mono.just("m-success"));

        Supplier<Mono<String>> tSupplierMock = mock(Supplier.class);
        when(tSupplierMock.get()).thenReturn(Mono.just("t-success"));

        Function<String, Supplier<Mono<String>>> mToTSupplier = m -> tSupplierMock;

        Mono<String> mono = ReactivePoller.pollUntilAny(
                Collections.singletonList(mSupplier),
                "t-success"::equals,
               t -> false,
                ReactivePoller.defaultBackoff(Duration.ofMillis(1), 1),
                mToTSupplier,
                0
        );

        StepVerifier.create(mono)
                .expectNext("t-success")
                .verifyComplete();

        verify(mSupplier, times(1)).get();
        verify(tSupplierMock, times(1)).get();
    }

    @SuppressWarnings("unchecked")
    @Test
    void oneCallGivenRetryOverZeroFailsRetryEqualToTheRetryCount() {
        Supplier<Mono<String>> supplier = mock(Supplier.class);
        when(supplier.get()).thenReturn(Mono.just("m-result"));

        // use a mocked tSupplier so we can verify how many times it was invoked
        Supplier<Mono<String>> tSupplierMock = mock(Supplier.class);
        when(tSupplierMock.get()).thenReturn(Mono.just("IN_PROGRESS"));
        Function<String, Supplier<Mono<String>>> mToTSupplier = m -> tSupplierMock;

        int retryCount = 0;
        var retrySpec = ReactivePoller.defaultBackoff(Duration.ofMillis(1), retryCount + 1);

        Mono<String> mono = ReactivePoller.pollUntilAny(
                Collections.singletonList(supplier),
                "SUCCESS"::equals,
                "FAILURE"::equals,
                retrySpec,
                mToTSupplier,
                0
        );

        StepVerifier.create(mono)
                .expectErrorMatches(e -> e instanceof RuntimeException)
                .verify();

        // initial attempt + retryCount retries
        verify(tSupplierMock, times(retryCount + 2)).get();
        verify(supplier, times(1)).get();
    }

    @SuppressWarnings("unchecked")
    @Test
    void twoCallInCallListSucceedsSecondCallNotMade() {
        Supplier<Mono<String>> m1 = mock(Supplier.class);
        Supplier<Mono<String>> m2 = mock(Supplier.class);
        when(m1.get()).thenReturn(Mono.just("m1"));
        when(m2.get()).thenReturn(Mono.just("m2"));

        Supplier<Mono<String>> t1 = mock(Supplier.class);
        when(t1.get()).thenReturn(Mono.just("T-OK"));

        Supplier<Mono<String>> t2 = mock(Supplier.class);
        when(t2.get()).thenReturn(Mono.just("SHOULD-NOT-BE-CALLED"));

        Function<String, Supplier<Mono<String>>> mToTSupplier = m -> "m1".equals(m) ? t1 : t2;

        Mono<String> mono = ReactivePoller.pollUntilAny(
                List.of(m1, m2),
                "T-OK"::equals,
                t -> false,
                ReactivePoller.defaultBackoff(Duration.ofMillis(1), 1),
                mToTSupplier,
                0
        );

        StepVerifier.create(mono)
                .expectNext("T-OK")
                .verifyComplete();

        verify(m1, times(1)).get();
        verify(t1, times(1)).get();
        verify(m2, times(0)).get();
        verify(t2, times(0)).get();
    }

    @SuppressWarnings("unchecked")
    @Test
    void twoCallFirstRetriesSucceedSecondNotMade() {
        Supplier<Mono<String>> m1 = mock(Supplier.class);
        Supplier<Mono<String>> m2 = mock(Supplier.class);
        when(m1.get()).thenReturn(Mono.just("m1"));
        when(m2.get()).thenReturn(Mono.just("m2"));

        Supplier<Mono<String>> t1 = mock(Supplier.class);
        // first attempt in progress, second attempt succeeds
        when(t1.get()).thenReturn(Mono.just("IN_PROGRESS"), Mono.just("SUCCESS"));

        Supplier<Mono<String>> t2 = mock(Supplier.class);
        when(t2.get()).thenReturn(Mono.just("SHOULD-NOT-BE-CALLED"));

        Function<String, Supplier<Mono<String>>> mToTSupplier = m -> "m1".equals(m) ? t1 : t2;

        var retrySpec = ReactivePoller.defaultBackoff(Duration.ofMillis(1), 2);

        Mono<String> mono = ReactivePoller.pollUntilAny(
                List.of(m1, m2),
                "SUCCESS"::equals,
                t -> false,
                retrySpec,
                mToTSupplier,
                0
        );

        StepVerifier.create(mono)
                .expectNext("SUCCESS")
                .verifyComplete();

        // t1 should have been called twice (initial + one retry)
        verify(t1, times(2)).get();
        verify(m1, times(1)).get();
        verify(m2, times(0)).get();
        verify(t2, times(0)).get();
    }

    @SuppressWarnings("unchecked")
    @Test
    void twoCallFirstRetriesFailSecondMade() {
        Supplier<Mono<String>> m1 = mock(Supplier.class);
        Supplier<Mono<String>> m2 = mock(Supplier.class);
        when(m1.get()).thenReturn(Mono.just("m1"));
        when(m2.get()).thenReturn(Mono.just("m2"));

        Supplier<Mono<String>> t1 = mock(Supplier.class);
        // always fail (maps to PollingFailedException)
        when(t1.get()).thenReturn(Mono.just("FAILURE"));

        Supplier<Mono<String>> t2 = mock(Supplier.class);
        when(t2.get()).thenReturn(Mono.just("SUCCESS"));

        Function<String, Supplier<Mono<String>>> mToTSupplier = m -> "m1".equals(m) ? t1 : t2;

        int individualFailRetries = 1; // retry the failing supplier once before moving on

        Mono<String> mono = ReactivePoller.pollUntilAny(
                List.of(m1, m2),
                "SUCCESS"::equals,
                "FAILURE"::equals,
                ReactivePoller.defaultBackoff(Duration.ofMillis(1), 1),
                mToTSupplier,
                individualFailRetries
        );

        StepVerifier.create(mono)
                .expectNext("SUCCESS")
                .verifyComplete();

        // m1 and t1 should have been invoked twice (initial + retry), then m2/t2 once
        verify(m1, times(2)).get();
        verify(t1, times(2)).get();
        verify(m2, times(1)).get();
        verify(t2, times(1)).get();
    }

    @SuppressWarnings("unchecked")
    @Test
    void listOf10CallsAllFailReturnError() {
        Supplier<Mono<String>> mSupplier = mock(Supplier.class);
        when(mSupplier.get()).thenReturn(Mono.just("m"));

        Supplier<Mono<String>> tSupplier = mock(Supplier.class);
        when(tSupplier.get()).thenReturn(Mono.just("FAILURE"));

        Function<String, Supplier<Mono<String>>> mToTSupplier = m -> tSupplier;

        var calls = Collections.nCopies(10, mSupplier);

        Mono<String> mono = ReactivePoller.pollUntilAny(
                calls,
                "SUCCESS"::equals,
                "FAILURE"::equals,
                ReactivePoller.defaultBackoff(Duration.ofMillis(1), 1),
                mToTSupplier,
                0
        );

        // Implementation returns Mono.empty() when all calls are exhausted
        StepVerifier.create(mono)
                .verifyError();

        // each entry in the list should have been invoked once
        verify(mSupplier, times(10)).get();
        verify(tSupplier, times(10)).get();
    }
}
