package com.catacomb5099.naviseerr.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Which browsers' pages may read the API: the listed dev origin, the app's own address; nobody else. */
class WebConfigCorsTest {

    @RestController
    static class Ping {
        @GetMapping("/ping")
        String ping() { return "pong"; }
    }

    /** Absolute, as Netty hands it to the server: Spring's CORS code needs the scheme. */
    private static final String URL = "http://localhost:8080/ping";

    private final WebTestClient http = WebTestClient.bindToController(new Ping())
            .webFilter(new WebConfig().corsWebFilter(List.of("http://localhost:5173")))
            .build();

    @Test
    void theDevOrigin_isAllowed_withCredentials() {
        http.get().uri(URL).header("Origin", "http://localhost:5173").header("Host", "localhost:8080").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
        http.options().uri(URL).header("Origin", "http://localhost:5173").header("Host", "localhost:8080")
                .header("Access-Control-Request-Method", "POST").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    @Test
    void aForeignOrigin_getsNoAllowHeader_andNoAnswerToReadOrPreflight() {
        http.get().uri(URL).header("Origin", "https://evil.example").header("Host", "localhost:8080").exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
                .expectHeader().doesNotExist("Access-Control-Allow-Origin")
                .expectHeader().doesNotExist("Access-Control-Allow-Credentials");
        http.options().uri(URL).header("Origin", "https://evil.example").header("Host", "localhost:8080")
                .header("Access-Control-Request-Method", "POST").exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        // Close is not equal: another port, or the dev origin as a prefix of something else.
        http.get().uri(URL).header("Origin", "http://localhost:5174").header("Host", "localhost:8080").exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        http.get().uri(URL).header("Origin", "http://localhost:5173.evil.example").header("Host", "localhost:8080").exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theAppsOwnAddress_isNotCrossOrigin_evenWhenTheProxyDidHttps_andNoOriginIsFine() {
        // The install: the browser is on https://music.example, the server sees plain HTTP with that Host.
        http.get().uri(URL).header("Origin", "https://music.example").header("Host", "music.example").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        http.get().uri(URL).header("Origin", "http://nas:5056").header("Host", "NAS:5056").exchange()
                .expectStatus().isOk();
        // Same host, another port: that IS another origin, and not a listed one.
        http.get().uri(URL).header("Origin", "http://nas:5057").header("Host", "nas:5056").exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        // No Origin at all (curl, same-origin GETs): untouched.
        http.get().uri(URL).header("Host", "nas:5056").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        // Nonsense in Origin is not own, not allowed.
        http.get().uri(URL).header("Origin", "::not a url::").header("Host", "nas:5056").exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }
}
