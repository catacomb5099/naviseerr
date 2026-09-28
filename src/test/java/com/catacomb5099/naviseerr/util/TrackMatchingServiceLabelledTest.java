package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.download.SearchQueryTiers;
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
 * "title - artist" string naviseerr builds from the YouTube entry, qualifiers included. It is run through
 * {@link SearchQueryTiers#pickerName} first, exactly as {@code DownloadStepExecutor} does.
 * <p>
 * The floors below are a few points under what the matcher scored when the fixture was created, so a change
 * that makes the picker worse fails here instead of being argued about. Raise them when the picker improves.
 * See docs/decisions/soulseek-search-lab-26-09-2026.md and tools/search-lab/.
 */
class TrackMatchingServiceLabelledTest {

    private static final double MIN_PRECISION = 0.86;
    private static final double MIN_RECALL = 0.85;
    private static final double MIN_TITLE_ONLY_PRECISION = 0.85;
    private static final double MIN_TITLE_ONLY_RECALL = 0.65;

    private final TrackMatchingService matcher = new TrackMatchingService();

    @Test
    void isMatch_onLabelledSoulseekResults_holdsPrecisionAndRecallFloors() throws Exception {
        List<JsonNode> rows = fixture();
        int tp = 0, fp = 0, fn = 0;
        for (JsonNode r : rows) {
            boolean exact = r.get("exact").asBoolean();
            boolean accepted = matcher.isMatch(SearchQueryTiers.pickerName(r.get("request").asText()), r.get("path").asText());
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

    /**
     * The title-only searches of the lab (variant "C": the wording named no artist). Judged as the app now
     * judges them, with the artist required somewhere in the path, against judged on the filename alone.
     * Floors set from the run that introduced the rule; the fixture keeps only the last folders of each
     * path, so the recall here understates what a full Soulseek path gives.
     */
    @Test
    void titleOnlyResults_withTheArtistRequiredInThePath_areMuchMorePrecise() throws Exception {
        int tp = 0, fp = 0, fn = 0, fpBefore = 0;
        for (JsonNode r : fixture()) {
            if (!r.get("variants").toString().contains("\"C\"")) continue;
            boolean exact = r.get("exact").asBoolean();
            String request = SearchQueryTiers.pickerName(r.get("request").asText());
            String title = request.contains(" - ") ? request.substring(0, request.indexOf(" - ")) : request;
            boolean accepted = matcher.grade(request, r.get("path").asText(), title) == TrackMatchingService.Match.EXACT;
            if (!exact && matcher.isMatch(request, r.get("path").asText())) fpBefore++;
            if (accepted && exact) tp++;
            else if (accepted) fp++;
            else if (exact) fn++;
        }
        double precision = (double) tp / (tp + fp);
        double recall = (double) tp / (tp + fn);
        System.out.printf("Title-only results, artist required in path: precision %.3f, recall %.3f (tp=%d fp=%d fn=%d); wrong files accepted before %d, now %d%n",
                precision, recall, tp, fp, fn, fpBefore, fp);
        assertTrue(fp < fpBefore, "the artist rule should reject wrong files the filename alone let through");
        assertTrue(precision >= MIN_TITLE_ONLY_PRECISION, "title-only precision fell to " + precision);
        assertTrue(recall >= MIN_TITLE_ONLY_RECALL, "title-only recall fell to " + recall);
    }

    static List<JsonNode> fixture() throws Exception {
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
