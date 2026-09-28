package com.catacomb5099.naviseerr.curator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One line of {@code GET /v1/editions}: the latest edition the curator holds for one category.
 *
 * @param year the category's Discogs year range ("1980-1989", or "1950-2026" for an all-time list); null
 *             from a curator that predates it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorEditionSummary(String category, String title, String year, String editionDate,
                                    Integer trackCount) {
}
