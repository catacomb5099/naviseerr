package com.catacomb5099.naviseerr.mediaserver;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        return new MediaServerNotifier(navidrome, jellyfin, organiser, GAP, Duration.ofMillis(10));
    }

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(MediaServerNotifier.class)).addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(MediaServerNotifier.class)).detachAppender(logs);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).map(ILoggingEvent::getFormattedMessage).toList();
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

    @TempDir Path tmp;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};

    @Test
    void aPlaylistWithAPictureFile_getsItSentToJellyfin_onceJellyfinListsThePlaylist() throws IOException {
        Path m3u8 = Files.writeString(tmp.resolve("Mix.m3u8"), "#EXTM3U\n");
        Files.write(tmp.resolve("Mix.jpg"), JPEG);
        // Jellyfin has not imported the file at the first look, then lists it.
        when(jellyfin.findPlaylist("Mix.m3u8")).thenReturn(Mono.empty(), Mono.empty(), Mono.just("item-1"));
        when(jellyfin.setPrimaryImage(any(), any())).thenReturn(Mono.empty());
        MediaServerNotifier notifier = notifier();

        notifier.playlistWritten(m3u8);
        notifier.libraryChanged();
        notifier.flush(T0).block();

        verify(jellyfin, timeout(5000)).setPrimaryImage("item-1", JPEG);
        verify(jellyfin, times(3)).findPlaylist("Mix.m3u8");
        // Seen live 19-10-2026: the "not imported" warning fired after a successful push, because the push
        // completes empty. The warning belongs to the lookup only.
        assertEquals(List.of(), warnings());
    }

    @Test
    void aPlaylistWithoutAPictureFile_isNotPushed_andAFailedJellyfinRescan_keepsItForTheNextOne() throws IOException {
        Path plain = Files.writeString(tmp.resolve("Plain.m3u8"), "#EXTM3U\n");
        Path withPicture = Files.writeString(tmp.resolve("Mix.m3u8"), "#EXTM3U\n");
        Files.write(tmp.resolve("Mix.jpg"), JPEG);
        when(jellyfin.rescan()).thenReturn(
                Mono.error(new MediaServerException("Jellyfin at http://nas:8096 did not answer: Connection refused")),
                Mono.fromRunnable(jellyfinCalls::incrementAndGet));
        when(jellyfin.findPlaylist(any())).thenReturn(Mono.just("item-2"));
        when(jellyfin.setPrimaryImage(any(), any())).thenReturn(Mono.empty());
        MediaServerNotifier notifier = notifier();

        notifier.playlistWritten(plain);
        notifier.playlistWritten(withPicture);
        notifier.flush(T0).block();                       // Jellyfin down: nothing pushed, both kept
        verify(jellyfin, never()).findPlaylist(any());

        notifier.libraryChanged();
        notifier.flush(T0.plusSeconds(60)).block();       // Jellyfin back: the one with a picture is pushed
        verify(jellyfin, timeout(5000)).setPrimaryImage("item-2", JPEG);
        verify(jellyfin, timeout(1000).times(1)).findPlaylist("Mix.m3u8");
        verify(jellyfin, never()).findPlaylist("Plain.m3u8");
    }

    @Test
    void pushCover_givesUpWithOneWarning_whenJellyfinNeverListsThePlaylist() throws IOException {
        Path m3u8 = Files.writeString(tmp.resolve("Lost.m3u8"), "#EXTM3U\n");
        Files.write(tmp.resolve("Lost.jpg"), JPEG);
        when(jellyfin.findPlaylist("Lost.m3u8")).thenReturn(Mono.empty());

        notifier().pushCover(m3u8).block(Duration.ofSeconds(10));

        verify(jellyfin, times(MediaServerNotifier.COVER_POLLS + 1)).findPlaylist("Lost.m3u8");
        verify(jellyfin, never()).setPrimaryImage(any(), any());
        assertEquals(1, warnings().size(), () -> warnings().toString());
        assertTrue(warnings().getFirst().startsWith("Jellyfin has not imported playlist 'Lost'"), warnings().getFirst());
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
