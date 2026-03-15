package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class SearchResultProcessor {
    private final SlskdService slskdService;
    private final TrackMatchingService trackMatchingService;

    public SearchResultProcessor(SlskdService slskdService, TrackMatchingService trackMatchingService) {
        this.slskdService = slskdService;
        this.trackMatchingService = trackMatchingService;
    }

    public Mono<SearchState> pollUntilComplete(String searchId) {
        return pollUntilComplete(searchId, 500L);
    }

    private Mono<SearchState> pollUntilComplete(String searchId, long delay) {
        return slskdService.getSearchResultsProgress(searchId)
                .flatMap(state -> state.getIsComplete() ? Mono.just(state) : Mono.delay(Duration.ofMillis(delay)).then(pollUntilComplete(searchId, Math.min(delay * 2, 30000L))));
    }

    // polling with exponential backoff until SearchState.isComplete = true
    // filter songs that are not relevant
    // filter songs that are not flac, or above 320 bit rate
    // sort by relevance, SearchResponseItem.uploadSpeed
    public Mono<Map.Entry<SearchResponseItem, SearchFile>> selectBestFile(SearchState state, String query) {
        return Mono.fromCallable(() -> {
            List<Map.Entry<SearchResponseItem, SearchFile>> candidates = state.getResponses().stream()
                    .flatMap(item -> item.getFiles().stream().map(file -> Map.entry(item, file)))
                    .filter(entry -> isFlacAndHighBitrate(entry.getValue()))
                    .filter(entry -> isRelevant(entry.getValue(), query))
                    .sorted(Comparator.comparingInt(entry -> -entry.getKey().getUploadSpeed()))
                    .toList();
            return candidates.isEmpty() ? null : candidates.getFirst();
        });
    }

    private boolean isRelevant(SearchFile file, String trackTitle) {
        return trackMatchingService.isMatch(trackTitle, file.getFilename());
    }

    private boolean isFlacAndHighBitrate(SearchFile file) {
        return (file.getBitRate().isPresent() && file.getBitRate().get() > 320) || file.getExtension().equals("flac");
    }
}
