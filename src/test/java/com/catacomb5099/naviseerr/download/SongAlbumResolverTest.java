package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.curator.CuratorClient;
import com.catacomb5099.naviseerr.curator.CuratorEdition;
import com.catacomb5099.naviseerr.curator.CuratorException;
import com.catacomb5099.naviseerr.curator.CuratorTrack;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicBadRequestException;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicUnavailableException;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * The album lookup against real YouTube Music answers: {@code /ytmusic/song-albums.json} is what the
 * adapter said on 04-10-2026 for each song below, trimmed. A call the fixture has no answer for fails
 * the test, so every adapter call the lookup makes is one these answers cover.
 */
class SongAlbumResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final String DEFINITELY_MAYBE = "MPREb_Hl8XJR59OrY";
    private static final String DEFINITELY_MAYBE_DELUXE = "MPREb_VXk6OaxfNsY";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode FIXTURES;

    static {
        try (InputStream in = SongAlbumResolverTest.class.getResourceAsStream("/ytmusic/song-albums.json")) {
            FIXTURES = JSON.readTree(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private DownloadTaskRepository repository;
    private YtMusicService ytMusic;
    private CuratorClient curator;
    private LibraryOrganiser organiser;
    private SongAlbumResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(DownloadTaskRepository.class);
        ytMusic = mock(YtMusicService.class);
        curator = mock(CuratorClient.class);
        organiser = mock(LibraryOrganiser.class);
        when(organiser.isEnabled()).thenReturn(true);
        when(organiser.cutoff(any())).thenAnswer(call -> call.<Instant>getArgument(0).minusSeconds(600));
        when(repository.upsertMedia(any())).thenReturn(Mono.just(1L));
        when(repository.saveSongAlbum(any(), any(), any(), any())).thenReturn(Mono.just(1L));
        when(ytMusic.getSongDetails(anyString())).thenAnswer(call ->
                answer("details", call.getArgument(0), YtMusicDetailResponse.SongDetails.class));
        when(ytMusic.getAlbum(anyString())).thenAnswer(call ->
                answer("albums", call.getArgument(0), YtMusicDetailResponse.Collection.class));
        when(ytMusic.searchSongRows(anyString(), eq(SongAlbumResolver.SEARCH_ROWS))).thenAnswer(call -> {
            JsonNode rows = FIXTURES.get("searches").get(call.<String>getArgument(0));
            if (rows == null) return Mono.error(new AssertionError("no recorded search for " + call.getArgument(0)));
            return Mono.just(JSON.convertValue(rows, new TypeReference<List<YtMusicSearchResponse.Item>>() {}));
        });
        resolver = new SongAlbumResolver(repository, ytMusic, curator, organiser,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(2), 10);
    }

    private static <T> Mono<T> answer(String section, String id, Class<T> type) {
        JsonNode node = FIXTURES.get(section).get(id);
        return node == null ? Mono.error(new AssertionError("no recorded " + section + " answer for " + id))
                : Mono.just(JSON.treeToValue(node, type));
    }

    /** As admission writes it: the song's own title and uploader joined, and its own length. */
    private static SongAlbumResolver.Song song(String id, String songName, Integer seconds) {
        return new SongAlbumResolver.Song(id, songName, seconds, DownloadType.SONG, id);
    }

    private SongAlbumResolver.Found resolve(SongAlbumResolver.Song song) {
        return resolver.resolve(song).block().orElse(null);
    }

    // ---- joining a song to its album -------------------------------------------------------------

    @Test
    void supersonic_isTrack6OfDefinitelyMaybe_withOneAlbumFetch() {
        SongAlbumResolver.Found found = resolve(song("2n5OwsJtrfg", "Supersonic - Oasis", 284));

        assertEquals(DEFINITELY_MAYBE, found.albumId());
        assertEquals(6, found.track().getTrackNumber(), "the ATV id is not on the album page; title and length are");
        verify(ytMusic, times(1)).getAlbum(any());
        verify(ytMusic, never()).searchSongRows(any(), anyInt());
    }

    @Test
    void liveForever_namedOnTheDeluxeEdition_joinsSupersonicOnThePlainDefinitelyMaybe() {
        // YouTube Music puts these two songs of one album on two editions; filed as named, one playlist
        // would make two Definitely Maybe folders (critic-product #4).
        SongAlbumResolver.Found found = resolve(song("1HqpFoHv6qg", "Live Forever - Oasis", 277));

        assertEquals(DEFINITELY_MAYBE, found.albumId());
        assertEquals("Definitely Maybe", found.album().getTitle());
        assertEquals(3, found.track().getTrackNumber());
        verify(ytMusic).getAlbum(DEFINITELY_MAYBE_DELUXE);
        verify(ytMusic).getAlbum(DEFINITELY_MAYBE);
    }

    @Test
    void anOfficialVideo_hasNoAlbumOfItsOwn_soTheSongSearchFindsTheAudioUploadsAlbum() {
        SongAlbumResolver.Found found = resolve(song("6hzrDeceEKc", "Wonderwall - Oasis", 279));

        assertEquals("(What's The Story) Morning Glory?", found.album().getTitle());
        assertEquals(3, found.track().getTrackNumber());
        // Two editions share the plain title; the smaller id wins, so every song of it agrees.
        assertEquals("MPREb_IInSY5QXXrW", found.albumId());
        verify(ytMusic).searchSongRows("Wonderwall - Oasis", SongAlbumResolver.SEARCH_ROWS);
    }

    @Test
    void aSingle_isNeverTrusted_soBlindingLightsHasNoAlbum() {
        assertNull(resolve(song("J7p4bzqLvCw", "Blinding Lights - The Weeknd", 202)),
                "its details name the Blinding Lights single; the search finds only singles too");
        assertNull(resolve(song("4NRXx6U8ABQ", "Blinding Lights (Official Video) - The Weeknd", 263)));
    }

    @Test
    void aVariousArtistsCompilation_isNeverTrusted_soDontYouIsNotFiledUnderDriving() {
        assertNull(resolve(song("IL5TvxJqRBU",
                "Don't You (Forget About Me) [2001 Remastered Version] - Simple Minds", 261)));
        verify(ytMusic).getAlbum("MPREb_EawXzBZLriI");
        verify(ytMusic).getAlbum("MPREb_awmOBzybmCq");
    }

    @Test
    void loseYourself_isNotTrack2OfTheJustLoseItSingle_norOnThe8MileCompilation() {
        assertNull(resolve(song("4wOLVrGHiIU", "Lose Yourself - Eminem", 322)));
        verify(ytMusic).getAlbum("MPREb_WEtHcp9WJWl");
        verify(ytMusic).getAlbum("MPREb_iwMjyngE7Gd");
    }

    @Test
    void aSuggestedPlaylistSong_startsFromTheCuratorsAlbum_withoutAskingForItsDetails() {
        // The curator took BJKpUH2kJQg off the Deluxe edition's page; the plain edition lists the same id.
        when(curator.getEdition("90s-britpop")).thenReturn(Mono.just(new CuratorEdition("Britpop", Map.of(),
                "2026-09-28", 1L, List.of(new CuratorTrack("BJKpUH2kJQg", "Supersonic", List.of("Oasis"),
                        "Definitely Maybe (Deluxe Edition Remastered)", DEFINITELY_MAYBE_DELUXE, 1994, 1L, "top", "")))));

        SongAlbumResolver.Found found = resolve(new SongAlbumResolver.Song("BJKpUH2kJQg", "Supersonic - Oasis",
                null, DownloadType.CURATED, "90s-britpop"));

        assertEquals(DEFINITELY_MAYBE, found.albumId());
        assertEquals(6, found.track().getTrackNumber());
        verify(ytMusic, never()).getSongDetails(any());
    }

    @Test
    void aSuggestedPlaylistSong_whileTheCuratorIsDown_asksForItsDetailsInstead() {
        when(curator.getEdition(any())).thenReturn(Mono.error(new CuratorException("down", true)));

        SongAlbumResolver.Found found = resolve(new SongAlbumResolver.Song("2n5OwsJtrfg", "Supersonic - Oasis",
                284, DownloadType.CURATED, "90s-britpop"));

        assertEquals(DEFINITELY_MAYBE, found.albumId());
    }

    @Test
    void anIdYouTubeDoesNotKnow_isAnswered_none() {
        when(ytMusic.getSongDetails("gone")).thenReturn(Mono.error(new YtMusicBadRequestException("404")));

        assertEquals(Optional.empty(), resolver.resolve(song("gone", "Gone - Nobody", 100)).block());
    }

    // ---- the rules on their own ------------------------------------------------------------------

    @Test
    void match_aRepeatedIdOnOneAlbum_isTheRowWithTheSongsTitle() {
        // The 30th Anniversary edition lists h7-BHdjeEY0 as tracks 21 and 24 under two titles.
        YtMusicDetailResponse.Collection album = answer("albums", "MPREb_rAj03C3fO0c",
                YtMusicDetailResponse.Collection.class).block();
        List<SongAlbumResolver.Identity> id = List.of(new SongAlbumResolver.Identity("h7-BHdjeEY0", 272));

        assertEquals(24, SongAlbumResolver.match("x", album,
                SongAlbumResolver.Want.of(song("h7-BHdjeEY0", "Cigarettes & Alcohol (Sawmills Outtake) - Oasis", 272)), id)
                .track().getTrackNumber());
        assertEquals(21, SongAlbumResolver.match("x", album,
                SongAlbumResolver.Want.of(song("h7-BHdjeEY0", "Up In The Sky (Sawmills Outtake) - Oasis", 272)), id)
                .track().getTrackNumber());
        assertNull(SongAlbumResolver.match("x", album,
                SongAlbumResolver.Want.of(song("h7-BHdjeEY0", "Up In The Sky - Oasis", 272)), id),
                "the studio take is not the outtake, whatever id it came with");
    }

    @Test
    void key_ignoresCasePunctuationAccentsRemasterAndGuestNotes_butKeepsVersions() {
        assertEquals("rock n roll star", SongAlbumResolver.key("Rock 'N' Roll Star"));
        assertEquals(SongAlbumResolver.key("Cigarettes and Alcohol"), SongAlbumResolver.key("Cigarettes & Alcohol"));
        assertEquals("beyonce", SongAlbumResolver.key("Beyoncé"));
        assertEquals("live forever", SongAlbumResolver.key("Live Forever (2014 Remaster)"));
        assertEquals("love the way you lie", SongAlbumResolver.key("Love The Way You Lie (feat. Rihanna)"));
        assertEquals("wonderwall unplugged", SongAlbumResolver.key("Wonderwall (Unplugged)"));
    }

    // ---- the loop --------------------------------------------------------------------------------

    @Test
    void resolveDue_storesTheAlbumsRowThenTheSongsAnswer_andNoneAsANullAlbum() {
        when(repository.songsToResolve(10, NOW.minusSeconds(600), NOW.minus(SongAlbumResolver.RELOOK_AFTER)))
                .thenReturn(Flux.just(song("2n5OwsJtrfg", "Supersonic - Oasis", 284),
                        song("4wOLVrGHiIU", "Lose Yourself - Eminem", 322)));

        resolver.resolveDue().block();

        var order = inOrder(repository);
        order.verify(repository).upsertMedia(argThat(rows -> rows.size() == 1
                && rows.getFirst().equals(new MediaItem(DEFINITELY_MAYBE, "Definitely Maybe", List.of("Oasis"),
                        List.of("UCmMUZbaYdNH0bEd1PAlAqsA"), FIXTURES.get("albums").get(DEFINITELY_MAYBE)
                        .get("thumbnailUrl").asString(), null, 11, 1994, "Album"))));
        order.verify(repository).saveSongAlbum("2n5OwsJtrfg", DEFINITELY_MAYBE, 6, NOW);
        verify(repository).saveSongAlbum(eq("4wOLVrGHiIU"), isNull(), isNull(), eq(NOW));
    }

    @Test
    void resolveDue_anAdapterOutage_storesNothing_soTheSongIsAskedAgain_andTheRestCarryOn() {
        when(repository.songsToResolve(anyInt(), any(), any()))
                .thenReturn(Flux.just(song("down", "Down - Nobody", 1), song("2n5OwsJtrfg", "Supersonic - Oasis", 284)));
        when(ytMusic.getSongDetails("down")).thenReturn(Mono.error(new YtMusicUnavailableException("502")));

        resolver.resolveDue().block();

        verify(repository, never()).saveSongAlbum(eq("down"), any(), any(), any());
        verify(repository).saveSongAlbum("2n5OwsJtrfg", DEFINITELY_MAYBE, 6, NOW);
    }

    @Test
    void runsOnItsOwnInterval_whenTheOrganiserIsOn_andNotAtAllWhenItIsOff() {
        when(repository.songsToResolve(anyInt(), any(), any())).thenReturn(Flux.empty());
        SongAlbumResolver ticking = new SongAlbumResolver(repository, ytMusic, curator, organiser,
                Clock.systemUTC(), Duration.ofMillis(20), 10);
        ticking.start();
        try {
            verify(repository, timeout(2000).atLeast(2)).songsToResolve(anyInt(), any(), any());
        } finally {
            ticking.stop();
        }

        DownloadTaskRepository untouched = mock(DownloadTaskRepository.class);
        LibraryOrganiser off = mock(LibraryOrganiser.class);
        SongAlbumResolver idle = new SongAlbumResolver(untouched, ytMusic, curator, off,
                Clock.systemUTC(), Duration.ofMillis(20), 10);
        idle.start();
        verify(untouched, after(200).never()).songsToResolve(anyInt(), any(), any());
        idle.stop();
    }
}
