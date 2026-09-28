package com.catacomb5099.naviseerr.curator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One category's outcome inside a {@link CuratorRun}; every field but {@code key} may be null. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorCategoryResult(String key, String status, String editionDate, Integer trackCount,
                                    String message) {
}
