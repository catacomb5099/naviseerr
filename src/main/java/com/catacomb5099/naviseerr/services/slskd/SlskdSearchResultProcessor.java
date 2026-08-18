package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static reactor.netty.http.HttpConnectionLiveness.log;

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

    public Mono<List<Map.Entry<SearchResponseItem, SearchFile>>> selectBestFiles(SearchState state, String query) {
        return Mono.fromCallable(() -> {
            List<Map.Entry<SearchResponseItem, SearchFile>> candidates = state.getResponses().stream()
                    .flatMap(item -> item.getFiles().stream().map(file -> Map.entry(item, file)))
                    .filter(entry -> isFlacAndHighBitrate(entry.getValue()))
                    .filter(entry -> isRelevant(entry.getValue(), query))
                    .sorted(Comparator.comparingInt(entry -> -entry.getKey().getUploadSpeed()))
                    .toList();

            log.info("Completed candidate selection for query='{}' - {} total files, {} relevant candidates; limiting to {} by maxFilesPerDownload", query, state.getFileCount(), candidates.size(), maxFilesPerDownload);
            return candidates.stream().limit(maxFilesPerDownload).toList();
        });
    }

    private boolean isRelevant(SearchFile file, String trackTitle) {
        return trackMatchingService.isMatch(trackTitle, file.getFilename());
    }

    private boolean isFlacAndHighBitrate(SearchFile file) {
        return (file.getBitRate().isPresent() && file.getBitRate().get() >= minBitRate) || file.getExtension().equals("flac");
    }

}
