package com.catacomb5099.naviseerr.support;

import com.catacomb5099.naviseerr.schema.slskd.QueueDownloadResponse;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;

import java.util.List;
import java.util.Optional;

public final class SlskdFixtures {

    private SlskdFixtures() {}

    /**
     * Shaped like the batched {@code GET /searches} summary: {@code responses} empty, because that
     * endpoint never populates it. Use {@link #searchStateWithResponses} for the single-search refetch.
     */
    public static SearchState searchState(String id, boolean complete, String state) {
        return new SearchState(Optional.empty(), 0, id, complete, 0, 0, List.of(),
                "query", "2026-08-13T00:00:00Z", state, 1);
    }

    /** Shaped like {@code GET /searches/{id}?includeResponses=true}: the only source of responses. */
    public static SearchState searchStateWithResponses(String id, boolean complete, String state,
                                                       List<SearchResponseItem> responses) {
        return new SearchState(Optional.empty(), 0, id, complete, 0, responses.size(), responses,
                "query", "2026-08-13T00:00:00Z", state, 1);
    }

    public static TransferedFile transfer(String id, String username, String state) {
        return new TransferedFile(id, username, "Download", "path/song.flac", 100L, null, state,
                null, null, null, null, 0L, 0f, 100L, null, 0f, null);
    }

    public static QueueDownloadResponse enqueued(String id, String username) {
        return new QueueDownloadResponse(List.of(transfer(id, username, "Queued")), List.of());
    }

    public static QueueDownloadResponse enqueueRejected() {
        return new QueueDownloadResponse(List.of(), List.of(transfer("x", "peer", "Rejected")));
    }
}
