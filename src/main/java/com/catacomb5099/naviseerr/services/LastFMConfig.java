package com.catacomb5099.naviseerr.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class LastFMConfig {
    @Value("${last-fm-service.url}")
    private String url;

    @Bean
    public WebClient lastFmWebClient() {
        return WebClient.builder()
                .baseUrl(url)
                .build();
    }


}
