package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.support.DownloadTaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LibraryOrganiserTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final Duration LOOP = Duration.ofSeconds(2);

    @TempDir Path tmp;
    private Path downloads;
    private Path incomplete;
    private Path root;
    private LibraryOrganiser organiser;

    @BeforeEach
    void setUp() throws IOException {
        // macOS hands out /var/folders/..., a symlink to /private/var/...; the organiser resolves
        // real paths, so the expectations below must start from the same place.
        tmp = tmp.toRealPath();
        downloads = Files.createDirectories(tmp.resolve("downloads"));
        incomplete = Files.createDirectories(tmp.resolve("incomplete"));
        root = tmp.resolve("music");
        organiser = new LibraryOrganiser(downloads.toString(), incomplete.toString(), root.toString(), LOOP);
    }

    private static LibraryOrganiser.Job song(String remote, String title, String... artists) {
        return new LibraryOrganiser.Job(UUID.randomUUID(), DownloadType.SONG, remote, NOW.minusSeconds(5),
                title, List.of(artists), null, List.of());
    }

    private Path put(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), "audio");
    }

    // ---- switching on and off ------------------------------------------------------------------

    @Test
    void offWhenEitherFolderIsUnset() {
        assertFalse(new LibraryOrganiser("", "", root.toString(), LOOP).isEnabled());
        assertFalse(new LibraryOrganiser(downloads.toString(), "", "", LOOP).isEnabled());
        assertTrue(organiser.isEnabled());
    }

    @Test
    void refusesToRunWhenTheLibraryIsInsideSlskdsFolders_orTheOtherWayRound() {
        assertFalse(new LibraryOrganiser(downloads.toString(), "", downloads.resolve("music").toString(), LOOP)
                .isEnabled(), "library inside downloads");
        assertFalse(new LibraryOrganiser(tmp.resolve("downloads").toString(), "", tmp.toString(), LOOP)
                .isEnabled(), "downloads inside library");
        assertFalse(new LibraryOrganiser(downloads.toString(), tmp.toString(), root.toString(), LOOP)
                .isEnabled(), "library inside incomplete");
    }

    // ---- where slskd put the file ----------------------------------------------------------------

    @Test
    void locate_usesTheLastRemoteFolderName_forBothSeparatorStyles() throws IOException {
        Path file = put(downloads.resolve("Doolittle"), "05 Pixies - Here Comes Your Man.flac");

        assertEquals(file, organiser.locate("@@abcd\\music\\Pixies\\Doolittle\\05 Pixies - Here Comes Your Man.flac"));
        assertEquals(file, organiser.locate("music/Pixies/Doolittle/05 Pixies - Here Comes Your Man.flac"));
    }

    @Test
    void locate_findsAFileSharedAtThePeersRoot_directlyInDownloads() throws IOException {
        Path file = put(downloads, "song.mp3");

        assertEquals(file, organiser.locate("@@abcde\\song.mp3"));
        assertEquals(file, organiser.locate("song.mp3"));
    }

    @Test
    void locate_fallsBackToSearching_whenSlskdRenamedTheFileOnCollision() throws IOException {
        Path renamed = put(downloads.resolve("Doolittle"), "05 - Debaser_639000000000000000.flac");

        assertEquals(renamed, organiser.locate("Pixies\\Doolittle\\05 - Debaser.flac"));
    }

    @Test
    void locate_neverTakesASameNamedFileFromAnotherFolder() throws IOException {
        // Another album's '05 - Debaser.flac' (or the user's own download) is not this song's file.
        put(downloads.resolve("Other"), "05 - Debaser.flac");
        assertNull(organiser.locate("Pixies\\Doolittle\\05 - Debaser.flac"));

        Path renamed = put(downloads.resolve("Doolittle"), "05 - Debaser_639000000000000000.flac");
        assertEquals(renamed, organiser.locate("Pixies\\Doolittle\\05 - Debaser.flac"));
    }

    @Test
    void locate_returnsNullWhenTheFileIsNotThereYet() throws IOException {
        assertNull(organiser.locate("Pixies\\Doolittle\\05 - Debaser.flac"));
    }

    @Test
    void locate_neverClimbsOutOfTheDownloadsFolder() throws IOException {
        Files.writeString(tmp.resolve("secret.flac"), "x");

        assertNull(organiser.locate("..\\secret.flac"));
        assertNull(organiser.locate("C:\\..\\..\\secret.flac"));
    }

    // ---- sanitising ------------------------------------------------------------------------------

    @Test
    void sanitise_makesANameLegalOnEveryFilesystem() {
        assertEquals("AC_DC_ Back In Black", LibraryOrganiser.sanitise("AC/DC: Back In Black"));
        assertEquals("What_", LibraryOrganiser.sanitise(" What?  "));
        assertEquals("Trailing", LibraryOrganiser.sanitise("Trailing... "));
        assertEquals("hidden", LibraryOrganiser.sanitise(".hidden"));
        assertEquals("Unknown", LibraryOrganiser.sanitise(""));
        assertEquals("Unknown", LibraryOrganiser.sanitise(null));
        assertEquals("Unknown", LibraryOrganiser.sanitise(".."));
        assertEquals("_CON", LibraryOrganiser.sanitise("CON"));
        assertEquals("_com1.txt", LibraryOrganiser.sanitise("com1.txt"));
        assertEquals("a__b", LibraryOrganiser.sanitise("a\u0000\tb"));
    }

    @Test
    void sanitise_capsAComponentAt200BytesOfUtf8_onACharacterBoundary() {
        String longName = "é".repeat(150);   // 300 bytes
        String result = LibraryOrganiser.sanitise(longName);
        assertEquals(200, result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals("é".repeat(100), result);
    }

    @Test
    void sanitiseFileName_keepsTheExtension() {
        assertEquals("05 - Where Is My Mind_.flac", LibraryOrganiser.sanitiseFileName("05 - Where Is My Mind?.flac"));
        assertEquals("noext", LibraryOrganiser.sanitiseFileName("noext"));
    }

    // ---- filing ----------------------------------------------------------------------------------

    @Test
    void aSong_goesUnderItsArtistAndTitle_keepingTheDownloadedFileName() throws IOException {
        put(downloads.resolve("Doolittle"), "05 Pixies - Here Comes Your Man.flac");

        Path filed = organiser.file(song("Pixies\\Doolittle\\05 Pixies - Here Comes Your Man.flac",
                "Here Comes Your Man", "Pixies", "Someone Else"), NOW).block();

        assertEquals(root.resolve("Pixies/Here Comes Your Man/05 Pixies - Here Comes Your Man.flac"), filed);
        assertEquals("audio", Files.readString(filed));
        assertFalse(Files.exists(downloads.resolve("Doolittle")), "emptied peer folder is removed");
        assertTrue(Files.exists(downloads), "the downloads folder itself is never removed");
    }

    @Test
    void anAlbumTrack_goesUnderTheAlbumArtistAndAlbumTitle() throws IOException {
        put(downloads.resolve("Doolittle"), "01 - Debaser.flac");
        LibraryOrganiser.Job job = new LibraryOrganiser.Job(UUID.randomUUID(), DownloadType.ALBUM,
                "Pixies\\Doolittle\\01 - Debaser.flac", NOW.minusSeconds(5),
                "Debaser", List.of("Pixies"), "Doolittle", List.of("Pixies"));

        Path filed = organiser.file(job, NOW).block();

        assertEquals(root.resolve("Pixies/Doolittle/01 - Debaser.flac"), filed);
    }

    @Test
    void aPlaylistTrack_isFiledLikeASong_notIntoAPlaylistFolder() throws IOException {
        // Jellyfin would show Playlists/<name>/ as an album named after its first track.
        put(downloads.resolve("Freedom of Choice"), "02-devo-whip_it.mp3");
        LibraryOrganiser.Job job = new LibraryOrganiser.Job(UUID.randomUUID(), DownloadType.PLAYLIST,
                "x\\Freedom of Choice\\02-devo-whip_it.mp3", NOW.minusSeconds(5),
                "Whip It", List.of("Devo"), "Alt Nation 1989", List.of());

        Path filed = organiser.file(job, NOW).block();

        assertEquals(root.resolve("Devo/Whip It/02-devo-whip_it.mp3"), filed);
    }

    @Test
    void namesAreSanitisedAndMissingOnesFallBack() throws IOException {
        put(downloads.resolve("d"), "track.flac");

        Path filed = organiser.file(song("d\\track.flac", "Who? Me: Yes", "AC/DC"), NOW).block();
        assertEquals(root.resolve("AC_DC/Who_ Me_ Yes/track.flac"), filed);

        put(downloads.resolve("d"), "other.flac");
        Path unnamed = organiser.file(song("d\\other.flac", null), NOW).block();
        assertEquals(root.resolve("Unknown Artist/other/other.flac"), unnamed);
    }

    @Test
    void neverOverwrites_anExistingFileGetsANumberedSibling() throws IOException {
        put(root.resolve("Pixies/Debaser"), "01 - Debaser.flac");
        put(root.resolve("Pixies/Debaser"), "01 - Debaser (2).flac");
        put(downloads.resolve("Doolittle"), "01 - Debaser.flac");

        Path filed = organiser.file(song("Doolittle\\01 - Debaser.flac", "Debaser", "Pixies"), NOW).block();

        assertEquals(root.resolve("Pixies/Debaser/01 - Debaser (3).flac"), filed);
        assertEquals("audio", Files.readString(root.resolve("Pixies/Debaser/01 - Debaser.flac")),
                "the original is untouched");
    }

    @Test
    void aStagedFileAlreadyInTheFolder_isNeverOverwritten() throws IOException {
        // Something else is mid-copy into this folder; this song's staging name is its own.
        Path other = Files.writeString(Files.createDirectories(root.resolve("Pixies/Debaser"))
                .resolve("01 - Debaser.flac.partial"), "someone else's bytes");
        put(downloads.resolve("Doolittle"), "01 - Debaser.flac");

        Path filed = organiser.file(song("Doolittle\\01 - Debaser.flac", "Debaser", "Pixies"), NOW).block();

        assertEquals(root.resolve("Pixies/Debaser/01 - Debaser.flac"), filed);
        assertEquals("someone else's bytes", Files.readString(other));
        try (var files = Files.list(root.resolve("Pixies/Debaser"))) {
            assertEquals(2, files.count(), "no staging file of our own left behind");
        }
    }

    @Test
    void aFileNotThereYet_isLeftForTheNextPass_andNothingIsCreated() {
        assertNull(organiser.file(song("Doolittle\\01 - Debaser.flac", "Debaser", "Pixies"), NOW).block());
        assertFalse(Files.exists(root));
    }

    @Test
    void givesUpQuietly_afterTheGraceWindow_stillWithoutCreatingAnything() {
        LibraryOrganiser.Job old = new LibraryOrganiser.Job(UUID.randomUUID(), DownloadType.SONG,
                "Doolittle\\01 - Debaser.flac", NOW.minus(LibraryOrganiser.GIVE_UP_AFTER).minusSeconds(1),
                "Debaser", List.of("Pixies"), null, List.of());

        // Same observable outcome as "not yet": no path, no folders. The difference is one WARN line.
        assertNull(organiser.file(old, NOW).block());
        assertFalse(Files.exists(root));
    }

    @Test
    void aSymlinkInDownloads_isNeverFollowed() throws IOException {
        Path outside = Files.writeString(tmp.resolve("outside.flac"), "secret");
        Files.createDirectories(downloads.resolve("d"));
        Files.createSymbolicLink(downloads.resolve("d/link.flac"), outside);

        assertNull(organiser.file(song("d\\link.flac", "Link", "X"), NOW).block());
        assertTrue(Files.exists(outside));
    }

    // ---- playlist file ---------------------------------------------------------------------------

    private static LibraryOrganiser.Entry entry(Path file, String title, Integer seconds, String... artists) {
        return new LibraryOrganiser.Entry(file.toString(), title, List.of(artists), seconds);
    }

    @Test
    void m3u8_isExtendedM3u_withEntriesRelativeToThePlaylistsFolder_inTheOrderGiven() {
        Path playlists = root.resolve("Playlists");
        String body = LibraryOrganiser.m3u8("Alt Nation 1989", List.of(
                entry(root.resolve("Pixies/Doolittle/05 Pixies - Here Comes Your Man.flac"),
                        "Here Comes Your Man", 210, "Pixies"),
                entry(root.resolve("Devo/Whip It/02-devo-whip_it.mp3"), "Whip It", null, "Devo", "Someone"),
                entry(root.resolve("Unknown Artist/track/track.flac"), null, 90)), playlists);

        assertEquals("""
                #EXTM3U
                #PLAYLIST:Alt Nation 1989
                #EXTINF:210,Pixies - Here Comes Your Man
                ../Pixies/Doolittle/05 Pixies - Here Comes Your Man.flac
                #EXTINF:-1,Devo - Whip It
                ../Devo/Whip It/02-devo-whip_it.mp3
                #EXTINF:90,track
                ../Unknown Artist/track/track.flac
                """, body);
    }

    @Test
    void m3u8_keepsHeaderLinesOnOneLine() {
        String body = LibraryOrganiser.m3u8("Bad\nName", List.of(
                entry(root.resolve("A/B/c.flac"), "Ti\ntle", 1, "Ar\rtist")), root.resolve("Playlists"));

        assertTrue(body.contains("#PLAYLIST:Bad Name\n"));
        assertTrue(body.contains("#EXTINF:1,Ar tist - Ti tle\n"));
    }

    @Test
    void writePlaylist_writesUtf8WithoutBom_lfOnly_underASanitisedName_andRewritesInPlace() throws IOException {
        Path track = put(root.resolve("Pixies/Debaser"), "01 - Débaser.flac");
        List<LibraryOrganiser.Entry> entries = List.of(entry(track, "Débaser", 170, "Pixies"));

        Path written = organiser.writePlaylist("Mix: 1989?", entries).block();

        assertEquals(root.resolve("Playlists/Mix_ 1989_.m3u8"), written);
        byte[] bytes = Files.readAllBytes(written);
        assertNotEquals(0xEF, bytes[0] & 0xFF, "no BOM");
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(text.contains("\r"));
        assertTrue(text.contains("../Pixies/Debaser/01 - Débaser.flac\n"));
        assertTrue(text.startsWith("#EXTM3U\n#PLAYLIST:Mix: 1989?\n"), "the display name keeps its punctuation");

        // Second write of the same playlist replaces the file; no ' (2)', no leftover staging file.
        Path again = organiser.writePlaylist("Mix: 1989?", entries).block();
        assertEquals(written, again);
        assertEquals(1, Files.list(root.resolve("Playlists")).count());
    }

    @Test
    void writePlaylist_skipsEntriesOutsideTheLibrary_andFallsBackToAName() throws IOException {
        Path inside = put(root.resolve("A/B"), "c.flac");

        Path written = organiser.writePlaylist(null, List.of(
                entry(tmp.resolve("elsewhere.flac"), "x", 1, "y"),
                entry(inside, "c", 1, "A"))).block();

        assertEquals(root.resolve("Playlists/Playlist.m3u8"), written);
        String text = Files.readString(written);
        assertFalse(text.contains("elsewhere"));
        assertTrue(text.contains("../A/B/c.flac"));
    }

    // ---- failed leftovers ------------------------------------------------------------------------

    @Test
    void deletePartials_removesEveryTriedCandidatesPartialFile_andItsEmptyFolders() throws IOException {
        Path alice = put(incomplete.resolve("alice/music/alice"), "song.flac");
        Path bob = put(incomplete.resolve("bob/music/bob"), "song.flac");
        Path carol = put(incomplete.resolve("carol/music/carol"), "song.flac");
        // Tried alice, then failed over to bob and failed for good there; carol was never attempted.
        DownloadTask task = DownloadTaskFixtures.downloadPolling(
                DownloadTaskFixtures.candidates("alice", "bob", "carol"), 1, 0, "t1");

        organiser.deletePartials(task).block();

        assertFalse(Files.exists(alice));
        assertFalse(Files.exists(bob));
        assertTrue(Files.exists(carol), "an untried peer's file is not ours to touch");
        assertFalse(Files.exists(incomplete.resolve("alice")), "empty folder chain removed");
        assertTrue(Files.exists(incomplete), "the incomplete folder itself stays");
    }

    @Test
    void deletePartials_isANoOpWithoutAnIncompleteFolder_orWithNothingThere() throws IOException {
        LibraryOrganiser noIncomplete = new LibraryOrganiser(downloads.toString(), "", root.toString(), LOOP);
        Path alice = put(incomplete.resolve("alice/music/alice"), "song.flac");
        DownloadTask task = DownloadTaskFixtures.downloadPolling(DownloadTaskFixtures.candidates("alice"), 0, 0, "t1");

        noIncomplete.deletePartials(task).block();
        assertTrue(Files.exists(alice));

        Files.delete(alice);
        assertDoesNotThrow(() -> organiser.deletePartials(task).block());
    }

    @Test
    void partialPath_neverEscapesTheIncompleteFolder() {
        assertNull(organiser.partialPath("..", "music\\song.flac"));
        assertNull(organiser.partialPath("a\\b", "music\\song.flac"));
        // The share root and every '..' are dropped, so the path can only land under bob's folder.
        assertEquals(incomplete.resolve("bob/music/song.flac"),
                organiser.partialPath("bob", "@@root\\..\\music\\..\\song.flac"));
    }
}
