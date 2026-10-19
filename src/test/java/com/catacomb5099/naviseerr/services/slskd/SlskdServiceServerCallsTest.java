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

/** Pins the two server calls' shapes against slskd 0.26.0's ServerController: PUT with no body, DELETE with the reason as a JSON string. */
class SlskdServiceServerCallsTest {

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
    void connect_putsTheServerResource() {
        StepVerifier.create(serviceAnswering(HttpStatus.OK).connect()).verifyComplete();

        assertEquals(HttpMethod.PUT, sent.get().method());
        assertEquals("http://slskd.test/api/v0/server", sent.get().url().toString());
    }

    @Test
    void connect_acceptsResetContent_whenSlskdWasAlreadyTrying() {
        StepVerifier.create(serviceAnswering(HttpStatus.RESET_CONTENT).connect()).verifyComplete();
    }

    @Test
    void disconnect_deletesTheServerResource_withTheReasonAsJson() {
        StepVerifier.create(serviceAnswering(HttpStatus.NO_CONTENT).disconnect("account changed")).verifyComplete();

        assertEquals(HttpMethod.DELETE, sent.get().method());
        assertEquals("http://slskd.test/api/v0/server", sent.get().url().toString());
        assertEquals("application/json", sent.get().headers().getFirst("Content-Type"));
    }

    @Test
    void errors_surface_soTheCallerCanLogAndCarryOn() {
        StepVerifier.create(serviceAnswering(HttpStatus.FORBIDDEN).connect())
                .expectError(WebClientResponseException.class)
                .verify();
    }
}
