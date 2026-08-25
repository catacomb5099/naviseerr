package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.QueueDownloadResponse;
import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.ServerState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.schema.slskd.UserTransfers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class SlskdService {
    private static final String TIMEOUT_HEADER = "searchTimeout";
    private static final String SEARCH_QUERY_HEADER = "searchText";
    private final static String SEARCHES_ENDPOINT = "/searches";
    private final static String TRANSFERS_ENDPOINT = "/transfers/downloads";
    private final static String SERVER_ENDPOINT = "/server";

    private final WebClient webClient;

    @Value("${slskd-service.timeout}")
    private int timeout;

    public SlskdService(WebClient slskdWebClient) {
        this.webClient = slskdWebClient;
    }

    /**
     * slskd's own connection state to the Soulseek network. A single small object, not a list --
     * the cheapest possible call to slskd, used to check the connection is alive without touching
     * search or transfer state at all.
     */
    public Mono<ServerState> getServerState() {
        return webClient
                .get()
                .uri(SERVER_ENDPOINT)
                .retrieve()
                .bodyToMono(ServerState.class);
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

    public Mono<QueueDownloadResponse> enqueueDownload(String username, SearchFile file) {
        return webClient
                .post()
                .uri(TRANSFERS_ENDPOINT + "/" + username)
                .bodyValue(List.of(file))
                .retrieve()
                .bodyToMono(QueueDownloadResponse.class);
    }

    public Mono<TransferedFile> getDownloadProgress(String username, String downloadId) {
        return webClient
                .get()
                .uri(TRANSFERS_ENDPOINT + "/" + username + "/" + downloadId)
                .retrieve()
                .bodyToMono(TransferedFile.class);
    }

    /**
     * Every search slskd currently knows about, but <strong>summaries only</strong>: the list endpoint
     * takes no {@code includeResponses} parameter and always returns {@code responses} empty, however
     * many results the search actually found. Use it for the {@code isComplete} gate, never for the
     * results themselves — for those, follow up with {@link #getSearchWithResponses(String)}.
     */
    public Flux<SearchState> getAllSearches() {
        return webClient
                .get()
                .uri(SEARCHES_ENDPOINT)
                .retrieve()
                .bodyToFlux(SearchState.class);
    }

    /**
     * One search including its {@code responses}. The only endpoint that populates them, so this is
     * the required follow-up to {@link #getAllSearches()} before candidate selection. Called once per
     * download, on the transition to complete — not once per poll.
     */
    public Mono<SearchState> getSearchWithResponses(String searchId) {
        return webClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(SEARCHES_ENDPOINT + "/" + searchId)
                        .queryParam("includeResponses", true)
                        .build())
                .retrieve()
                .bodyToMono(SearchState.class);
    }

    /**
     * Every transfer slskd currently knows about, flattened. The endpoint groups transfers by peer and
     * then by directory ({@link UserTransfers}), so the flattening is required, not cosmetic: reading
     * the response as a flat {@code Flux<TransferedFile>} yields one all-null transfer per peer, whose
     * null {@code id} makes every by-id lookup miss.
     */
    public Flux<TransferedFile> getAllDownloads() {
        return webClient
                .get()
                .uri(TRANSFERS_ENDPOINT)
                .retrieve()
                .bodyToFlux(UserTransfers.class)
                .flatMapIterable(user -> user.getDirectories() == null
                        ? List.of()
                        : user.getDirectories())
                .flatMapIterable(directory -> directory.getFiles() == null
                        ? List.of()
                        : directory.getFiles());
    }
}
