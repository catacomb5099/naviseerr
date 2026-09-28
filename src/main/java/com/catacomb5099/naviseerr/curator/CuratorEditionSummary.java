package com.catacomb5099.naviseerr.curator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One line of {@code GET /v1/editions}: the latest edition the curator holds for one category. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorEditionSummary(String category, String title, String editionDate, Integer trackCount) {
}
