package com.catacomb5099.naviseerr.library;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class LibraryControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final UUID LIVE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID FRESH = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final LibrarySongs songs = mock(LibrarySongs.class);
    private final LibraryOrganiser organiser = mock(LibraryOrganiser.class);
    private final WebTestClient client = WebTestClient.bindToController(
            new LibraryController(songs, organiser, Clock.fixed(NOW, ZoneOffset.UTC))).build();

    @Test
    void organiserOn_filedIsInLibrary_liveAndJustFinishedAreOnTheirWay_restLeftOut() {
        when(organiser.isEnabled()).thenReturn(true);
        when(organiser.cutoff(NOW)).thenReturn(NOW.minusSeconds(600));
        when(songs.rows(List.of("filed", "live", "fresh", "gone", "old")))
                .thenReturn(Flux.just(
                        new LibrarySongs.Row("filed", UUID.randomUUID(), true, "/music/a.mp3", NOW),
                        new LibrarySongs.Row("live", LIVE, false, null, null),
                        new LibrarySongs.Row("fresh", FRESH, true, null, NOW.minusSeconds(5)),
                        new LibrarySongs.Row("gone", UUID.randomUUID(), true, "/music/deleted.mp3", NOW),
                        new LibrarySongs.Row("old", UUID.randomUUID(), true, null, NOW.minusSeconds(3600))));
        // Only /music/a.mp3 is still a file.
        when(organiser.stillFiled(anyInt(), any())).thenAnswer(call -> {
            List<LibraryOrganiser.FiledCopy> copies = call.getArgument(1);
            LibraryOrganiser.FiledCopy[] found = new LibraryOrganiser.FiledCopy[(int) call.getArgument(0)];
            copies.stream().filter(c -> c.libraryPath().equals("/music/a.mp3")).forEach(c -> found[c.position() - 1] = c);
            return Mono.just(Arrays.asList(found));
        });

        client.get().uri("/library/songs?ids=filed,live,fresh,gone,old").exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        {"filed": {"state": "IN_LIBRARY"},
                         "live": {"state": "ON_ITS_WAY", "downloadId": "%s"},
                         "fresh": {"state": "ON_ITS_WAY", "downloadId": "%s"}}
                        """.formatted(LIVE, FRESH), org.springframework.test.json.JsonCompareMode.STRICT);
    }

    @Test
    void organiserOff_aFinishedSongCountsAsInLibrary() {
        when(organiser.isEnabled()).thenReturn(false);
        when(organiser.cutoff(NOW)).thenReturn(NOW.minusSeconds(600));
        when(songs.rows(List.of("done", "live")))
                .thenReturn(Flux.just(new LibrarySongs.Row("done", UUID.randomUUID(), true, null, NOW.minusSeconds(3600)),
                        new LibrarySongs.Row("live", LIVE, false, null, null)));

        client.get().uri("/library/songs?ids=done,live").exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        {"done": {"state": "IN_LIBRARY"}, "live": {"state": "ON_ITS_WAY", "downloadId": "%s"}}
                        """.formatted(LIVE), org.springframework.test.json.JsonCompareMode.STRICT);
        verify(organiser, never()).stillFiled(anyInt(), any());
    }

    @Test
    void moreThan200Ids_is400WithASentence_andReadsNothing() {
        String ids = IntStream.rangeClosed(1, 201).mapToObj(i -> "v" + i).collect(Collectors.joining(","));

        client.get().uri("/library/songs?ids=" + ids).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo("Ask for between 1 and 200 song ids.");
        verifyNoInteractions(songs);
    }
}
