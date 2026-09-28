package com.catacomb5099.naviseerr.curator;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SuggestedPlaylistControllerTest {

    private final CuratorClient client = mock(CuratorClient.class);
    private final CuratorScheduler scheduler = mock(CuratorScheduler.class);
    private final SuggestedPlaylistController controller = new SuggestedPlaylistController(client, scheduler);

    private static CuratorEdition edition() {
        return new CuratorEdition("80s indie pop",
                Map.of("title", "80s indie pop", "year", "1980-1989", "style", "Indie Pop"),
                "2026-09-27", 1738171583L, List.of(
                new CuratorTrack("kkxixKRfEnk", "Cico Buff", List.of("Cocteau Twins"), "Blue Bell Knoll", null,
                        1988, 6_100_000L, "top", "#23 of 1036 by plays"),
                new CuratorTrack(null, "Unplayable", List.of("Nobody"), "Nothing", null, null, 0L, "random", "x"),
                new CuratorTrack("ewnLtRyqAzo", "Decomposing Trees", List.of("Galaxie 500"), "On Fire", "MPREb_x",
                        1988, 180_000L, "random", "random pick (seed 1738171583) from 1006 remaining")));
    }

    @Test
    void list_withNoCuratorConfigured_saysSoWithoutCallingIt() {
        when(scheduler.isEnabled()).thenReturn(false);

        StepVerifier.create(controller.list())
                .assertNext(view -> {
                    assertFalse(view.enabled());
                    assertNull(view.refreshDay());
                    assertTrue(view.playlists().isEmpty());
                })
                .verifyComplete();
        verifyNoInteractions(client);
    }

    @Test
    void list_mapsTheCuratorsEditions() {
        when(scheduler.isEnabled()).thenReturn(true);
        when(scheduler.getRefreshDay()).thenReturn(DayOfWeek.MONDAY);
        when(client.getEditions()).thenReturn(Mono.just(List.of(
                new CuratorEditionSummary("80s-indie-pop", "80s indie pop", "2026-09-27", 40),
                new CuratorEditionSummary("current-pop", "Current pop", "2026-09-28", null))));

        StepVerifier.create(controller.list())
                .assertNext(view -> {
                    assertTrue(view.enabled());
                    assertEquals(DayOfWeek.MONDAY, view.refreshDay());
                    assertEquals(2, view.playlists().size());
                    SuggestedPlaylistsView.SuggestedPlaylistSummary first = view.playlists().getFirst();
                    assertEquals("80s-indie-pop", first.category());
                    assertEquals("80s indie pop", first.title());
                    assertEquals("2026-09-27", first.editionDate());
                    assertEquals(40, first.trackCount());
                    assertEquals(0, view.playlists().get(1).trackCount(), "a missing count is 0, not null");
                })
                .verifyComplete();
    }

    @Test
    void list_withNoEditionsYet_isEnabledAndEmpty() {
        when(scheduler.isEnabled()).thenReturn(true);
        when(client.getEditions()).thenReturn(Mono.just(List.of()));

        StepVerifier.create(controller.list())
                .assertNext(view -> {
                    assertTrue(view.enabled());
                    assertTrue(view.playlists().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void one_mapsTracksInOrder_dropsIdlessOnes_andBuildsThumbnails() {
        when(scheduler.isEnabled()).thenReturn(true);
        when(client.getEdition("80s-indie-pop")).thenReturn(Mono.just(edition()));

        StepVerifier.create(controller.one("80s-indie-pop"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SuggestedPlaylistView view = response.getBody();
                    assertNotNull(view);
                    assertEquals("80s-indie-pop", view.category());
                    assertEquals("80s indie pop", view.title());
                    assertEquals("2026-09-27", view.editionDate());
                    assertEquals(Map.of("year", "1980-1989", "style", "Indie Pop"), view.filters(),
                            "the title is not a filter");
                    assertEquals(2, view.trackCount(), "the track without a videoId is dropped");
                    assertEquals(2, view.tracks().size());

                    SuggestedPlaylistView.SuggestedTrackView first = view.tracks().getFirst();
                    assertEquals("kkxixKRfEnk", first.id());
                    assertEquals("Cico Buff", first.name());
                    assertEquals(List.of("Cocteau Twins"), first.artists());
                    assertEquals("Blue Bell Knoll", first.album());
                    assertEquals(1988, first.albumYear());
                    assertEquals(6_100_000L, first.popularity());
                    assertEquals("top", first.tier());
                    assertEquals("#23 of 1036 by plays", first.reason());
                    assertEquals("https://i.ytimg.com/vi/kkxixKRfEnk/hqdefault.jpg", first.iconURL());
                    assertEquals(1, first.position());

                    SuggestedPlaylistView.SuggestedTrackView second = view.tracks().get(1);
                    assertEquals("ewnLtRyqAzo", second.id());
                    assertEquals("random", second.tier());
                    assertEquals(2, second.position(), "positions stay consecutive after the drop");
                })
                .verifyComplete();
    }

    @Test
    void one_withNoCuratorConfigured_is503WithoutCallingIt() {
        when(scheduler.isEnabled()).thenReturn(false);

        StepVerifier.create(controller.one("80s-indie-pop"))
                .assertNext(response -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode()))
                .verifyComplete();
        verify(client, never()).getEdition(anyString());
    }

    @Test
    void one_withABlankCategory_is400() {
        StepVerifier.create(controller.one(" "))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
        verifyNoInteractions(client, scheduler);
    }

    @Test
    void refresh_asksTheSchedulerAndAnswers202WithTheRun() {
        when(scheduler.isEnabled()).thenReturn(true);
        CuratorRun queued = new CuratorRun("r1", "queued", "2026-09-28T10:14:39Z", null, null, List.of(
                new CuratorCategoryResult("80s-indie-pop", "queued", null, null, null)));
        when(scheduler.refreshNow()).thenReturn(Mono.just(queued));

        StepVerifier.create(controller.refresh())
                .assertNext(response -> {
                    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
                    assertEquals("r1", response.getBody().runId());
                    assertEquals("queued", response.getBody().status());
                })
                .verifyComplete();
        verify(client, never()).triggerRun();
        verify(client, never()).triggerRunOnce();
    }

    @Test
    void refresh_withNoCuratorConfigured_is503WithoutTriggering() {
        when(scheduler.isEnabled()).thenReturn(false);

        StepVerifier.create(controller.refresh())
                .assertNext(response -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode()))
                .verifyComplete();
        verify(scheduler, never()).refreshNow();
    }

    @Test
    void latestRun_readsTheCuratorsLatestRun() {
        when(scheduler.isEnabled()).thenReturn(true);
        when(client.getLatestRun()).thenReturn(Mono.just(new CuratorRun("r2", "partial", "2026-09-28T10:14:39Z",
                "2026-09-28T10:14:40Z", "2026-09-28T10:17:28Z", List.of(
                new CuratorCategoryResult("90s-grime", "no_albums", "2026-09-28", null, "Discogs returned no albums")))));

        StepVerifier.create(controller.latestRun())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("partial", response.getBody().status());
                    assertEquals("no_albums", response.getBody().categories().getFirst().status());
                })
                .verifyComplete();
    }

    @Test
    void latestRun_withNoCuratorConfigured_is503() {
        when(scheduler.isEnabled()).thenReturn(false);

        StepVerifier.create(controller.latestRun())
                .assertNext(response -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode()))
                .verifyComplete();
        verifyNoInteractions(client);
    }

    @Test
    void curatorNotFound_becomes404_anythingElse502() {
        ResponseEntity<Void> notFound = controller.handleCurator(
                new CuratorException("curator returned 404: no edition for 90s-grime", false, 404));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        ResponseEntity<Void> down = controller.handleCurator(
                new CuratorException("curator list of editions failed: Connection refused", true));
        assertEquals(HttpStatus.BAD_GATEWAY, down.getStatusCode());

        ResponseEntity<Void> badToken = controller.handleCurator(
                new CuratorException("curator rejected the token (401)", false, 401));
        assertEquals(HttpStatus.BAD_GATEWAY, badToken.getStatusCode(), "a wrong token is a server-side problem");
    }
}
