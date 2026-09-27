package com.catacomb5099.naviseerr.curator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** One curator run, as returned by {@code POST /v1/runs} and {@code GET /v1/runs/{runId}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorRun(String runId, String status, String requestedAt, String startedAt,
                         String finishedAt, List<CuratorCategoryResult> categories) {

    /**
     * "succeeded", "partial" (some categories got an edition, some did not) or "failed"; anything else
     * ("queued", "running") means keep polling.
     */
    public boolean isFinal() {
        return "succeeded".equals(status) || "partial".equals(status) || "failed".equals(status);
    }

    public List<CuratorCategoryResult> categories() {
        return categories == null ? List.of() : categories;
    }
}
