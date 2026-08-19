package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the wire shape of {@code GET /transfers/downloads}, captured verbatim from a live slskd
 * instance on 2026-08-19.
 *
 * <p>This exists because that endpoint does NOT return a flat list of transfers — it nests them under
 * peer, then directory — while the per-transfer endpoint returns a bare transfer. Reading the list as
 * {@code Flux<TransferedFile>} parses each top-level PEER into an all-null transfer (only
 * {@code username} maps), so the by-id lookup map ends up keyed by null and no transfer id ever
 * matches. Every DOWNLOAD_POLL row then saw an empty state list and polled until its budget expired,
 * including rows whose transfer had already succeeded.
 *
 * <p>A fixture-based test could not have caught that: the fixture would have been written to whatever
 * shape the code expected. Only real captured JSON pins it, which is why the payload below is literal.
 */
class SlskdServiceTransfersShapeTest {

    private static final String LIVE_RESPONSE = """
            [
              {
                "username": "lwl",
                "directories": [
                  {
                    "directory": "Unsorted\\\\Bootlegs\\\\2025\\\\0424",
                    "fileCount": 1,
                    "files": [
                      {
                        "id": "33fc1f71-8143-4832-8a6f-be1be8387c0d",
                        "username": "lwl",
                        "direction": "Download",
                        "filename": "Unsorted\\\\Bootlegs\\\\2025\\\\0424\\\\Vance Joy - Riptide.mp3",
                        "size": 8911936,
                        "state": "Completed, Succeeded",
                        "requestedAt": "2026-08-19T10:59:42.7006033",
                        "enqueuedAt": "2026-08-19T10:59:43.7922221",
                        "startedAt": "2026-08-19T10:59:44.1003276Z",
                        "endedAt": "2026-08-19T10:59:44.4455452Z",
                        "bytesTransferred": 8911936,
                        "averageSpeed": 25815416.131738357,
                        "attempts": 1,
                        "removed": false,
                        "bytesRemaining": 0,
                        "elapsedTime": "00:00:00.3452176",
                        "percentComplete": 100,
                        "remainingTime": "00:00:00"
                      }
                    ]
                  }
                ]
              }
            ]
            """;

    private static SlskdService serviceReturning(String body) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://slskd.test/api/v0/")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();
        return new SlskdService(webClient);
    }

    @Test
    void getAllDownloads_flattensPeerAndDirectoryNesting_downToTheTransfers() {
        List<TransferedFile> transfers = serviceReturning(LIVE_RESPONSE)
                .getAllDownloads().collectList().block();

        assertNotNull(transfers);
        assertEquals(1, transfers.size(), "should yield the FILE, not the peer wrapper");

        TransferedFile transfer = transfers.getFirst();
        // The id is the whole point: it is the key DownloadTaskRunner builds its lookup map on, and
        // it is null for every entry if the nesting is not flattened.
        assertEquals("33fc1f71-8143-4832-8a6f-be1be8387c0d", transfer.getId());
        assertEquals("Completed, Succeeded", transfer.getState());
        assertEquals("lwl", transfer.getUsername());
        assertEquals(8911936L, transfer.getSize());
    }

    @Test
    void getAllDownloads_toleratesAPeerWithNoDirectories_andADirectoryWithNoFiles() {
        String sparse = """
                [
                  {"username": "a", "directories": null},
                  {"username": "b", "directories": [{"directory": "d", "fileCount": 0, "files": null}]}
                ]
                """;

        List<TransferedFile> transfers = serviceReturning(sparse)
                .getAllDownloads().collectList().block();

        assertNotNull(transfers);
        assertTrue(transfers.isEmpty());
    }
}
