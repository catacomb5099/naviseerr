package com.catacomb5099.naviseerr.curator;

import java.time.DayOfWeek;
import java.util.List;

/**
 * {@code GET /suggested-playlists}. {@code enabled} is false when this install has no curator
 * configured (no {@code CURATOR_TOKEN}), so the client can hide the section entirely rather than show
 * an empty one; with a curator and no editions yet the list is empty and {@code enabled} is true.
 *
 * @param refreshDay the weekday the weekly refresh runs ("MONDAY"), so the client can say "New edition
 *                   every Monday" the way the streaming services name the day; null when the cron is
 *                   not a plain weekly one or the curator is off.
 * @param playlists  the latest edition per category, as the curator lists them.
 */
public record SuggestedPlaylistsView(boolean enabled, DayOfWeek refreshDay,
                                     List<SuggestedPlaylistSummary> playlists) {

    /** @param editionDate {@code YYYY-MM-DD}; the client turns it into "Updated Monday". */
    public record SuggestedPlaylistSummary(String category, String title, String editionDate, int trackCount) {
        static SuggestedPlaylistSummary from(CuratorEditionSummary s) {
            return new SuggestedPlaylistSummary(s.category(), s.title(), s.editionDate(),
                    s.trackCount() == null ? 0 : s.trackCount());
        }
    }

    static SuggestedPlaylistsView off() {
        return new SuggestedPlaylistsView(false, null, List.of());
    }

    static SuggestedPlaylistsView of(DayOfWeek refreshDay, List<CuratorEditionSummary> editions) {
        return new SuggestedPlaylistsView(true, refreshDay,
                editions.stream().map(SuggestedPlaylistSummary::from).toList());
    }
}
