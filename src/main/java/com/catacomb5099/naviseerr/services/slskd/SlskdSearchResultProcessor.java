package com.catacomb5099.naviseerr.services.slskd;

import ch.qos.logback.core.joran.sanity.Pair;
import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class SlskdSearchResultProcessor {
    private final SlskdService slskdService;
    private final TrackMatchingService trackMatchingService;

    @Value("${slskd-service.min-bit-rate}")
    int minBitRate;
    @Value("${slskd-service.max-files-per-download}")
    int maxFilesPerDownload;

    public SlskdSearchResultProcessor(SlskdService slskdService, TrackMatchingService trackMatchingService) {
        this.slskdService = slskdService;
        this.trackMatchingService = trackMatchingService;
    }

    public Mono<SearchState> pollUntilComplete(String searchId) {
        return pollUntilComplete(searchId, 500L);
    }

    // polling with exponential backoff until SearchState.isComplete = true
    private Mono<SearchState> pollUntilComplete(String searchId, long delay) {
        return slskdService.getSearchResultsProgress(searchId)
                .flatMap(state ->
                        state.getIsComplete() ?
                                Mono.just(state) :
                                Mono.delay(Duration.ofMillis(delay))
                                        .then(pollUntilComplete(searchId, Math.min(delay * 2, 30000L))));
    }

    public Mono<List<Map.Entry<SearchResponseItem, SearchFile>>> selectBestFiles(SearchState state, String query) {
        return Mono.fromCallable(() -> {
            List<Map.Entry<SearchResponseItem, SearchFile>> candidates = state.getResponses().stream()
                    .flatMap(item -> item.getFiles().stream().map(file -> Map.entry(item, file)))
                    .filter(entry -> isFlacAndHighBitrate(entry.getValue()))
                    .filter(entry -> isRelevant(entry.getValue(), query))
                    .sorted(Comparator.comparingInt(entry -> -entry.getKey().getUploadSpeed()))
                    .toList();

            // TODO: if this returns sub Y candidates, retry alternative search solutions (maybe omitting the artist name, replacing the artist name with album, replacing non alphanumeric chars (ex spaces))
                // TODO: stop retrying when Y result threshold is met
            // TODO: this is a great place for metric incrementing and calculating how often the default search is not enough, and which usually yields enough results (would prob need to randomise the strategy order - otherwise most catch all strat will be reported most)
            return candidates.isEmpty() ? null : candidates.stream().limit(maxFilesPerDownload).toList();
        });
    }

    private boolean isRelevant(SearchFile file, String trackTitle) {
        return trackMatchingService.isMatch(trackTitle, file.getFilename());
    }

    private boolean isFlacAndHighBitrate(SearchFile file) {
        return (file.getBitRate().isPresent() && file.getBitRate().get() > minBitRate) || file.getExtension().equals("flac");
    }
}
