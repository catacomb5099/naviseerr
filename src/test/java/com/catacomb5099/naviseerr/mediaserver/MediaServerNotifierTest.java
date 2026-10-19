package com.catacomb5099.naviseerr.mediaserver;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaServerNotifierTest {

    private static final Instant T0 = Instant.parse("2026-10-19T12:00:00Z");
    private static final Duration GAP = Duration.ofSeconds(30);

    private NavidromeClient navidrome;
    private JellyfinClient jellyfin;
    private LibraryOrganiser organiser;
    private final AtomicInteger navidromeCalls = new AtomicInteger();
    private final AtomicInteger jellyfinCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        navidrome = mock(NavidromeClient.class);
        jellyfin = mock(JellyfinClient.class);
        organiser = mock(LibraryOrganiser.class);
        when(organiser.isEnabled()).thenReturn(true);
        when(navidrome.isConfigured()).thenReturn(true);
        when(navidrome.hasCredentials()).thenReturn(true);
        when(navidrome.url()).thenReturn("http://nas:4533");
        when(navidrome.rescan()).thenReturn(Mono.fromRunnable(navidromeCalls::incrementAndGet));
        when(jellyfin.isConfigured()).thenReturn(true);
        when(jellyfin.hasCredentials()).thenReturn(true);
        when(jellyfin.url()).thenReturn("http://nas:8096");
        when(jellyfin.rescan()).thenReturn(Mono.fromRunnable(jellyfinCalls::incrementAndGet));
    }

    private MediaServerNotifier notifier() {
        return new MediaServerNotifier(navidrome, jellyfin, organiser, GAP);
    }

    @Test
    void theFirstFlushAfterStart_asksEveryServerOnce_asTheBootCheck() {
        MediaServerNotifier notifier = notifier();

        notifier.flush(T0).block();
        notifier.flush(T0.plusSeconds(1)).block();

        assertEquals(1, navidromeCalls.get());
        assertEquals(1, jellyfinCalls.get());
        assertEquals(List.of(
                new MediaServerNotifier.MediaServerStatus("Navidrome", "http://nas:4533", null, T0),
                new MediaServerNotifier.MediaServerStatus("Jellyfin", "http://nas:8096", null, T0)), notifier.status());
    }

    @Test
    void aChange_isSentOncePerGap_andAChangeInsideTheGapGoesOutWhenItEnds() {
        MediaServerNotifier notifier = notifier();
        notifier.flush(T0).block();                       // boot check

        notifier.libraryChanged();
        notifier.flush(T0.plusSeconds(10)).block();       // inside the gap: held
        assertEquals(1, navidromeCalls.get());

        notifier.flush(T0.plusSeconds(30)).block();       // gap over: the held change goes out
        assertEquals(2, navidromeCalls.get());

        notifier.flush(T0.plusSeconds(70)).block();       // nothing new: nothing sent
        assertEquals(2, navidromeCalls.get());

        notifier.libraryChanged();
        notifier.libraryChanged();
        notifier.flush(T0.plusSeconds(71)).block();       // two changes, one call
        assertEquals(3, navidromeCalls.get());
        assertEquals(3, jellyfinCalls.get());
    }

    @Test
    void aServerThatFails_isReportedWithItsSentence_andClearedWhenItAnswersAgain() {
        when(jellyfin.rescan()).thenReturn(
                Mono.error(new MediaServerException("Jellyfin rejected the API key (JELLYFIN_API_KEY)")),
                Mono.fromRunnable(jellyfinCalls::incrementAndGet));
        MediaServerNotifier notifier = notifier();

        notifier.flush(T0).block();
        assertEquals(1, navidromeCalls.get(), "the other server is still asked");
        MediaServerNotifier.MediaServerStatus jf = notifier.status().get(1);
        assertEquals("Jellyfin rejected the API key (JELLYFIN_API_KEY)", jf.problem());
        assertNull(jf.lastRescanAt());

        notifier.libraryChanged();
        notifier.flush(T0.plusSeconds(60)).block();
        jf = notifier.status().get(1);
        assertNull(jf.problem());
        assertEquals(T0.plusSeconds(60), jf.lastRescanAt());
        assertEquals(1, jellyfinCalls.get());
    }

    @Test
    void aServerWithAUrlButNoLogin_isLeftOut_andNothingConfigured_meansNothingToDo() {
        when(navidrome.hasCredentials()).thenReturn(false);
        when(jellyfin.isConfigured()).thenReturn(false);
        MediaServerNotifier notifier = notifier();

        notifier.libraryChanged();
        notifier.flush(T0).block();

        assertEquals(0, navidromeCalls.get());
        assertEquals(0, jellyfinCalls.get());
        assertEquals(List.of(), notifier.status());
    }
}
