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
import java.util.function.Function;
import java.util.stream.Collectors;

import static reactor.netty.http.HttpConnectionLiveness.log;

@Component
public class SlskdSearchResultProcessor {
    private final SlskdService slskdService;
    private final TrackMatchingService trackMatchingService;

    @Value("${slskd-service.min-bit-rate}")
    int minBitRate;
    @Value("${slskd-service.max-files-per-download}")
    int maxFilesPerDownload;
    @Value("${download-task.max-sharer-queue:50}")
    int maxSharerQueue;

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
                    .toList();

            // Most candidates share the duration of the mainstream release; remixes, live takes and
            // album re-records are the odd lengths out. Prefer the most shared length, then fall back
            // to who can serve the file fastest. Measured 80% -> 93% correct top pick on 718 songs.
            //
            // One exception sits in front of the duration vote: a sharer with no free upload slot
            // AND more than max-sharer-queue files already waiting goes to the back of the whole
            // list, whatever its file's length. The duration vote protects against the wrong version;
            // this protects against a sharer that will not reach us inside queued-budget-ms at all
            // (27-09-2026: two such sharers cost ten minutes per song, 58 times). Putting the
            // demotion inside each duration group instead would change nothing, because
            // BY_AVAILABILITY already sorts busy sharers to the end of their group. The price is
            // small: only when EVERY sharer of the majority length is that overloaded does an
            // odd-length file get tried first, and the threshold is set high enough that "busy" alone
            // never triggers it.
            Map<Integer, Long> countByLength = candidates.stream()
                    .flatMap(entry -> entry.getValue().getLength().stream())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            candidates = candidates.stream()
                    .sorted(Comparator
                            .comparing((Map.Entry<SearchResponseItem, SearchFile> entry) ->
                                    isOverloaded(entry.getKey()))
                            .thenComparingLong(entry ->
                                    -entry.getValue().getLength().map(countByLength::get).orElse(0L))
                            .thenComparing(BY_AVAILABILITY))
                    .toList();

            log.info("Completed candidate selection for query='{}' - {} response(s), {} total files, {} relevant candidates; limiting to {} by maxFilesPerDownload", query, responses.size(), state.getFileCount(), candidates.size(), maxFilesPerDownload);
            return spreadAcrossSharers(candidates).stream().limit(maxFilesPerDownload).toList();
        });
    }

    /**
     * Same order, but no sharer gets a second file in the list until every other sharer has had
     * its first, then a third until every one has had a second, and so on. A failover list made of
     * one sharer's ten pressings of the same album is ten chances for the same "Queued, Remotely"
     * (27-09-2026: songs with three to five files all from one stalling sharer waited 30-50 minutes
     * before running out). Spreading the list means a sharer that stalls or throttles costs one
     * candidate, not the whole list. Within a round the ranking above still decides who goes first.
     */
    static List<Map.Entry<SearchResponseItem, SearchFile>> spreadAcrossSharers(
            List<Map.Entry<SearchResponseItem, SearchFile>> ranked) {
        Map<String, java.util.ArrayDeque<Map.Entry<SearchResponseItem, SearchFile>>> bySharer =
                new java.util.LinkedHashMap<>();
        for (Map.Entry<SearchResponseItem, SearchFile> entry : ranked) {
            bySharer.computeIfAbsent(entry.getKey().getUsername(), k -> new java.util.ArrayDeque<>()).add(entry);
        }
        List<Map.Entry<SearchResponseItem, SearchFile>> spread = new java.util.ArrayList<>(ranked.size());
        while (spread.size() < ranked.size()) {
            for (java.util.ArrayDeque<Map.Entry<SearchResponseItem, SearchFile>> queue : bySharer.values()) {
                if (!queue.isEmpty()) {
                    spread.add(queue.poll());
                }
            }
        }
        return spread;
    }

    /** No slot for us now and a long line ahead of us: the profile of a sharer that never serves. */
    private boolean isOverloaded(SearchResponseItem sharer) {
        return !Boolean.TRUE.equals(sharer.getHasFreeUploadsSlot())
                && sharer.getQueueLength() > maxSharerQueue;
    }

    private boolean isRelevant(SearchFile file, String trackTitle) {
        return trackMatchingService.isMatch(trackTitle, file.getFilename());
    }

    private boolean isFlacAndHighBitrate(SearchFile file) {
        return (file.getBitRate().isPresent() && file.getBitRate().get() >= minBitRate) || file.getExtension().equals("flac");
    }

}
