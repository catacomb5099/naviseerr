package com.catacomb5099.naviseerr.curator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound client for the croissant sidecar (the playlist curator). Every {@code /v1/*} request carries the shared
 * bearer token (env {@code CURATOR_TOKEN}, the same value the curator itself is started with).
 * {@code @EnableScheduling} lives here because {@link CuratorScheduler} is the only cron job in the
 * project; the download loop uses its own {@code Flux.interval} and does not need it.
 */
@Configuration
@EnableScheduling
public class CuratorConfig {

    @Bean
    public WebClient curatorWebClient(@Value("${curator.url}") String url,
                                      @Value("${curator.token}") String token) {
        return WebClient.builder()
                .baseUrl(url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }
}
