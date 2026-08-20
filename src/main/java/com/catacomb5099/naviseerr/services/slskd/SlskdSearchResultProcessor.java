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

    // A free slot is a fact about now; uploadSpeed is the peer's own unverified claim about its
    // history. So: can they start at all, then how many people are ahead of you, then speed.
    private static final Comparator<Map.Entry<SearchResponseItem, SearchFile>> BY_AVAILABILITY =
            Comparator
                    .comparing((Map.Entry<SearchResponseItem, SearchFile> entry) ->
                            !Boolean.TRUE.equals(entry.getKey().getHasFreeUploadsSlot()))
                    .thenComparingInt(entry -> entry.getKey().getQueueLength())
                    .thenComparingInt(entry -> -entry.getKey().getUploadSpeed());

    public SlskdSearchResultProcessor(SlskdService slskdService, TrackMatchingService trackMatchingService) {
        this.slskdService = slskdService;
        this.trackMatchingService = trackMatchingService;
    }

    public Mono<List<Map.Entry<SearchResponseItem, SearchFile>>> selectBestFiles(SearchState state, String query) {
        return Mono.fromCallable(() -> {
            // Null rather than empty when the caller handed us a search fetched without
            // includeResponses. Degrade to "no candidates" instead of an NPE, so the failure reads as
            // what it is in the log below rather than as a generic step error.
            List<SearchResponseItem> responses =
                    state.getResponses() == null ? List.of() : state.getResponses();
            List<Map.Entry<SearchResponseItem, SearchFile>> candidates = responses.stream()
                    .flatMap(item -> item.getFiles().stream().map(file -> Map.entry(item, file)))
                    .filter(entry -> isFlacAndHighBitrate(entry.getValue()))
                    .filter(entry -> isRelevant(entry.getValue(), query))
                    .sorted(BY_AVAILABILITY)
                    .toList();

            log.info("Completed candidate selection for query='{}' - {} response(s), {} total files, {} relevant candidates; limiting to {} by maxFilesPerDownload", query, responses.size(), state.getFileCount(), candidates.size(), maxFilesPerDownload);
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
