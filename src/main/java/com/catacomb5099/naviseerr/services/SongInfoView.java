package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicDetailResponse;
import com.catacomb5099.naviseerr.services.ytmusic.model.YtMusicSearchResponse;

import java.util.List;
import java.util.Objects;

/**
 * One song as the client shows it on its info page: header, album, a few numbers and the credits
 * YouTube Music lists (performed / written / produced by, label).
 *
 * @param id       the id the client REQUESTED, same rule as {@link CollectionView}.
 * @param iconURL  capital {@code URL}, matching {@code Track} on the search contract; {@code ""} when
 *                 the adapter has no picture, never null.
 * @param album    null for an official-video id -- YouTube Music knows no album for those.
 * @param explicit null when unknown (official videos), not false.
 * @param credits  empty when YouTube Music has none; {@code role} is YouTube's own heading, e.g.
 *                 "Written by", so the client renders it verbatim and new roles need no code.
 */
public record SongInfoView(String id, String name, List<Ref> artists, Ref album, Integer durationSeconds,
                           Integer year, Long viewCount, String iconURL, Boolean explicit,
                           List<Credit> credits) {

    /** An artist ({@code id} = channel id) or an album ({@code id} = browse id); either id may be null. */
    public record Ref(String id, String name) {
    }

    public record Credit(String role, List<String> names) {
    }

    static SongInfoView from(YtMusicDetailResponse.SongDetails d, String requestedId) {
        List<Ref> artists = d.getArtists() == null ? List.of() : d.getArtists().stream()
                .filter(a -> a.getName() != null)
                .map(a -> new Ref(a.getChannelId(), a.getName()))
                .toList();
        YtMusicSearchResponse.AlbumRef album = d.getAlbum();
        List<Credit> credits = d.getCredits() == null ? List.of() : d.getCredits().stream()
                .filter(c -> c.getRole() != null)
                .map(c -> new Credit(c.getRole(), c.getNames() == null ? List.of()
                        : c.getNames().stream().filter(Objects::nonNull).toList()))
                .toList();
        return new SongInfoView(requestedId, d.getTitle(), artists,
                album == null || album.getName() == null ? null : new Ref(album.getBrowseId(), album.getName()),
                d.getDurationSeconds(), d.getYear(), d.getViewCount(),
                d.getThumbnailUrl() == null ? "" : d.getThumbnailUrl(), d.getExplicit(), credits);
    }
}
