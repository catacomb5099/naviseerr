package com.catacomb5099.naviseerr.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class SlskdService {
    private static final String TIMEOUT_HEADER = "searchTimeout";
    private static final String SEARCH_QUERY_HEADER = "searchText";

    private final WebClient webClient;

    @Value("${slskd-service.timeout}")
    private int timeout;

    public SlskdService(WebClient slskdWebClient) {
        this.webClient = slskdWebClient;
    }

    public Mono<String> getResults(String query) {
        Map<String, Object> requestBody = Map.of(SEARCH_QUERY_HEADER, query, TIMEOUT_HEADER, timeout);

        return webClient
                .post()
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class);
    }
}
