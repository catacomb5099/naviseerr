package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdDownloadProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Runs the slskd acquisition flow for a single song: search and poll until the search completes,
 * select the best candidate files, then enqueue and poll the download (with the processors' own
 * retry/failover) until a candidate succeeds or all are exhausted.
 *
 * <p>Pure acquisition only: it performs no database writes. Reaching a success/fail state and
 * persisting the terminal status is the caller's responsibility (see {@link DownloadWorker}).
 */
@Service
public class DownloadFulfillment {

    private final SlskdSearchResultProcessor slskdSearchResultProcessor;
    private final SlskdDownloadProcessor slskdDownloadProcessor;

    public DownloadFulfillment(SlskdSearchResultProcessor slskdSearchResultProcessor,
                               SlskdDownloadProcessor slskdDownloadProcessor) {
        this.slskdSearchResultProcessor = slskdSearchResultProcessor;
        this.slskdDownloadProcessor = slskdDownloadProcessor;
    }

    public Mono<TransferedFile> fulfill(String songName) {
        return slskdSearchResultProcessor.pollUntilComplete(songName)
                .flatMap(state -> slskdSearchResultProcessor.selectBestFiles(state, songName))
                .flatMap(slskdDownloadProcessor::pollUntilComplete);
    }
}
