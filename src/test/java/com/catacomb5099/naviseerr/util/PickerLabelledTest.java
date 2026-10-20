package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.download.SearchQueryTiers;
import com.catacomb5099.naviseerr.services.slskd.DownloadQuality;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plays the whole picker over the labelled search-lab fixture, one song at a time: keep the files the matcher
 * accepts that have a quality tier (a lossless suffix or a reported bit rate), take the one nearest the chosen
 * quality and, among those, the one whose length the most accepted files share (what SlskdSearchResultProcessor
 * does), and ask whether that single download is the right song in the right version. This is the number a
 * listener feels; per-file precision and recall in TrackMatchingServiceLabelledTest are the parts that build it.
 * <p>
 * Only files the first query returned (variant "A", the one the app sends) take part: the picker chooses among
 * one search's results, never a mix of the first query and the fallbacks. The chooser is the default, Lossless:
 * before 19-10-2026 (lossless and 320 kbps as equals, the quickest sharer deciding) the lab landed on lossless
 * 61% of the time with the same exactness; the Lossless chooser must land on it nearly always.
 */
class PickerLabelledTest {

    private static final double MIN_TOP_PICK_EXACT = 0.95;
    private static final double MIN_LOSSLESS_PICKS = 0.95;
    private static final int MIN_SONGS_WITH_A_PICK = 650;

    private final TrackMatchingService matcher = new TrackMatchingService();

    @Test
    void topPickPerSong_isTheRequestedSongAndVersion_inTheChosenQuality() throws Exception {
        DownloadQuality chosen = DownloadQuality.DEFAULT;
        Map<String, List<JsonNode>> bySong = new LinkedHashMap<>();
        for (JsonNode r : TrackMatchingServiceLabelledTest.fixture()) {
            if (!r.get("variants").toString().contains("\"A\"")) continue;
            bySong.computeIfAbsent(r.get("song").asText(), k -> new ArrayList<>()).add(r);
        }
        int picks = 0, exact = 0, lossless = 0;
        for (List<JsonNode> rows : bySong.values()) {
            List<JsonNode> accepted = new ArrayList<>();
            Map<Integer, Integer> lengthCount = new HashMap<>();
            Map<JsonNode, DownloadQuality> tiers = new HashMap<>();
            for (JsonNode r : rows) {
                Optional<DownloadQuality> tier = DownloadQuality.of(r.get("path").asText(), r.get("ext").asText(), r.get("bitRate").asInt(0));
                if (tier.isPresent() && matcher.isMatch(SearchQueryTiers.pickerName(r.get("request").asText()), r.get("path").asText())) {
                    accepted.add(r);
                    tiers.put(r, tier.get());
                    lengthCount.merge(r.get("length").asInt(0), 1, Integer::sum);
                }
            }
            if (accepted.isEmpty()) continue;
            accepted.sort(Comparator.comparingInt((JsonNode r) -> chosen.distance(tiers.get(r)))
                    .thenComparingInt(r -> -lengthCount.get(r.get("length").asInt(0))));
            JsonNode pick = accepted.getFirst();
            picks++;
            if (pick.get("exact").asBoolean()) exact++;
            if (tiers.get(pick) == DownloadQuality.LOSSLESS) lossless++;
        }
        double ratio = (double) exact / picks;
        double losslessRatio = (double) lossless / picks;
        System.out.printf("Picker on %d labelled songs (chooser %s): songs with a pick %d, top pick exact %d (%.3f), lossless %d (%.3f)%n",
                bySong.size(), chosen, picks, exact, ratio, lossless, losslessRatio);
        assertTrue(picks >= MIN_SONGS_WITH_A_PICK, "only " + picks + " songs got a pick");
        assertTrue(ratio >= MIN_TOP_PICK_EXACT, "top pick exact fell to " + ratio);
        assertTrue(losslessRatio >= MIN_LOSSLESS_PICKS, "a Lossless chooser got lossless for only " + losslessRatio);
    }
}
