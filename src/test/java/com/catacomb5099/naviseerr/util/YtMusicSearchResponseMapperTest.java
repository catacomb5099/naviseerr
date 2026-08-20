package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.schema.response.Album;
import com.catacomb5099.naviseerr.schema.response.Artist;
import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.schema.response.Track;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YtMusicSearchResponseMapperTest {

    private static YtMusicSearchResponse.Item songItem() {
        return YtMusicSearchResponse.Item.builder()
                .type("song")
                .videoId("hpSrLjc5SMs")
                .title("Wonderwall")
                .artists(List.of(
                        YtMusicSearchResponse.ArtistRef.builder().name("Oasis").channelId("UCmMUZbaYdNH0bEd1PAlAqsA").build()
                ))
                .album(YtMusicSearchResponse.AlbumRef.builder().name("(What's The Story) Morning Glory?").browseId("MPREb_PITqkpE6ExP").build())
                .thumbnailUrl("https://example.com/song.jpg")
                .durationSeconds(259)
                .explicit(false)
                .build();
    }

    private static YtMusicSearchResponse.Item albumItem() {
        return YtMusicSearchResponse.Item.builder()
                .type("album")
                .browseId("MPREb_Hl8XJR59OrY")
                .playlistId("OLAK5uy_m--RCG58SjXLvgRiw0pASnMY6YjE8q3NU")
                .title("Definitely Maybe")
                .artists(List.of(YtMusicSearchResponse.ArtistRef.builder().name("Oasis").channelId("UCmMUZbaYdNH0bEd1PAlAqsA").build()))
                .thumbnailUrl("https://example.com/album.jpg")
                .year(1994)
                .build();
    }

    private static YtMusicSearchResponse.Item artistItem() {
        return YtMusicSearchResponse.Item.builder()
                .type("artist")
                .browseId("UCmMUZbaYdNH0bEd1PAlAqsA")
                .title("Oasis")
                .artists(List.of())
                .thumbnailUrl("https://example.com/artist.jpg")
                .build();
    }

    private static YtMusicSearchResponse.Item videoItem() {
        return YtMusicSearchResponse.Item.builder()
                .type("video")
                .videoId("ajHr7fEmfms")
                .title("Oasis 'Wonderwall' Live")
                .build();
    }

    private static YtMusicSearchResponse.Item episodeItem() {
        return YtMusicSearchResponse.Item.builder()
                .type("episode")
                .videoId("JN709NLk_ps")
                .title("Rock Feed News")
                .build();
    }

    private static YtMusicSearchResponse.Item podcastItem() {
        return YtMusicSearchResponse.Item.builder()
                .type("podcast")
                .browseId("MPSPPL7lGdgoUKJQas6jpVtb5Q__3NHbrbC1oq")
                .title("Pointless Reviews")
                .build();
    }

    private static YtMusicSearchResponse.Item playlistItem() {
        return YtMusicSearchResponse.Item.builder()
                .type("playlist")
                .browseId("VLPLK1PkWQlWtnNfovRdGWpKffO1Wdi2kvDx")
                .title("Wonderwall - Oasis")
                .build();
    }

    @Test
    void songs_mapVideoIdToTrackId_andArtistsToDisplayNames_notChannelIds() {
        YtMusicSearchResponse response = YtMusicSearchResponse.builder().items(List.of(songItem())).build();

        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(response);

        assertEquals(1, result.getTracks().size());
        Track track = result.getTracks().get(0);
        assertEquals("hpSrLjc5SMs", track.getId());
        assertEquals("Wonderwall", track.getName());
        assertEquals(List.of("Oasis"), track.getArtists());
        assertFalse(track.getArtists().contains("UCmMUZbaYdNH0bEd1PAlAqsA"));
        assertTrue(result.getAlbums().isEmpty());
        assertTrue(result.getArtists().isEmpty());
    }

    @Test
    void songs_iconURL_isCapitalUrlAndFromThumbnail() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(songItem())).build());

        assertEquals("https://example.com/song.jpg", result.getTracks().get(0).getIconURL());
    }

    @Test
    void songs_streamURL_isEmpty_andYear_isZero_neitherHasAReader() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(songItem())).build());

        Track track = result.getTracks().get(0);
        assertEquals("", track.getStreamURL());
        assertEquals(0, track.getYear());
    }

    @Test
    void songs_albumId_comesFromAlbumBrowseId_notAudioPlaylistId() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(songItem())).build());

        assertEquals("MPREb_PITqkpE6ExP", result.getTracks().get(0).getAlbumId());
    }

    @Test
    void songs_missingAlbum_albumIdIsEmptyNotNull() {
        YtMusicSearchResponse.Item item = songItem().toBuilder().album(null).build();
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(item)).build());

        assertEquals("", result.getTracks().get(0).getAlbumId());
    }

    @Test
    void songs_missingThumbnail_iconURLIsEmptyNotNull() {
        YtMusicSearchResponse.Item item = songItem().toBuilder().thumbnailUrl(null).build();
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(item)).build());

        assertEquals("", result.getTracks().get(0).getIconURL());
    }

    @Test
    void songs_nullArtists_mapsToEmptyList_notNullPointerException() {
        YtMusicSearchResponse.Item item = songItem().toBuilder().artists(null).build();
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(item)).build());

        assertTrue(result.getTracks().get(0).getArtists().isEmpty());
    }

    @Test
    void songs_itemsOfOtherTypes_areFilteredOut() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(songItem(), albumItem(), artistItem())).build());

        assertEquals(1, result.getTracks().size());
    }

    @Test
    void albums_mapBrowseIdToAlbumId_andYearFromItem() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(albumItem())).build());

        assertEquals(1, result.getAlbums().size());
        Album album = result.getAlbums().get(0);
        assertEquals("MPREb_Hl8XJR59OrY", album.getId());
        assertEquals(1994, album.getYear());
        assertEquals(List.of("Oasis"), album.getArtists());
    }

    @Test
    void albums_nullYear_defaultsToZero_notNullPointerOnUnboxing() {
        YtMusicSearchResponse.Item item = albumItem().toBuilder().year(null).build();
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(item)).build());

        assertEquals(0, result.getAlbums().get(0).getYear());
    }

    @Test
    void albums_itemsOfOtherTypes_areFilteredOut() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(songItem(), albumItem())).build());

        assertEquals(1, result.getAlbums().size());
        assertEquals("MPREb_Hl8XJR59OrY", result.getAlbums().get(0).getId());
    }

    @Test
    void artists_useLowercaseIconUrl_distinctFromTrackAndAlbumIconURL() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(artistItem())).build());

        assertEquals(1, result.getArtists().size());
        Artist artist = result.getArtists().get(0);
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", artist.getId());
        assertEquals("Oasis", artist.getName());
        assertEquals("https://example.com/artist.jpg", artist.getIconUrl());
    }

    @Test
    void nullItemsList_mapsToEmptyResults_notNullPointerException() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(null).build());

        assertTrue(result.getTracks().isEmpty());
    }

    @Test
    void nullResponse_mapsToEmptyResults_notNullPointerException() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(null);

        assertTrue(result.getTracks().isEmpty());
        assertTrue(result.getAlbums().isEmpty());
        assertTrue(result.getArtists().isEmpty());
    }

    @Test
    void mixedResponse_partitionsAllThreeTypes_inOneCall_andDropsNonMusicTypes() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder()
                        .items(List.of(songItem(), videoItem(), artistItem(), albumItem(),
                                playlistItem(), episodeItem(), podcastItem()))
                        .build());

        assertEquals(1, result.getTracks().size());
        assertEquals(1, result.getAlbums().size());
        assertEquals(1, result.getArtists().size());
        assertEquals("hpSrLjc5SMs", result.getTracks().get(0).getId());
        assertEquals("MPREb_Hl8XJR59OrY", result.getAlbums().get(0).getId());
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", result.getArtists().get(0).getId());
    }

    @Test
    void mixedResponse_topResultArtistWithNoBrowseId_isStillMapped_notDropped() {
        // A bare-name query's "Top result" artist card omits browseId/artist entirely -- the
        // adapter falls back to artists[0], so browseId here legitimately arrives as null. The
        // partition must not require browseId to classify an item as an artist.
        YtMusicSearchResponse.Item topResultArtist = YtMusicSearchResponse.Item.builder()
                .type("artist")
                .browseId(null)
                .title(null)
                .artists(List.of(YtMusicSearchResponse.ArtistRef.builder()
                        .name("Oasis").channelId("UCmMUZbaYdNH0bEd1PAlAqsA").build()))
                .build();

        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(topResultArtist)).build());

        assertEquals(1, result.getArtists().size());
        assertEquals("", result.getArtists().get(0).getId());
        assertEquals("", result.getArtists().get(0).getName());
    }

    @Test
    void mixedResponse_nullOrUnknownResultType_isSkipped_notThrown() {
        YtMusicSearchResponse.Item nullType = songItem().toBuilder().type(null).build();
        YtMusicSearchResponse.Item stationType = songItem().toBuilder().type("station").build();

        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder().items(List.of(nullType, stationType, songItem())).build());

        assertEquals(1, result.getTracks().size());
    }

    @Test
    void mixedResponse_preservesSourceOrderWithinEachTypeList() {
        YtMusicSearchResponse.Item firstSong = songItem().toBuilder().videoId("first").build();
        YtMusicSearchResponse.Item secondSong = songItem().toBuilder().videoId("second").build();

        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchResponse.builder()
                        .items(List.of(firstSong, albumItem(), secondSong))
                        .build());

        assertEquals(List.of("first", "second"),
                result.getTracks().stream().map(Track::getId).toList());
    }
}
