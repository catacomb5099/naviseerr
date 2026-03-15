package com.catacomb5099.naviseerr.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class SlskdConfig {
    private final static String API_KEY_HEADER = "X-API-Key";
    private final static String SEARCHES_ENDPOINT = "/searches";

    @Value("${slskd-service.url}")
    private String url;
    @Value("${slskd-service.api_key}")
    private String apiKey;

    @Bean
    public WebClient slskdWebClient() {
        return WebClient.builder()
                .baseUrl(url + SEARCHES_ENDPOINT)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .build();
    }


}
