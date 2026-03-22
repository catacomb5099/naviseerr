package com.catacomb5099.naviseerr.util.networkcalls;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class ReactivePoller {

    public static <T> Mono<T> pollUntil(
        Supplier<Mono<T>> call,
        Predicate<T> isSuccess,
        Predicate<T> isFailure,
        RetryBackoffSpec retrySpec
    ) {
        return Mono.defer(call)
                .flatMap(response -> {
                    if (isSuccess.test(response)) {
                        return Mono.just(response);
                    } else if (isFailure.test(response)) {
                        return Mono.error(new PollingFailedException("Polling failed due to failure state"));
                    } else {
                        return Mono.error(new PollingInProgressException("Still processing"));
                    }
                })
                .retryWhen(
                        retrySpec.filter(ex -> ex instanceof PollingInProgressException)
                );
    }

    public static <T> Mono<T> pollUntilAny(
            List<Supplier<Mono<T>>> calls,
            Predicate<T> isSuccess,
            Predicate<T> isFailure,
            RetryBackoffSpec retrySpec
    ) {
        if (calls == null || calls.isEmpty()) {
            return Mono.error(new IllegalArgumentException("No suppliers provided"));
        }

        return Mono.defer(() -> tryNextSupplier(calls, 0, isSuccess, isFailure, retrySpec));
    }

    private static <T> Mono<T> tryNextSupplier(
            List<Supplier<Mono<T>>> calls,
            int index,
            Predicate<T> isSuccess,
            Predicate<T> isFailure,
            RetryBackoffSpec retrySpec
    ) {
        if (index >= calls.size()) {
            return Mono.empty();
        }
        return pollUntil(calls.get(index), isSuccess, isFailure, retrySpec)
                .onErrorResume(PollingFailedException.class, ex -> tryNextSupplier(calls, index + 1, isSuccess, isFailure, retrySpec));
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