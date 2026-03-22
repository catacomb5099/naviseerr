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

    public static <T> Mono<T> pollUntil(
        Supplier<Mono<T>> call,
        Predicate<T> isSuccess,
        Predicate<T> isFailure,
        RetryBackoffSpec retrySpec
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
                        retrySpec.filter(ex -> ex instanceof PollingInProgressException)
                );
    }

    public static <M, T> Mono<T> pollUntilAny(
            List<Supplier<Mono<M>>> calls,
            Predicate<T> isSuccess,
            Predicate<T> isFailure,
            RetryBackoffSpec retrySpec,
            Function<M, Supplier<Mono<T>>> mToTSupplier
    ) {
        if (calls == null || calls.isEmpty()) {
            return Mono.error(new IllegalArgumentException("No suppliers provided"));
        }

        return Mono.defer(() -> tryNextSupplier(calls, 0, isSuccess, isFailure, retrySpec, mToTSupplier));
    }

    private static <M, T> Mono<T> tryNextSupplier(
            List<Supplier<Mono<M>>> calls,
            int index,
            Predicate<T> isSuccess,
            Predicate<T> isFailure,
            RetryBackoffSpec retrySpec,
            Function<M, Supplier<Mono<T>>> mToTSupplier
    ) {
        if (index >= calls.size()) {
            return Mono.empty();
        }

        return calls.get(index).get()
                .flatMap(m -> pollUntil(mToTSupplier.apply(m), isSuccess, isFailure, retrySpec))
                .onErrorResume(PollingFailedException.class, ex -> tryNextSupplier(calls, index + 1, isSuccess, isFailure, retrySpec, mToTSupplier));
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