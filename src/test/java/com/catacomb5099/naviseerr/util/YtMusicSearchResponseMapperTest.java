package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.schema.response.Album;
import com.catacomb5099.naviseerr.schema.response.Artist;
import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.schema.response.Track;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicSearchType;
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

    @Test
    void songs_mapVideoIdToTrackId_andArtistsToDisplayNames_notChannelIds() {
        YtMusicSearchResponse response = YtMusicSearchResponse.builder().items(List.of(songItem())).build();

        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(YtMusicSearchType.SONGS, response);

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
                YtMusicSearchType.SONGS, YtMusicSearchResponse.builder().items(List.of(songItem())).build());

        assertEquals("https://example.com/song.jpg", result.getTracks().get(0).getIconURL());
    }

    @Test
    void songs_streamURL_isEmpty_andYear_isZero_neitherHasAReader() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.SONGS, YtMusicSearchResponse.builder().items(List.of(songItem())).build());

        Track track = result.getTracks().get(0);
        assertEquals("", track.getStreamURL());
        assertEquals(0, track.getYear());
    }

    @Test
    void songs_albumId_comesFromAlbumBrowseId_notAudioPlaylistId() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.SONGS, YtMusicSearchResponse.builder().items(List.of(songItem())).build());

        assertEquals("MPREb_PITqkpE6ExP", result.getTracks().get(0).getAlbumId());
    }

    @Test
    void songs_missingAlbum_albumIdIsEmptyNotNull() {
        YtMusicSearchResponse.Item item = songItem().toBuilder().album(null).build();
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.SONGS, YtMusicSearchResponse.builder().items(List.of(item)).build());

        assertEquals("", result.getTracks().get(0).getAlbumId());
    }

    @Test
    void songs_missingThumbnail_iconURLIsEmptyNotNull() {
        YtMusicSearchResponse.Item item = songItem().toBuilder().thumbnailUrl(null).build();
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.SONGS, YtMusicSearchResponse.builder().items(List.of(item)).build());

        assertEquals("", result.getTracks().get(0).getIconURL());
    }

    @Test
    void songs_nullArtists_mapsToEmptyList_notNullPointerException() {
        YtMusicSearchResponse.Item item = songItem().toBuilder().artists(null).build();
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.SONGS, YtMusicSearchResponse.builder().items(List.of(item)).build());

        assertTrue(result.getTracks().get(0).getArtists().isEmpty());
    }

    @Test
    void songs_itemsOfOtherTypes_areFilteredOut() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.SONGS,
                YtMusicSearchResponse.builder().items(List.of(songItem(), albumItem(), artistItem())).build());

        assertEquals(1, result.getTracks().size());
    }

    @Test
    void albums_mapBrowseIdToAlbumId_andYearFromItem() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.ALBUMS, YtMusicSearchResponse.builder().items(List.of(albumItem())).build());

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
                YtMusicSearchType.ALBUMS, YtMusicSearchResponse.builder().items(List.of(item)).build());

        assertEquals(0, result.getAlbums().get(0).getYear());
    }

    @Test
    void albums_itemsOfOtherTypes_areFilteredOut() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.ALBUMS,
                YtMusicSearchResponse.builder().items(List.of(songItem(), albumItem())).build());

        assertEquals(1, result.getAlbums().size());
        assertEquals("MPREb_Hl8XJR59OrY", result.getAlbums().get(0).getId());
    }

    @Test
    void artists_useLowercaseIconUrl_distinctFromTrackAndAlbumIconURL() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.ARTISTS, YtMusicSearchResponse.builder().items(List.of(artistItem())).build());

        assertEquals(1, result.getArtists().size());
        Artist artist = result.getArtists().get(0);
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", artist.getId());
        assertEquals("Oasis", artist.getName());
        assertEquals("https://example.com/artist.jpg", artist.getIconUrl());
    }

    @Test
    void nullItemsList_mapsToEmptyResults_notNullPointerException() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(
                YtMusicSearchType.SONGS, YtMusicSearchResponse.builder().items(null).build());

        assertTrue(result.getTracks().isEmpty());
    }

    @Test
    void nullResponse_mapsToEmptyResults_notNullPointerException() {
        SearchResponse result = YtMusicSearchResponseMapper.mapToSearchResponse(YtMusicSearchType.SONGS, null);

        assertTrue(result.getTracks().isEmpty());
        assertTrue(result.getAlbums().isEmpty());
        assertTrue(result.getArtists().isEmpty());
    }
}
