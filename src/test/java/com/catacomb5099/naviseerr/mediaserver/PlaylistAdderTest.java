package com.catacomb5099.naviseerr.mediaserver;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaylistAdderTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final PlaylistAdditionRepository repository = mock(PlaylistAdditionRepository.class);
    private final MediaItems items = mock(MediaItems.class);
    private final MediaServerNotifier notifier = mock(MediaServerNotifier.class);
    private final PlaylistAdder adder = new PlaylistAdder(repository, items, notifier, Clock.systemUTC(), Duration.ofHours(1));

    private PlaylistAdditionRepository.Due due(int tries) {
        return new PlaylistAdditionRepository.Due(UUID.randomUUID(), MediaServer.NAVIDROME, "pl", null, "yt",
                tries, new FiledSongs.FiledSong("yt", "/m/a.mp3", "A", List.of("X"), 100), NOW.minusSeconds(60));
    }

    @Test
    void backoff_growsTo10Minutes_andTheThirdMissAsksForARescan() {
        assertEquals(Duration.ofSeconds(15), PlaylistAdder.backoff(0));
        assertEquals(Duration.ofMinutes(5), PlaylistAdder.backoff(4));
        assertEquals(Duration.ofMinutes(10), PlaylistAdder.backoff(40));

        when(items.connected(MediaServer.NAVIDROME)).thenReturn(true);
        when(items.find(any(), any())).thenReturn(Mono.empty());
        when(repository.tryLater(any(), anyInt(), any(), any())).thenReturn(Mono.empty());

        when(repository.due(NOW, 50)).thenReturn(Flux.just(due(1)));
        adder.pass(NOW).block();
        verifyNoInteractions(notifier);

        when(repository.due(NOW, 50)).thenReturn(Flux.just(due(2)));
        adder.pass(NOW).block();
        verify(notifier).libraryChanged();
        verify(repository).tryLater(any(), eq(3), eq(NOW.plus(Duration.ofMinutes(1))), eq("Navidrome does not list it yet"));
    }

    @Test
    void aMediaServerThatDoesNotAnswer_isTriedAgainWithItsSentence() {
        when(items.connected(MediaServer.NAVIDROME)).thenReturn(true);
        when(items.find(any(), any())).thenReturn(Mono.error(new MediaServerException("Could not reach nas:4533")));
        when(repository.tryLater(any(), anyInt(), any(), any())).thenReturn(Mono.empty());
        when(repository.due(NOW, 50)).thenReturn(Flux.just(due(0)));

        adder.pass(NOW).block();

        verify(repository).tryLater(any(), eq(1), eq(NOW.plusSeconds(15)), eq("Could not reach nas:4533"));
        verify(repository, never()).giveUp(any(), any(), any());
    }
}
