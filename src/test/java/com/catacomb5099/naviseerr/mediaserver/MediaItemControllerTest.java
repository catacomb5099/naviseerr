package com.catacomb5099.naviseerr.mediaserver;

import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MediaItemControllerTest {

    private final FiledSongs filed = mock(FiledSongs.class);
    private final MediaItems items = mock(MediaItems.class);
    private final WebTestClient client = WebTestClient.bindToController(new MediaItemController(filed, items)).build();

    private static FiledSongs.FiledSong song(String id) {
        return new FiledSongs.FiledSong(id, "/music/Oasis/A/" + id + ".mp3", id, List.of("Oasis"), 200);
    }

    @Test
    void eachIdIsFound_notFound_orNotInTheLibrary() {
        when(items.connected(MediaServer.NAVIDROME)).thenReturn(true);
        when(filed.newest(List.of("a", "b", "c"))).thenReturn(Flux.just(song("a"), song("b")));
        when(items.find(eq(MediaServer.NAVIDROME), eq(song("a")))).thenReturn(Mono.just(new SongFinder.Found("nd-a", "nd-album")));
        when(items.find(eq(MediaServer.NAVIDROME), eq(song("b")))).thenReturn(Mono.empty());

        client.get().uri("/library/media-items?server=NAVIDROME&ids=a,b,c").exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        {"a": {"id": "nd-a", "albumId": "nd-album"},
                         "b": {"reason": "NOT_FOUND_IN_MEDIA_SERVER"},
                         "c": {"reason": "NOT_IN_LIBRARY"}}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void aServerNaviseerrIsNotConnectedTo_is409WithTheReason() {
        when(items.connected(MediaServer.JELLYFIN)).thenReturn(false);

        client.get().uri("/library/media-items?server=jellyfin&ids=a").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.reason").isEqualTo("MEDIA_SERVER_NOT_CONNECTED")
                .jsonPath("$.message").isEqualTo("naviseerr is not connected to Jellyfin. Add it in naviseerr's Settings, under Music app.");
        verifyNoInteractions(filed);
    }

    @Test
    void anUnreachableServer_is502WithItsSentence() {
        when(items.connected(MediaServer.NAVIDROME)).thenReturn(true);
        when(filed.newest(any())).thenReturn(Flux.just(song("a")));
        when(items.find(any(), any())).thenReturn(Mono.error(new MediaServerException("Could not reach nas:4533: no answer in time. Is Navidrome running, and can this server reach it?")));

        client.get().uri("/library/media-items?server=NAVIDROME&ids=a").exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.reason").isEqualTo("MEDIA_SERVER_UNREACHABLE")
                .jsonPath("$.message").value(m -> org.junit.jupiter.api.Assertions.assertTrue(m.toString().startsWith("Could not reach nas:4533")));
    }

    @Test
    void anUnknownServerOrTooManyIds_is400() {
        client.get().uri("/library/media-items?server=PLEX&ids=a").exchange().expectStatus().isBadRequest();
        String ids = String.join(",", java.util.stream.IntStream.rangeClosed(1, 51).mapToObj(i -> "v" + i).toList());
        client.get().uri("/library/media-items?server=NAVIDROME&ids=" + ids).exchange().expectStatus().isBadRequest();
        verifyNoInteractions(filed, items);
    }
}
