package com.catacomb5099.naviseerr.services.slskd;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.catacomb5099.naviseerr.services.slskd.DownloadQuality.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadQualityTest {

    @Test
    void aFilesTier_comesFromTheSuffixFirst_thenFromTheReportedBitRate() {
        // Shapes as slskd sends them (04-10-2026): lossless files carry no bit rate, and the extension field is blank or wrong.
        assertEquals(Optional.of(LOSSLESS), of("Music\\Daft Punk\\Discovery\\06 - Night Vision.flac", "mp3", null));
        assertEquals(Optional.of(LOSSLESS), of("Music\\Mr. Big\\Wild World", "flac", null), "no suffix: the field is all there is");
        assertEquals(Optional.of(LOSSLESS), of("Music\\Daft Punk\\Discovery\\01 - One More Time.wav", "", null));
        // The MP3 rungs, and the VBR averages that land between them (LAME V0 260-271, V2 190-205).
        assertEquals(Optional.of(VERY_HIGH), of("x\\02 - Aerodynamic.mp3", "", 320));
        assertEquals(Optional.of(HIGH), of("x\\a.mp3", "", 256));
        assertEquals(Optional.of(HIGH), of("x\\a.mp3", "", 267));
        assertEquals(Optional.of(NORMAL), of("x\\a.mp3", "", 192));
        assertEquals(Optional.of(NORMAL), of("x\\a.mp3", "", 203));
        assertEquals(Optional.of(NORMAL), of("x\\a.mp3", "", 160), "160 is equidistant between 128 and 192 and goes up");
        assertEquals(Optional.of(LOW), of("x\\a.mp3", "", 128));
        assertEquals(Optional.of(LOW), of("x\\a.mp3", "", 64));
        assertEquals(Optional.of(VERY_HIGH), of("x\\a.m4a", "", 1411), "ALAC in .m4a counts by its bit rate; not widened on purpose");
        // The boundaries: the midpoints of the measured rungs.
        assertEquals(Optional.of(VERY_HIGH), of("x\\a.mp3", "", 288));
        assertEquals(Optional.of(HIGH), of("x\\a.mp3", "", 287));
        assertEquals(Optional.of(HIGH), of("x\\a.mp3", "", 224));
        assertEquals(Optional.of(NORMAL), of("x\\a.mp3", "", 223));
        assertEquals(Optional.of(LOW), of("x\\a.mp3", "", 159));
        // No tier: a lossy file whose bit rate slskd did not report (2.7% of files, mostly m4a and opus), or no format at all.
        assertEquals(Optional.empty(), of("x\\a.m4a", "", null));
        assertEquals(Optional.empty(), of("x\\a.opus", "", 0));
        assertEquals(Optional.empty(), of("Music\\Mr. Big\\To Be With You", null, null));
    }

    @Test
    void theChosenTierComesFirst_thenTheNearest_tiesGoingUp() {
        assertEquals(List.of(LOSSLESS, VERY_HIGH, HIGH, NORMAL, LOW), nearestFirst(LOSSLESS));
        assertEquals(List.of(VERY_HIGH, LOSSLESS, HIGH, NORMAL, LOW), nearestFirst(VERY_HIGH));
        assertEquals(List.of(HIGH, VERY_HIGH, NORMAL, LOSSLESS, LOW), nearestFirst(HIGH));
        assertEquals(List.of(NORMAL, HIGH, LOW, VERY_HIGH, LOSSLESS), nearestFirst(NORMAL));
        assertEquals(List.of(LOW, NORMAL, HIGH, VERY_HIGH, LOSSLESS), nearestFirst(LOW));
        assertEquals(LOSSLESS, DEFAULT, "what the picker effectively preferred before the setting existed");
    }

    private static List<DownloadQuality> nearestFirst(DownloadQuality chosen) {
        return Arrays.stream(values()).sorted(Comparator.comparingInt(chosen::distance)).toList();
    }
}
