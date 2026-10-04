package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.download.DownloadCandidate;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor.Pick;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the wire shape of {@code GET /searches/{id}?includeResponses=true}: two of the 252 responses a
 * live slskd returned for "Daft Punk Discovery" on 04-10-2026, each trimmed to its One More Time file.
 *
 * <p>Until 04-10-2026 {@code SearchResponseItem} spelt the free-slot flag {@code hasFreeUploadsSlot}
 * while slskd sends {@code hasFreeUploadSlot}, so it read null for every sharer. A sharer with a free
 * slot and a long queue was then ranked as overloaded, behind one that could not start at all, and
 * every stored candidate showed a null slot. A test built from hand-made objects cannot catch a
 * misspelt field name; only real JSON does.
 */
class SlskdServiceSearchShapeTest {

    private static final String LIVE_RESPONSE = """
            {
              "endedAt": "2026-10-04T12:15:09.3336466Z",
              "fileCount": 4592,
              "id": "9e21d164-d2d1-452c-9bb6-7dc5ba95f618",
              "isComplete": true,
              "lockedFileCount": 79,
              "responseCount": 252,
              "responses": [
                {
                  "fileCount": 14,
                  "files": [
                    {
                      "bitRate": 320,
                      "code": 1,
                      "extension": "",
                      "filename": "@@fvxlo\\\\music\\\\Daft Punk\\\\Discovery\\\\01 One More Time.mp3",
                      "length": 320,
                      "size": 13061988,
                      "isLocked": false
                    }
                  ],
                  "hasFreeUploadSlot": false,
                  "lockedFileCount": 0,
                  "lockedFiles": [],
                  "queueLength": 39,
                  "token": 7618882,
                  "uploadSpeed": 1007128,
                  "username": "acrossma"
                },
                {
                  "fileCount": 28,
                  "files": [
                    {
                      "bitRate": 320,
                      "code": 1,
                      "extension": "",
                      "filename": "@@mfapl\\\\Music (320)\\\\Daft Punk\\\\Discovery\\\\01 One More Time.mp3",
                      "length": 320,
                      "size": 12912942,
                      "isLocked": false
                    }
                  ],
                  "hasFreeUploadSlot": true,
                  "lockedFileCount": 0,
                  "lockedFiles": [],
                  "queueLength": 574,
                  "token": 7618882,
                  "uploadSpeed": 8058637,
                  "username": "M3H9X"
                }
              ],
              "searchText": "Daft Punk Discovery",
              "startedAt": "2026-10-04T12:15:08.7002792Z",
              "state": "Completed, Errored",
              "token": 7618882
            }
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
    void aSharerWithAFreeSlot_isTriedFirst_evenWithALongQueue() {
        SearchState state = serviceReturning(LIVE_RESPONSE)
                .getSearchWithResponses("9e21d164-d2d1-452c-9bb6-7dc5ba95f618").block();

        TrackMatchingService matching = mock(TrackMatchingService.class);
        when(matching.grade(anyString(), anyString(), anyString())).thenReturn(TrackMatchingService.Match.EXACT);
        SlskdSearchResultProcessor processor = new SlskdSearchResultProcessor(mock(SlskdService.class), matching);
        ReflectionTestUtils.setField(processor, "minBitRate", 320);
        ReflectionTestUtils.setField(processor, "maxFilesPerDownload", 10);
        ReflectionTestUtils.setField(processor, "maxSharerQueue", 50);

        List<Pick> picks = processor.selectBestFiles(state, "One More Time - Daft Punk", "One More Time").block();

        // M3H9X has 574 people queued but a slot free right now; acrossma has 39 queued and no slot.
        assertEquals(List.of("M3H9X", "acrossma"), picks.stream().map(pick -> pick.peer().getUsername()).toList());
        assertEquals(true, DownloadCandidate.from(picks.get(0)).hasFreeUploadSlot());
        assertEquals(false, DownloadCandidate.from(picks.get(1)).hasFreeUploadSlot());
    }
}
