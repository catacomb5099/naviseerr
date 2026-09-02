package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.request.TrackQuery;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlskdQueryBuilderTest {

    private final SlskdQueryBuilder builder = new SlskdQueryBuilder();

    @Test
    void build_primaryArtistOnlyByDefault_prependsOnlyTheFirstArtist() {
        ReflectionTestUtils.setField(builder, "useAllArtists", false);

        String result = builder.build(new TrackQuery("Riptide", List.of("Vance Joy", "Someone Else")));

        assertEquals("Vance Joy Riptide", result);
    }

    @Test
    void build_stripsPunctuation() {
        ReflectionTestUtils.setField(builder, "useAllArtists", false);

        String result = builder.build(new TrackQuery("Twenty-One!", List.of("Bad, Bunny")));

        assertEquals("Bad Bunny TwentyOne", result);
    }

    @Test
    void build_emptyArtists_degradesToTheSongNameAlone() {
        ReflectionTestUtils.setField(builder, "useAllArtists", false);

        String result = builder.build(new TrackQuery("Riptide", List.of()));

        assertEquals("Riptide", result);
    }

    @Test
    void build_useAllArtistsEnabled_joinsEveryArtist() {
        ReflectionTestUtils.setField(builder, "useAllArtists", true);

        String result = builder.build(new TrackQuery("We Own It",
                List.of("Metro Boomin", "Travis Scott", "Future")));

        assertEquals("Metro Boomin Travis Scott Future We Own It", result);
    }

    @Test
    void build_useAllArtistsEnabled_butEmptyArtists_stillDegradesToSongNameAlone() {
        ReflectionTestUtils.setField(builder, "useAllArtists", true);

        String result = builder.build(new TrackQuery("Riptide", List.of()));

        assertEquals("Riptide", result);
    }
}
