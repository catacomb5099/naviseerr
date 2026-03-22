package com.catacomb5099.naviseerr.services.lastfm;

import com.catacomb5099.naviseerr.util.LastFMAPIMethod;
import com.catacomb5099.naviseerr.util.LastFMAPIMethodHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class LastFMService {
    private static final String API_KEY_HEADER = "api_key";
    private static final String FORMAT_HEADER = "format";
    private static final String LIMIT_HEADER = "limit";
    private static final String METHOD_HEADER = "method";

    private final WebClient lastFmWebClient;
    private final LastFMAPIMethodHelper lastFMAPIMethodHelper;

    @Value("${last-fm-service.api_key}")
    private String apiKey;
    @Value("${last-fm-service.search-result-limit}")
    private int searchResultsLimit;

    public LastFMService(WebClient lastFmWebClient, LastFMAPIMethodHelper lastFMAPIMethodHelper) {
        this.lastFmWebClient = lastFmWebClient;
        this.lastFMAPIMethodHelper = lastFMAPIMethodHelper;
    }

    public Mono<String> getResults(String query, LastFMAPIMethod apiMethod) {
        String method = lastFMAPIMethodHelper.getRelevantMethodHeaderValue(apiMethod);
        String paramName = lastFMAPIMethodHelper.getAPIMethodSpecificParam(apiMethod);

        return lastFmWebClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .queryParam(API_KEY_HEADER, apiKey)
                        .queryParam(FORMAT_HEADER, "json")
                        .queryParam(LIMIT_HEADER, searchResultsLimit)
                        .queryParam(METHOD_HEADER, method)
                        .queryParam(paramName, query)
                        .build())
                .retrieve()
                .bodyToMono(String.class);
    }
}
