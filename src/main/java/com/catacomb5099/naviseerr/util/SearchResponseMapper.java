package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.schema.response.Album;
import com.catacomb5099.naviseerr.schema.response.Artist;
import com.catacomb5099.naviseerr.schema.response.SearchResponse;
import com.catacomb5099.naviseerr.schema.response.Track;
import com.catacomb5099.naviseerr.services.lastfm.model.LastFmSearchResponse;
import reactor.core.publisher.Mono;

import java.util.List;

public class SearchResponseMapper {
    public static Mono<SearchResponse> mapFromLastFmResponse(Mono<LastFmSearchResponse> lastFmResponse) {
        return lastFmResponse.flatMap(lastFmSearchResponse ->
            Mono.just(new SearchResponse(
                    lastFmSearchResponse.getResults().getTrackList().stream().map(SearchResponseMapper::mapFromLastFMTrack).toList(),
                    lastFmSearchResponse.getResults().getAlbumList().stream().map(SearchResponseMapper::mapFromLastFMAlbum).toList(),
                    lastFmSearchResponse.getResults().getArtistList().stream().map(SearchResponseMapper::mapFromLastFMArtist).toList()
            ))
        );
    }

    private static Track mapFromLastFMTrack(LastFmSearchResponse.LastFMTrack lastFMTrack) {
        String imageUrl = lastFMTrack.getImages().isEmpty() ? "" :
                (lastFMTrack.getImages().getFirst().getUrl() != null ? lastFMTrack.getImages().getFirst().getUrl() : "");
        return new Track(
                lastFMTrack.getMbid(),
                imageUrl,
                "",
                lastFMTrack.getName(),
                List.of(lastFMTrack.getArtist()),
                "lol",
                0
        );
    }

    private static Artist mapFromLastFMArtist(LastFmSearchResponse.LastFMArtist lastFMArtist) {
        String imageUrl = lastFMArtist.getImages().isEmpty() ? "" :
                (lastFMArtist.getImages().get(2).getUrl() != null ? lastFMArtist.getImages().get(2).getUrl() : "");
        return new Artist(
            lastFMArtist.getMbid(),
            imageUrl,
            lastFMArtist.getName()
        );
    }

    private static Album mapFromLastFMAlbum(LastFmSearchResponse.LastFMAlbum lastFMAlbum) {
        String imageUrl = lastFMAlbum.getImages().isEmpty() ? "" :
                (lastFMAlbum.getImages().get(2).getUrl() != null ? lastFMAlbum.getImages().get(2).getUrl() : "");
        return new Album(
            lastFMAlbum.getMbid(),
            imageUrl,
            lastFMAlbum.getName(),
            List.of(lastFMAlbum.getArtist()),
            0
        );
    }
}
