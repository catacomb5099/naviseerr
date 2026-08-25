package com.catacomb5099.naviseerr.support;

import com.catacomb5099.naviseerr.schema.slskd.QueueDownloadResponse;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.schema.slskd.ServerState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
        return transfer(id, username, state, 0f);
    }

    public static TransferedFile transfer(String id, String username, String state,
                                          Float percentComplete) {
        return new TransferedFile(id, username, "Download", "path/song.flac", 100L, null, state,
                null, null, null, null, 0L, 0f, 100L, null, percentComplete, null);
    }

    public static QueueDownloadResponse enqueued(String id, String username) {
        return new QueueDownloadResponse(List.of(transfer(id, username, "Queued")), List.of());
    }

    public static QueueDownloadResponse enqueueRejected() {
        return new QueueDownloadResponse(List.of(), List.of(transfer("x", "peer", "Rejected")));
    }

    public static ServerState serverState() {
        return new ServerState("vps.slsknet.org:2242", "208.76.170.59:2242", "Connected, LoggedIn",
                true, false, true, false, false);
    }

    /** A dropped/timed-out connection -- the transport-level failure, not an HTTP response at all. */
    public static WebClientRequestException transportFailure() {
        return new WebClientRequestException(new java.io.IOException("Operation timed out"),
                HttpMethod.POST, URI.create("https://slskd.example/api/v0/searches"), new HttpHeaders());
    }

    /** slskd responding with an HTTP error status, e.g. 400 (rejected) or 502 (upstream failure). */
    public static WebClientResponseException responseFailure(int statusCode) {
        return WebClientResponseException.create(statusCode, "error", new HttpHeaders(),
                new byte[0], StandardCharsets.UTF_8);
    }
}
