package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import com.catacomb5099.naviseerr.util.TrackMatchingService.Match;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SlskdSearchResultProcessorTest {

    private final SlskdService slskdService = mock(SlskdService.class);
    private final TrackMatchingService trackMatchingService = mock(TrackMatchingService.class);
    private final SlskdSearchResultProcessor processor = new SlskdSearchResultProcessor(slskdService, trackMatchingService);

    @BeforeEach
    void setUp() {
        // sensible defaults
        ReflectionTestUtils.setField(processor, "minBitRate", 128);
        ReflectionTestUtils.setField(processor, "maxFilesPerDownload", 5);
        ReflectionTestUtils.setField(processor, "maxSharerQueue", 50);
    }

    @Test
    void selectBestFiles_emptyResponses_returnsEmptyList() {
        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of());
        when(state.getFileCount()).thenReturn(0);

        StepVerifier.create(processor.selectBestFiles(state, "track", "track"))
                .expectNextMatches(List::isEmpty)
                .verifyComplete();
    }

    @Test
    void selectBestFiles_filtersByRelevance_onlyRelevantIncluded() {
        // two entries, only one matches via TrackMatchingService and both pass bitrate/extension
        SearchResponseItem itemA = mock(SearchResponseItem.class);
        SearchResponseItem itemB = mock(SearchResponseItem.class);
        SearchFile fileA = mock(SearchFile.class);
        SearchFile fileB = mock(SearchFile.class);

        when(itemA.getFiles()).thenReturn(List.of(fileA));
        when(itemB.getFiles()).thenReturn(List.of(fileB));
        when(fileA.getFilename()).thenReturn("match.mp3");
        when(fileB.getFilename()).thenReturn("nope.mp3");
        when(fileA.getBitRate()).thenReturn(Optional.of(192));
        when(fileB.getBitRate()).thenReturn(Optional.of(192));
        when(fileA.getExtension()).thenReturn("mp3");
        when(fileB.getExtension()).thenReturn("mp3");

        when(trackMatchingService.grade(eq("track"), eq("match.mp3"), eq("track"))).thenReturn(Match.EXACT);
        when(trackMatchingService.grade(eq("track"), eq("nope.mp3"), eq("track"))).thenReturn(Match.NONE);

        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of(itemA, itemB));
        when(state.getFileCount()).thenReturn(2);

        StepVerifier.create(processor.selectBestFiles(state, "track", "track"))
                .expectNextMatches(list -> list.size() == 1 && list.getFirst().file().getFilename().equals("match.mp3"))
                .verifyComplete();
    }

    @Test
    void selectBestFiles_keepsUnverifiedFiles_onlyWhenNoFileNamesTheArtist() {
        SearchResponseItem verified = mock(SearchResponseItem.class);
        SearchResponseItem unverified = mock(SearchResponseItem.class);
        SearchFile named = mock(SearchFile.class);
        SearchFile nameless = mock(SearchFile.class);
        when(verified.getFiles()).thenReturn(List.of(named));
        when(unverified.getFiles()).thenReturn(List.of(nameless));
        when(verified.getUsername()).thenReturn("verified");
        when(unverified.getUsername()).thenReturn("unverified");
        for (SearchFile f : List.of(named, nameless)) {
            when(f.getBitRate()).thenReturn(Optional.of(320));
            when(f.getExtension()).thenReturn("mp3");
            when(f.getLength()).thenReturn(Optional.empty());
        }
        when(named.getFilename()).thenReturn("Cher/Believe.mp3");
        when(nameless.getFilename()).thenReturn("Top 1000/Believe.mp3");
        when(trackMatchingService.grade(eq("Believe - Cher"), eq("Cher/Believe.mp3"), eq("Believe"))).thenReturn(Match.EXACT);
        when(trackMatchingService.grade(eq("Believe - Cher"), eq("Top 1000/Believe.mp3"), eq("Believe"))).thenReturn(Match.UNVERIFIED);

        // beside a file that names the artist, the nameless one is dropped
        var both = processor.selectBestFiles(state(verified, unverified), "Believe - Cher", "Believe").block();
        assertEquals(List.of("verified"), both.stream().map(pick -> pick.peer().getUsername()).toList());

        // alone, it is the last resort and is kept
        var alone = processor.selectBestFiles(state(unverified), "Believe - Cher", "Believe").block();
        assertEquals(List.of("unverified"), alone.stream().map(pick -> pick.peer().getUsername()).toList());
        assertEquals(Match.UNVERIFIED, alone.getFirst().grade());
    }

    @Test
    void selectBestFiles_keepsAboveMinAndFlac_filtersOutBelowMin() {
        ReflectionTestUtils.setField(processor, "minBitRate", 160);

        SearchResponseItem above = mock(SearchResponseItem.class);
        SearchResponseItem below = mock(SearchResponseItem.class);
        SearchResponseItem flacItem = mock(SearchResponseItem.class);

        SearchFile fileAbove = mock(SearchFile.class);
        SearchFile fileBelow = mock(SearchFile.class);
        SearchFile fileFlac = mock(SearchFile.class);

        when(above.getFiles()).thenReturn(List.of(fileAbove));
        when(below.getFiles()).thenReturn(List.of(fileBelow));
        when(flacItem.getFiles()).thenReturn(List.of(fileFlac));

        when(fileAbove.getFilename()).thenReturn("a.mp3");
        when(fileBelow.getFilename()).thenReturn("b.mp3");
        when(fileFlac.getFilename()).thenReturn("c.flac");

        when(fileAbove.getBitRate()).thenReturn(Optional.of(192));
        when(fileBelow.getBitRate()).thenReturn(Optional.of(128));
        when(fileFlac.getBitRate()).thenReturn(Optional.empty());

        when(fileAbove.getExtension()).thenReturn("mp3");
        when(fileBelow.getExtension()).thenReturn("mp3");
        when(fileFlac.getExtension()).thenReturn("flac");

        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of(above, below, flacItem));
        when(state.getFileCount()).thenReturn(3);

        StepVerifier.create(processor.selectBestFiles(state, "track", "track"))
                .expectNextMatches(list -> list.size() == 2
                        && list.stream().anyMatch(e -> e.file().getFilename().equals("a.mp3"))
                        && list.stream().anyMatch(e -> e.file().getFilename().equals("c.flac"))
                        && list.stream().noneMatch(e -> e.file().getFilename().equals("b.mp3")))
                .verifyComplete();
    }

    @Test
    void selectBestFiles_ordersByUploadSpeed_descending() {
        // three responses with different upload speeds, all relevant and above min bitrate
        SearchResponseItem fast = mock(SearchResponseItem.class);
        SearchResponseItem med = mock(SearchResponseItem.class);
        SearchResponseItem slow = mock(SearchResponseItem.class);

        SearchFile fastFile = mock(SearchFile.class);
        SearchFile medFile = mock(SearchFile.class);
        SearchFile slowFile = mock(SearchFile.class);

        when(fast.getFiles()).thenReturn(List.of(fastFile));
        when(med.getFiles()).thenReturn(List.of(medFile));
        when(slow.getFiles()).thenReturn(List.of(slowFile));

        when(fastFile.getFilename()).thenReturn("fast.mp3");
        when(medFile.getFilename()).thenReturn("med.mp3");
        when(slowFile.getFilename()).thenReturn("slow.mp3");

        when(fast.getUploadSpeed()).thenReturn(300);
        when(med.getUploadSpeed()).thenReturn(200);
        when(slow.getUploadSpeed()).thenReturn(100);

        when(fastFile.getBitRate()).thenReturn(Optional.of(192));
        when(medFile.getBitRate()).thenReturn(Optional.of(192));
        when(slowFile.getBitRate()).thenReturn(Optional.of(192));

        when(fastFile.getExtension()).thenReturn("mp3");
        when(medFile.getExtension()).thenReturn("mp3");
        when(slowFile.getExtension()).thenReturn("mp3");

        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of(fast, med, slow));
        when(state.getFileCount()).thenReturn(3);

        StepVerifier.create(processor.selectBestFiles(state, "track", "track"))
                .expectNextMatches(list ->
                        list.size() == 3 &&
                                list.getFirst().peer().getUploadSpeed() == 300 &&
                                list.get(1).peer().getUploadSpeed() == 200 &&
                                list.get(2).peer().getUploadSpeed() == 100)
                .verifyComplete();
    }

    @Test
    void selectBestFiles_prefersAFreeSlotOverAFasterBusyPeer() {
        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        // fast, but 40 people ahead of you
        SearchResponseItem busy = peer("busy", 10_000_000, false, 40, file("busy/song.flac"));
        // slower, but can start right now
        SearchResponseItem free = peer("free", 2_000_000, true, 0, file("free/song.flac"));

        var result = processor.selectBestFiles(state(busy, free), "song", "song").block();

        assertEquals("free", result.getFirst().peer().getUsername());
    }

    @Test
    void selectBestFiles_amongFreePeers_prefersTheShorterQueue() {
        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        SearchResponseItem longer = peer("longer", 9_000_000, true, 5, file("longer/song.flac"));
        SearchResponseItem shorter = peer("shorter", 8_000_000, true, 1, file("shorter/song.flac"));

        var result = processor.selectBestFiles(state(longer, shorter), "song", "song").block();

        assertEquals("shorter", result.getFirst().peer().getUsername());
    }

    @Test
    void selectBestFiles_allElseEqual_stillPrefersTheFasterPeer() {
        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        SearchResponseItem slow = peer("slow", 1_000_000, true, 0, file("slow/song.flac"));
        SearchResponseItem fast = peer("fast", 9_000_000, true, 0, file("fast/song.flac"));

        var result = processor.selectBestFiles(state(slow, fast), "song", "song").block();

        assertEquals("fast", result.getFirst().peer().getUsername());
    }

    @Test
    void selectBestFiles_prefersTheLengthMostCandidatesShare_overAFreeSlot() {
        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        // the remix is the only one that can start right now, but two peers agree on 213s
        SearchResponseItem remix = peer("remix", 9_000_000, true, 0, file("remix/song.flac", 312));
        SearchResponseItem a = peer("a", 1_000_000, false, 3, file("a/song.flac", 213));
        SearchResponseItem b = peer("b", 2_000_000, false, 9, file("b/song.flac", 213));

        var result = processor.selectBestFiles(state(remix, a, b), "song", "song").block();

        assertEquals(List.of("a", "b", "remix"), result.stream().map(e -> e.peer().getUsername()).toList());
    }

    @Test
    void selectBestFiles_unknownLength_ranksAfterASharedLength() {
        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        SearchResponseItem unknown = peer("unknown", 9_000_000, true, 0, file("unknown/song.flac", null));
        SearchResponseItem a = peer("a", 1_000_000, false, 3, file("a/song.flac", 213));
        SearchResponseItem b = peer("b", 1_000_000, false, 3, file("b/song.flac", 213));

        var result = processor.selectBestFiles(state(unknown, a, b), "song", "song").block();

        assertEquals(3, result.size());
        assertEquals("unknown", result.getLast().peer().getUsername());
    }

    @Test
    void selectBestFiles_lengthTie_fallsBackToAvailability() {
        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        SearchResponseItem busy = peer("busy", 9_000_000, false, 40, file("busy/song.flac", 213));
        SearchResponseItem free = peer("free", 1_000_000, true, 0, file("free/song.flac", 213));

        var result = processor.selectBestFiles(state(busy, free), "song", "song").block();

        assertEquals("free", result.getFirst().peer().getUsername());
    }

    @Test
    void selectBestFiles_aSharerWithNoSlotAndALongQueue_goesLast_evenWithTheMajorityLength() {
        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        // The measured profile of a sharer that never serves: no free slot, dozens already waiting.
        SearchResponseItem overloaded = peer("overloaded", 9_000_000, false, 80, file("o/song.flac", 213));
        SearchResponseItem a = peer("a", 1_000_000, false, 3, file("a/song.flac", 213));
        SearchResponseItem odd = peer("odd", 2_000_000, true, 0, file("odd/song.flac", 312));

        var result = processor.selectBestFiles(state(overloaded, a, odd), "song", "song").block();

        assertEquals(List.of("a", "odd", "overloaded"),
                result.stream().map(e -> e.peer().getUsername()).toList());
    }

    @Test
    void selectBestFiles_aBusySharerUnderTheQueueThreshold_keepsItsDurationRank() {
        when(trackMatchingService.grade(anyString(), anyString(), anyString())).thenReturn(Match.EXACT);

        SearchResponseItem busy = peer("busy", 9_000_000, false, 50, file("busy/song.flac", 213));
        SearchResponseItem a = peer("a", 1_000_000, false, 3, file("a/song.flac", 213));
        SearchResponseItem odd = peer("odd", 2_000_000, true, 0, file("odd/song.flac", 312));

        var result = processor.selectBestFiles(state(busy, a, odd), "song", "song").block();

        assertEquals(List.of("a", "busy", "odd"),
                result.stream().map(e -> e.peer().getUsername()).toList());
    }

    private SearchResponseItem peer(String username, int uploadSpeed, boolean hasFreeUploadsSlot, int queueLength, SearchFile file) {
        SearchResponseItem item = mock(SearchResponseItem.class);
        when(item.getUsername()).thenReturn(username);
        when(item.getUploadSpeed()).thenReturn(uploadSpeed);
        when(item.getHasFreeUploadsSlot()).thenReturn(hasFreeUploadsSlot);
        when(item.getQueueLength()).thenReturn(queueLength);
        when(item.getFiles()).thenReturn(List.of(file));
        return item;
    }

    private SearchFile file(String filename) {
        return file(filename, null);
    }

    private SearchFile file(String filename, Integer lengthSeconds) {
        SearchFile searchFile = mock(SearchFile.class);
        when(searchFile.getFilename()).thenReturn(filename);
        when(searchFile.getExtension()).thenReturn("flac");
        when(searchFile.getBitRate()).thenReturn(Optional.empty());
        when(searchFile.getLength()).thenReturn(Optional.ofNullable(lengthSeconds));
        return searchFile;
    }

    private SearchState state(SearchResponseItem... peers) {
        SearchState state = mock(SearchState.class);
        when(state.getResponses()).thenReturn(List.of(peers));
        when(state.getFileCount()).thenReturn(peers.length);
        return state;
    }

    @Test
    void spreadAcrossSharers_noSharerGetsASecondFileBeforeEveryoneHasOne() {
        SearchResponseItem a = mock(SearchResponseItem.class);
        SearchResponseItem b = mock(SearchResponseItem.class);
        SearchResponseItem c = mock(SearchResponseItem.class);
        when(a.getUsername()).thenReturn("a");
        when(b.getUsername()).thenReturn("b");
        when(c.getUsername()).thenReturn("c");
        SearchFile a1 = mock(SearchFile.class), a2 = mock(SearchFile.class), a3 = mock(SearchFile.class);
        SearchFile b1 = mock(SearchFile.class), c1 = mock(SearchFile.class);
        TrackMatchingService.Match exact = TrackMatchingService.Match.EXACT;
        List<SlskdSearchResultProcessor.Pick> ranked = List.of(
                new SlskdSearchResultProcessor.Pick(a, a1, exact), new SlskdSearchResultProcessor.Pick(a, a2, exact),
                new SlskdSearchResultProcessor.Pick(a, a3, exact), new SlskdSearchResultProcessor.Pick(b, b1, exact),
                new SlskdSearchResultProcessor.Pick(c, c1, exact));

        List<SlskdSearchResultProcessor.Pick> spread = SlskdSearchResultProcessor.spreadAcrossSharers(ranked);

        assertEquals(List.of(a1, b1, c1, a2, a3), spread.stream().map(SlskdSearchResultProcessor.Pick::file).toList());
    }
}
