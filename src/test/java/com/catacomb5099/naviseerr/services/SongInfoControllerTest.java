package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SongInfoControllerTest {

    private final YtMusicService ytMusicService = mock(YtMusicService.class);
    private final SongInfoController controller = new SongInfoController(ytMusicService);
    private final WebTestClient client = WebTestClient.bindToController(controller).build();

    private static YtMusicDetailResponse.SongDetails albumTrack() {
        return YtMusicDetailResponse.SongDetails.builder()
                .videoId("DntZ3-yCaFs").title("Manchild")
                .artists(List.of(new YtMusicSearchResponse.ArtistRef("Sabrina Carpenter", "UCz51")))
                .album(new YtMusicSearchResponse.AlbumRef("Man's Best Friend", "MPREb_msf"))
                .durationSeconds(214).year(2025).viewCount(86_376_709L).plays("310M plays").explicit(true)
                .thumbnailUrl("https://img/m.jpg")
                .credits(List.of(
                        new YtMusicDetailResponse.Credit("Performed by", List.of("Sabrina Carpenter")),
                        new YtMusicDetailResponse.Credit("Written by", List.of("Sabrina Carpenter", "Jack Antonoff", "Amy Allen"))))
                .build();
    }

    private static YtMusicDetailResponse.SongDetails officialVideo() {
        return YtMusicDetailResponse.SongDetails.builder()
                .videoId("tM1RS_5IAiE").title("Little By Little (Official Video)")
                .artists(List.of(new YtMusicSearchResponse.ArtistRef("Oasis", "UCmMU")))
                .durationSeconds(239).viewCount(41_779_269L).thumbnailUrl("https://img/o.jpg")
                .credits(List.of())
                .build();
    }

    @Test
    void albumTrack_mapsEveryFieldOfTheContract() {
        when(ytMusicService.getSongDetails("DntZ3-yCaFs")).thenReturn(Mono.just(albumTrack()));

        StepVerifier.create(controller.song("DntZ3-yCaFs"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SongInfoView view = response.getBody();
                    assertNotNull(view);
                    assertEquals("DntZ3-yCaFs", view.id());
                    assertEquals("Manchild", view.name());
                    assertEquals(List.of(new SongInfoView.Ref("UCz51", "Sabrina Carpenter")), view.artists());
                    assertEquals(new SongInfoView.Ref("MPREb_msf", "Man's Best Friend"), view.album());
                    assertEquals(214, view.durationSeconds());
                    assertEquals(2025, view.year());
                    assertEquals(86_376_709L, view.viewCount(), "this upload's exact plays");
                    assertEquals("310M plays", view.plays(), "the combined count, YouTube's wording unparsed");
                    assertEquals("https://img/m.jpg", view.iconURL());
                    assertEquals(Boolean.TRUE, view.explicit());
                    assertEquals(2, view.credits().size());
                    assertEquals("Written by", view.credits().get(1).role());
                    assertEquals(List.of("Sabrina Carpenter", "Jack Antonoff", "Amy Allen"), view.credits().get(1).names());
                })
                .verifyComplete();
    }

    @Test
    void officialVideo_hasNullAlbumYearExplicit_andEmptyCredits_notAnError() {
        when(ytMusicService.getSongDetails("tM1RS_5IAiE")).thenReturn(Mono.just(officialVideo()));

        StepVerifier.create(controller.song("tM1RS_5IAiE"))
                .assertNext(response -> {
                    SongInfoView view = response.getBody();
                    assertNotNull(view);
                    assertNull(view.album());
                    assertNull(view.year());
                    assertNull(view.explicit(), "unknown, not false");
                    assertNull(view.plays(), "no album track, so no combined count");
                    assertEquals(41_779_269L, view.viewCount());
                    assertTrue(view.credits().isEmpty());
                    assertEquals(239, view.durationSeconds());
                })
                .verifyComplete();
    }

    @Test
    void id_isTheRequestedId_andMissingPiecesDegradeToEmptyNotNull() {
        YtMusicDetailResponse.SongDetails bare = YtMusicDetailResponse.SongDetails.builder()
                .videoId("other").title("Untitled")
                .artists(List.of(new YtMusicSearchResponse.ArtistRef(null, "UCx"), new YtMusicSearchResponse.ArtistRef("Blur", null)))
                .album(new YtMusicSearchResponse.AlbumRef(null, "MPREb_x"))
                .credits(Arrays.asList(new YtMusicDetailResponse.Credit("Mixed by", Arrays.asList("A", null)),
                        new YtMusicDetailResponse.Credit(null, List.of("ignored"))))
                .build();
        when(ytMusicService.getSongDetails("vid1")).thenReturn(Mono.just(bare));

        StepVerifier.create(controller.song("vid1"))
                .assertNext(response -> {
                    SongInfoView view = response.getBody();
                    assertNotNull(view);
                    assertEquals("vid1", view.id(), "the requested id, not the adapter's");
                    assertEquals(List.of(new SongInfoView.Ref(null, "Blur")), view.artists(), "nameless artist dropped");
                    assertNull(view.album(), "an album without a name is no album");
                    assertEquals("", view.iconURL(), "empty string, never null");
                    assertEquals(List.of(new SongInfoView.Credit("Mixed by", List.of("A"))), view.credits());
                })
                .verifyComplete();
    }

    @Test
    void blankId_isRejectedWith400_withoutCallingTheAdapter() {
        StepVerifier.create(controller.song(" "))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();

        verifyNoInteractions(ytMusicService);
    }

    @Test
    void adapterBadRequest_propagates_andHandlerMapsTo404() {
        when(ytMusicService.getSongDetails("nope")).thenReturn(Mono.error(new YtMusicBadRequestException("404")));

        StepVerifier.create(controller.song("nope")).verifyError(YtMusicBadRequestException.class);

        ResponseEntity<Void> response = controller.handleNotFound(new YtMusicBadRequestException("404"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void adapterUnavailable_propagates_andHandlerMapsTo502() {
        when(ytMusicService.getSongDetails("vid1")).thenReturn(Mono.error(new YtMusicUnavailableException("down")));

        StepVerifier.create(controller.song("vid1")).verifyError(YtMusicUnavailableException.class);

        ResponseEntity<Void> response = controller.handleUnavailable(new YtMusicUnavailableException("down"));
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }

    private WebTestClient.ResponseSpec views(String ids) {
        return client.get().uri(uri -> uri.path("/songs/views").queryParam("ids", ids).build()).exchange();
    }

    @Test
    void views_answersIdToCount_trimmingBlanksAndLookingUpADuplicateOnce() {
        when(ytMusicService.getSongViewCount("a")).thenReturn(Mono.just(19_334_421L));
        when(ytMusicService.getSongViewCount("b")).thenReturn(Mono.empty()); // the adapter has no count
        when(ytMusicService.getSongViewCount("c")).thenReturn(Mono.just(7_004_756L));

        views(" a , b,,a,c").expectStatus().isOk()
                .expectBody().json("{\"a\": 19334421, \"c\": 7004756}", JsonCompareMode.STRICT);

        verify(ytMusicService, times(1)).getSongViewCount("a");
    }

    @Test
    void views_theLiteralPathWins_overSongsId() {
        when(ytMusicService.getSongViewCount("a")).thenReturn(Mono.just(1L));

        views("a").expectStatus().isOk();

        verify(ytMusicService, never()).getSongDetails(anyString());
    }

    @Test
    void views_aFailingLookup_isLeftOut_andAllFailingIsStillAnObject() {
        when(ytMusicService.getSongViewCount("ok")).thenReturn(Mono.just(5L));
        when(ytMusicService.getSongViewCount("unknown")).thenReturn(Mono.error(new YtMusicBadRequestException("404")));
        when(ytMusicService.getSongViewCount("down")).thenReturn(Mono.error(new YtMusicUnavailableException("down")));

        views("ok,unknown,down").expectStatus().isOk()
                .expectBody().json("{\"ok\": 5}", JsonCompareMode.STRICT);
        views("unknown,down").expectStatus().isOk()
                .expectBody().json("{}", JsonCompareMode.STRICT);
    }

    @Test
    void views_noIdOrMoreThanFifty_is400_withoutCallingTheAdapter() {
        String fiftyOne = IntStream.range(0, 51).mapToObj(i -> "v" + i).collect(Collectors.joining(","));

        views(" , ,").expectStatus().isBadRequest();
        client.get().uri("/songs/views").exchange().expectStatus().isBadRequest();
        views(fiftyOne).expectStatus().isBadRequest();

        verifyNoInteractions(ytMusicService);
    }

    @Test
    void views_runsAtMostEightLookupsAtOnce() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // counted down before the answer is emitted: flatMap starts the next lookup as soon as one completes
        when(ytMusicService.getSongViewCount(anyString())).thenAnswer(call -> Mono.defer(() -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            return Mono.delay(Duration.ofMillis(20)).doOnNext(tick -> inFlight.decrementAndGet()).thenReturn(1L);
        }));

        views(IntStream.range(0, 50).mapToObj(i -> "v" + i).collect(Collectors.joining(",")))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.v49").isEqualTo(1);

        assertEquals(SongInfoController.VIEWS_CONCURRENCY, peak.get());
    }
}
