package com.catacomb5099.naviseerr.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the matcher against real Soulseek results that were labelled by hand (well, by Sonnet 5, audited)
 * in the search lab of 2026-09-26. Each fixture row is one returned file for one requested song:
 * {@code exact} means "this file is the requested song in the requested version"; {@code request} is the
 * "title - artist" string naviseerr builds from the YouTube entry, qualifiers included.
 * <p>
 * The floors below are a few points under what the matcher scored when the fixture was created, so a change
 * that makes the picker worse fails here instead of being argued about. Raise them when the picker improves.
 * See docs/decisions/soulseek-search-lab-26-09-2026.md and tools/search-lab/.
 */
class TrackMatchingServiceLabelledTest {

    private static final double MIN_PRECISION = 0.69;
    private static final double MIN_RECALL = 0.95;

    private final TrackMatchingService matcher = new TrackMatchingService();

    @Test
    void isMatch_onLabelledSoulseekResults_holdsPrecisionAndRecallFloors() throws Exception {
        List<JsonNode> rows = fixture();
        int tp = 0, fp = 0, fn = 0;
        for (JsonNode r : rows) {
            boolean exact = r.get("exact").asBoolean();
            boolean accepted = matcher.isMatch(r.get("request").asText(), r.get("path").asText());
            if (accepted && exact) tp++;
            else if (accepted) fp++;
            else if (exact) fn++;
        }
        double precision = (double) tp / (tp + fp);
        double recall = (double) tp / (tp + fn);
        System.out.printf("TrackMatchingService on %d labelled files: precision %.3f, recall %.3f (tp=%d fp=%d fn=%d)%n",
                rows.size(), precision, recall, tp, fp, fn);
        assertTrue(precision >= MIN_PRECISION, "precision fell to " + precision);
        assertTrue(recall >= MIN_RECALL, "recall fell to " + recall);
    }

    private static List<JsonNode> fixture() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> rows = new ArrayList<>();
        try (var in = TrackMatchingServiceLabelledTest.class.getResourceAsStream("/search-lab/labelled.jsonl.gz");
             var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) rows.add(mapper.readTree(line));
            }
        }
        return rows;
    }
}
