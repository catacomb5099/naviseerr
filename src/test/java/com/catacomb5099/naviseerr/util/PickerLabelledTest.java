package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.download.SearchQueryTiers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plays the whole picker over the labelled search-lab fixture, one song at a time: keep the files the matcher
 * accepts at flac or 320 kbps, take the one whose length the most accepted files share (what
 * SlskdSearchResultProcessor does), and ask whether that single download is the right song in the right version.
 * This is the number a listener feels; per-file precision and recall in TrackMatchingServiceLabelledTest are
 * the parts that build it.
 * <p>
 * Only files the first query returned (variant "A", the one the app sends) take part: the picker chooses among
 * one search's results, never a mix of the first query and the fallbacks.
 */
class PickerLabelledTest {

    private static final double MIN_TOP_PICK_EXACT = 0.95;
    private static final int MIN_SONGS_WITH_A_PICK = 650;

    private final TrackMatchingService matcher = new TrackMatchingService();

    @Test
    void topPickPerSong_isTheRequestedSongAndVersion() throws Exception {
        Map<String, List<JsonNode>> bySong = new LinkedHashMap<>();
        for (JsonNode r : TrackMatchingServiceLabelledTest.fixture()) {
            if (!r.get("variants").toString().contains("\"A\"")) continue;
            bySong.computeIfAbsent(r.get("song").asText(), k -> new ArrayList<>()).add(r);
        }
        int picks = 0, exact = 0;
        for (List<JsonNode> rows : bySong.values()) {
            List<JsonNode> accepted = new ArrayList<>();
            Map<Integer, Integer> lengthCount = new HashMap<>();
            for (JsonNode r : rows) {
                boolean hq = "flac".equals(r.get("ext").asText()) || r.get("bitRate").asInt(0) >= 320;
                if (hq && matcher.isMatch(SearchQueryTiers.pickerName(r.get("request").asText()), r.get("path").asText())) {
                    accepted.add(r);
                    lengthCount.merge(r.get("length").asInt(0), 1, Integer::sum);
                }
            }
            if (accepted.isEmpty()) continue;
            JsonNode pick = accepted.get(0);
            for (JsonNode r : accepted) {
                if (lengthCount.get(r.get("length").asInt(0)) > lengthCount.get(pick.get("length").asInt(0))) pick = r;
            }
            picks++;
            if (pick.get("exact").asBoolean()) exact++;
        }
        double ratio = (double) exact / picks;
        System.out.printf("Picker on %d labelled songs: songs with a pick %d, top pick exact %d (%.3f)%n",
                bySong.size(), picks, exact, ratio);
        assertTrue(picks >= MIN_SONGS_WITH_A_PICK, "only " + picks + " songs got a pick");
        assertTrue(ratio >= MIN_TOP_PICK_EXACT, "top pick exact fell to " + ratio);
    }
}
