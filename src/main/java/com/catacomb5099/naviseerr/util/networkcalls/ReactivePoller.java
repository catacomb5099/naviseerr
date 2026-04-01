package com.catacomb5099.naviseerr.util.networkcalls;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class ReactivePoller {

    private static <T> Mono<T> pollUntil(
        Supplier<Mono<T>> call,
        Predicate<T> isSuccess,
        Predicate<T> isFailure,
        RetryBackoffSpec pollSpec
    ) {
        return Mono.defer(call)
                .flatMap(response -> {
                    if (isFailure.test(response)) {
                        return Mono.error(new PollingFailedException("Polling failed due to failure state"));
                    } else if (isSuccess.test(response)) {
                        return Mono.just(response);
                    } else {
                        return Mono.error(new PollingInProgressException("Still processing"));
                    }
                })
                .retryWhen(
                        pollSpec.filter(ex -> ex instanceof PollingInProgressException)
                );
    }

    public static <M, T> Mono<T> pollUntilAny(
            List<Supplier<Mono<M>>> calls,
            Predicate<T> isSuccess,
            Predicate<T> isFailure,
            RetryBackoffSpec pollSpec,
            Function<M, Supplier<Mono<T>>> mToTSupplier,
            Integer individualFailRetries
    ) {
        if (calls == null || calls.isEmpty()) {
            return Mono.error(new IllegalArgumentException("No suppliers provided"));
        }

        return Mono.defer(() -> tryNextSupplier(calls, 0, isSuccess, isFailure, pollSpec, mToTSupplier, individualFailRetries, individualFailRetries));
    }

    private static <M, T> Mono<T> tryNextSupplier(
            List<Supplier<Mono<M>>> calls,
            int callIndex,
            Predicate<T> isSuccess,
            Predicate<T> isFailure,
            RetryBackoffSpec pollSpec,
            Function<M, Supplier<Mono<T>>> mToTSupplier,
            int pollFailureMaxRetries,
            int pollFailureRetryIndex
    ) {
        if (callIndex >= calls.size()) {
            return Mono.empty();
        }

        return calls.get(callIndex).get()
                .flatMap(m -> pollUntil(mToTSupplier.apply(m), isSuccess, isFailure, pollSpec))
                .onErrorResume(PollingFailedException.class, ex ->
                        pollFailureRetryIndex == 0 && callIndex == calls.size() - 1 ?
                                Mono.error(new PollingFailedException("All suppliers failed after retries"))
                                 : (pollFailureRetryIndex > 0) ?
                                        tryNextSupplier(calls, callIndex, isSuccess, isFailure, pollSpec, mToTSupplier, pollFailureMaxRetries, pollFailureRetryIndex - 1)
                                        : tryNextSupplier(calls, callIndex + 1, isSuccess, isFailure, pollSpec, mToTSupplier, pollFailureMaxRetries, pollFailureMaxRetries));
    }


    public static RetryBackoffSpec defaultBackoff(
            Duration firstBackoff,
            int maxAttempts
    ) {
        return Retry.backoff(maxAttempts, firstBackoff)
                .jitter(0.2)
                .transientErrors(true);
    }

    public static class PollingInProgressException extends RuntimeException {
        public PollingInProgressException(String message) {
            super(message);
        }
    }

    public static class PollingFailedException extends RuntimeException {
        public PollingFailedException(String message) {
            super(message);
        }
    }
}