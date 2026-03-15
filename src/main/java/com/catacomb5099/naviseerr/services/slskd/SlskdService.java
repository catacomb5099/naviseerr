package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class SlskdService {
    private static final String TIMEOUT_HEADER = "searchTimeout";
    private static final String SEARCH_QUERY_HEADER = "searchText";
    private final static String SEARCHES_ENDPOINT = "/searches";
    private final static String TRANSFERS_ENDPOINT = "/transfers/downloads";

    private final WebClient webClient;

    @Value("${slskd-service.timeout}")
    private int timeout;

    public SlskdService(WebClient slskdWebClient) {
        this.webClient = slskdWebClient;
    }

    public Mono<SearchState> searchResults(String query) {
        Map<String, Object> requestBody = Map.of(SEARCH_QUERY_HEADER, query, TIMEOUT_HEADER, timeout);

        return webClient
                .post()
                .uri(SEARCHES_ENDPOINT)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(SearchState.class);
    }

    public Mono<SearchState> getSearchResultsProgress(String searchId) {
        return webClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(SEARCHES_ENDPOINT + "/" + searchId)
                        .queryParam("includeResponses", true)
                        .build())
                .retrieve()
                .bodyToMono(SearchState.class);
    }

    public Mono<String> enqueueDownload(String username, SearchFile file) {
        return webClient
                .post()
                .uri(TRANSFERS_ENDPOINT + "/" + username)
                .bodyValue(List.of(file))
                .retrieve()
                .bodyToMono(String.class);
    }
}
