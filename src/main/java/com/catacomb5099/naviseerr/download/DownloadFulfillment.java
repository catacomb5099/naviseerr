package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import com.catacomb5099.naviseerr.services.slskd.SlskdDownloadProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Runs the slskd acquisition flow for one song: search, poll, select best files, download, poll,
 * with the processors' own retry/failover. Pure acquisition - no database writes; persisting the
 * terminal status is {@link DownloadWorker}'s job.
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
