package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import com.catacomb5099.naviseerr.util.networkcalls.ReactivePoller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Component
public class SlskdSearchResultProcessor {
    private final SlskdService slskdService;
    private final TrackMatchingService trackMatchingService;

    @Value("${slskd-service.min-bit-rate}")
    int minBitRate;
    @Value("${slskd-service.max-files-per-download}")
    int maxFilesPerDownload;
    @Value("${slskd-service.max-poll-attempts}")
    int maxPollAttempts;
    @Value("${slskd-service.first-back-off-duration-ms}")
    long firstBackOffDuration;

    public SlskdSearchResultProcessor(SlskdService slskdService, TrackMatchingService trackMatchingService) {
        this.slskdService = slskdService;
        this.trackMatchingService = trackMatchingService;
    }

    public Mono<SearchState> pollUntilComplete(String query) {
        // TODO: this is a great place for metric incrementing and calculating how often the default search is not enough, and which usually yields enough results (would prob need to randomise the strategy order - otherwise most catch all strat will be reported most)
        // TODO: can add alternative search solutions (maybe omitting the artist name, replacing the artist name with album, replacing non alphanumeric chars (ex spaces))
        Supplier<Mono<SearchState>> call = () -> slskdService.searchResults(query);

        Predicate<SearchState> done = SearchState::getIsComplete;
        // TODO: can change this to state.selectBestFiles(...).count < Threshold, to ensure we get a certain number of candidates
        Predicate<SearchState> failed = s -> false;
        RetryBackoffSpec retry = ReactivePoller.defaultBackoff(Duration.ofMillis(firstBackOffDuration), maxPollAttempts);
        Function<SearchState, Supplier<Mono<SearchState>>> function = startSearchState -> () -> slskdService.getSearchResultsProgress(startSearchState.getId());

        return ReactivePoller.pollUntilAny(List.of(call), done, failed, retry, function);
    }

    public Mono<List<Map.Entry<SearchResponseItem, SearchFile>>> selectBestFiles(SearchState state, String query) {
        return Mono.fromCallable(() -> {
            List<Map.Entry<SearchResponseItem, SearchFile>> candidates = state.getResponses().stream()
                    .flatMap(item -> item.getFiles().stream().map(file -> Map.entry(item, file)))
                    .filter(entry -> isFlacAndHighBitrate(entry.getValue()))
                    .filter(entry -> isRelevant(entry.getValue(), query))
                    .sorted(Comparator.comparingInt(entry -> -entry.getKey().getUploadSpeed()))
                    .toList();

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
