package com.catacomb5099.naviseerr.services.slskd;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the cancel call's shape: DELETE on the transfer's own URL, and "already gone" is not an error. */
class SlskdServiceCancelDownloadTest {

    private final AtomicReference<ClientRequest> sent = new AtomicReference<>();

    private SlskdService serviceAnswering(HttpStatus status) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://slskd.test/api/v0/")
                .exchangeFunction(request -> {
                    sent.set(request);
                    return Mono.just(ClientResponse.create(status).build());
                })
                .build();
        return new SlskdService(webClient);
    }

    @Test
    void cancelDownload_deletesTheTransferByUsernameAndId() {
        StepVerifier.create(serviceAnswering(HttpStatus.NO_CONTENT)
                        .cancelDownload("SKYLiGHT_B", "33fc1f71-8143-4832-8a6f-be1be8387c0d"))
                .verifyComplete();

        assertEquals(HttpMethod.DELETE, sent.get().method());
        assertEquals("http://slskd.test/api/v0/transfers/downloads/SKYLiGHT_B/33fc1f71-8143-4832-8a6f-be1be8387c0d",
                sent.get().url().toString());
    }

    @Test
    void cancelDownload_treatsNotFoundAsAlreadyGone() {
        StepVerifier.create(serviceAnswering(HttpStatus.NOT_FOUND).cancelDownload("alice", "abc"))
                .verifyComplete();
    }

    @Test
    void cancelDownload_surfacesOtherErrors_soTheCallerCanLogThem() {
        StepVerifier.create(serviceAnswering(HttpStatus.INTERNAL_SERVER_ERROR).cancelDownload("alice", "abc"))
                .expectError(WebClientResponseException.class)
                .verify();
    }
}
