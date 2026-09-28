package com.catacomb5099.naviseerr.curator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One edition as the curator wrote it ({@code GET /v1/editions/{category}}): the category's title and
 * Discogs filters, the date, and the 30-50 songs picked that week.
 *
 * @param category the category as configured in the curator's {@code categories.yaml}: {@code title}
 *                 plus the Discogs filters ({@code year}, {@code genre}, {@code style}), all strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorEdition(String title, Map<String, String> category, String editionDate, Long seed,
                             List<CuratorTrack> tracks) {

    public List<CuratorTrack> tracks() {
        return tracks == null ? List.of() : tracks;
    }

    public Map<String, String> category() {
        return category == null ? Map.of() : category;
    }
}
