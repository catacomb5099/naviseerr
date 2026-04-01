package com.catacomb5099.naviseerr.services.slskd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class SlskdConfig {
    private final static String API_KEY_HEADER = "X-API-Key";

    @Value("${slskd-service.url}")
    private String url;
    @Value("${slskd-service.api_key}")
    private String apiKey;

    @Bean
    public WebClient slskdWebClient() {
        return WebClient.builder()
                .baseUrl(url)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .build();
    }


}
