package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.T0;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The album folder picker on real slskd answers (04-10-2026, trimmed to the folders each test needs, in
 * src/test/resources/slskd/) against YouTube Music's own track lists for the same albums.
 */
class AlbumFolderPickerTest {

    private static final Predicate<String> NOBODY_STALLING = sharer -> false;

    private AlbumFolderPicker picker;

    @BeforeEach
    void setUp() {
        SlskdSearchResultProcessor processor =
                new SlskdSearchResultProcessor(mock(SlskdService.class), new TrackMatchingService());
        ReflectionTestUtils.setField(processor, "minBitRate", 320);
        ReflectionTestUtils.setField(processor, "maxSharerQueue", 50);
        picker = new AlbumFolderPicker(new TrackMatchingService(), processor);
    }

    // ---- Definitely Maybe (Oasis, 11 tracks): /v1/albums/MPREb_Hl8XJR59OrY -------------------------

    private static List<DownloadTask> definitelyMaybe() {
        return album("Oasis", "Rock 'n' Roll Star", 324, "Shakermaker", 309, "Live Forever", 277,
                "Up in the Sky", 269, "Columbia", 377, "Supersonic", 284, "Bring It on Down", 258,
                "Cigarettes & Alcohol", 290, "Digsy's Dinner", 153, "Slide Away", 393,
                "Married with Children", 193);
    }

    @Test
    void definitelyMaybe_everyWholeFolderIsFound_bestFirst_thenThePartOnes() {
        List<AlbumFolderPicker.Folder> whole = picker.folders(responses("album-definitely-maybe.json"),
                definitelyMaybe(), "Definitely Maybe", List.of("Oasis"), NOBODY_STALLING);

        // Free slot, then queue, then extra files, then speed; niccage has no free slot and 52 queued
        // (past max-sharer-queue), so it goes last of the whole ones. raphyduck's folder lacks Live
        // Forever: 10 of 11, after every whole folder.
        assertEquals(List.of("noochieplexMatt", "ComputerYeti", "fennyfom", "TheDrewCareyShow", "niccage", "raphyduck"),
                whole.stream().map(f -> f.peer().getUsername()).toList());
        assertEquals(0, whole.getFirst().extras());
    }

    @Test
    void definitelyMaybe_withNoWholeFolder_raphyducksTenOfElevenSupplyThoseTen() {
        List<DownloadTask> songs = definitelyMaybe();
        Predicate<String> wholeSharersStalling =
                List.of("noochieplexMatt", "ComputerYeti", "fennyfom", "TheDrewCareyShow", "niccage")::contains;

        List<AlbumFolderPicker.Folder> parts = picker.folders(responses("album-definitely-maybe.json"), songs,
                "Definitely Maybe", List.of("Oasis"), wholeSharersStalling);

        // Its bonus disc ("Definitely Maybe (Bonus Disc)") is another folder holding none of the 11, and its
        // one Live Forever sits in a compilation folder of one file: neither is a part.
        assertEquals(1, parts.size());
        AlbumFolderPicker.Folder raphyduck = parts.getFirst();
        assertEquals("Music\\Oasis\\Definitely Maybe", raphyduck.path());
        assertEquals(10, raphyduck.files().size());
        assertFalse(raphyduck.files().containsKey(songs.get(2).taskId()), "Live Forever searches on its own");
        assertEquals("1-06 - Supersonic.mp3", leaf(raphyduck.files().get(songs.get(5).taskId())));
        assertEquals(10, AlbumFolderPicker.candidates(parts, 3).size());
    }

    @Test
    void eachSongGetsItsOwnFile_matchedByTitle_notByPosition() {
        List<DownloadTask> songs = definitelyMaybe();
        AlbumFolderPicker.Folder best = picker.folders(responses("album-definitely-maybe.json"), songs,
                "Definitely Maybe", List.of("Oasis"), NOBODY_STALLING).getFirst();

        for (DownloadTask song : songs) {
            String leaf = leaf(best.files().get(song.taskId()));
            assertTrue(leaf.startsWith(String.format("Oasis - Definitely Maybe - %02d - ", song.trackNumber())),
                    song.trackTitle() + " got " + leaf);
        }
    }

    @Test
    void aDeluxeFolder_givesEachSongTheAlbumTake_neverTheDemoLiveOrAcousticOneBesideIt() {
        List<DownloadTask> songs = definitelyMaybe();
        AlbumFolderPicker.Folder deluxe = picker.folders(responses("album-definitely-maybe.json"), songs,
                "Definitely Maybe", List.of("Oasis"), NOBODY_STALLING).stream()
                .filter(f -> f.peer().getUsername().equals("ComputerYeti")).findFirst().orElseThrow();

        // 44 files: disc 1 (101-111) is the album; 201-317 are demos, live takes, B-sides.
        for (DownloadTask song : songs) {
            assertEquals(String.format("1%02d", song.trackNumber()), leaf(deluxe.files().get(song.taskId())).substring(0, 3),
                    song.trackTitle());
        }
        assertEquals(33, deluxe.extras(), "the other 33 files are left alone");
    }

    @Test
    void discSubfoldersOfOneAlbumFolder_areOneFolder() {
        AlbumFolderPicker.Folder split = picker.folders(responses("album-definitely-maybe.json"),
                definitelyMaybe(), "Definitely Maybe", List.of("Oasis"), NOBODY_STALLING).stream()
                .filter(f -> f.peer().getUsername().equals("TheDrewCareyShow")).findFirst().orElseThrow();

        // CD 01 holds the album, CD 02 three B-sides; the share alias (@@nfwmp) is not part of the path.
        assertEquals("Music\\Oasis\\Definitely Maybe (1994)", split.path());
        assertTrue(split.files().values().stream().allMatch(f -> f.getFilename().contains("\\CD 01\\")));
        assertEquals(3, split.extras());
    }

    @Test
    void aStallingSharer_isLeftOut() {
        List<AlbumFolderPicker.Folder> whole = picker.folders(responses("album-definitely-maybe.json"),
                definitelyMaybe(), "Definitely Maybe", List.of("Oasis"), "noochieplexMatt"::equals);

        assertEquals("ComputerYeti", whole.getFirst().peer().getUsername());
    }

    @Test
    void eachSongsCandidates_areTheBestFolder_thenTheSameTrackFromTheNextSharers() {
        List<DownloadTask> songs = definitelyMaybe();
        List<AlbumFolderPicker.Folder> whole = picker.folders(responses("album-definitely-maybe.json"),
                songs, "Definitely Maybe", List.of("Oasis"), NOBODY_STALLING);

        Map<UUID, List<DownloadCandidate>> picks = AlbumFolderPicker.candidates(whole, 3);

        assertEquals(songs.size(), picks.size());
        List<DownloadCandidate> supersonic = picks.get(songs.get(5).taskId());
        assertEquals(List.of("noochieplexMatt", "ComputerYeti", "fennyfom"),
                supersonic.stream().map(DownloadCandidate::username).toList());
        assertTrue(supersonic.stream().allMatch(c -> c.filename().contains("Supersonic")));
        assertTrue(supersonic.stream().allMatch(c -> DownloadCandidate.ALBUM_FOLDER.equals(c.source())
                && "EXACT".equals(c.grade())));
    }

    // ---- Discovery (Daft Punk, 14 tracks): /v1/albums/MPREb_7ltM34kr0mH ----------------------------

    private static List<DownloadTask> discovery() {
        return album("Daft Punk", "One More Time", 321, "Aerodynamic", 213,
                "Digital Love", 302, "Harder, Better, Faster, Stronger", 227, "Crescendolls", 212,
                "Nightvision", 105, "Superheroes", 238, "High Life", 202, "Something About Us", 233,
                "Voyager", 228, "Veridis Quo", 346, "Short Circuit", 207, "Face to Face", 241, "Too Long", 601);
    }

    @Test
    void discovery_onlyAFolderThatNamesTheArtistIsTheAlbum() {
        List<DownloadTask> songs = discovery();

        List<AlbumFolderPicker.Folder> whole = picker.folders(responses("album-discovery.json"), songs,
                "Discovery", List.of("Daft Punk"), NOBODY_STALLING);

        // Shockwave__42's "Discovery (2001)" has the right files but names nobody: from the folder alone it
        // could be anyone's Discovery. Sbonzo's is a cover album with the same track list (another artist,
        // 128 kbps, other lengths). soneo_app has 11 of the 14: a part, after the whole one.
        assertEquals(List.of("GraMoore", "soneo_app"), whole.stream().map(f -> f.peer().getUsername()).toList());
    }

    @Test
    void discovery_withNoWholeFolder_elevenOfFourteenIsAPart_sixIsTooFew() {
        List<DownloadTask> songs = discovery();

        List<AlbumFolderPicker.Folder> parts = picker.folders(responses("album-discovery.json"), songs,
                "Discovery", List.of("Daft Punk"), "GraMoore"::equals);

        // soneo_app's folder lacks One More Time, Digital Love and Something About Us; bugliker's has
        // only six (One More Time to Something About Us, gaps included): under half of 14.
        assertEquals(List.of("soneo_app"), parts.stream().map(f -> f.peer().getUsername()).toList());
        assertEquals(List.of("Aerodynamic", "Harder, Better, Faster, Stronger", "Crescendolls", "Nightvision",
                        "Superheroes", "High Life", "Voyager", "Veridis Quo", "Short Circuit", "Face to Face", "Too Long"),
                songs.stream().filter(s -> parts.getFirst().files().containsKey(s.taskId()))
                        .map(DownloadTask::trackTitle).toList());
    }

    // ---- Laughing Stock (Talk Talk, 6 tracks): /v1/albums/MPREb_10MwLM3Hu5L -------------------------

    @Test
    void laughingStock_aFolderWithAnotherLengthOrAnEditIsNotTheAlbum_andAnOuttakeBesideItIsNotTaken() {
        List<DownloadTask> songs = album("Talk Talk", "Myrrhman", 334, "Ascension Day", 361,
                "After The Flood", 567, "Taphead", 422, "New Grass", 587, "Runeii", 299);

        List<AlbumFolderPicker.Folder> whole = picker.folders(responses("album-laughing-stock.json"), songs,
                "Laughing Stock", List.of("Talk Talk"), NOBODY_STALLING);

        // moburma's Taphead runs 37 s long, so it has 5 of 6: a part, after the whole ones; llafnwod's is
        // an interview tape with the songs cut down; filfil's folder never names Talk Talk. Baron53 (free,
        // nobody queued) beats Nilkse (11 queued).
        assertEquals(List.of("Baron53", "Nilkse", "moburma"), whole.stream().map(f -> f.peer().getUsername()).toList());
        AlbumFolderPicker.Folder best = whole.getFirst();
        assertEquals("1-03 After The Flood.mp3", leaf(best.files().get(songs.get(2).taskId())));
        assertEquals(4, best.extras(), "the bonus disc, its outtake included, is left alone");
    }

    // ---- rules on small hand-made folders ----------------------------------------------------------

    @Test
    void aFolderWhoseOnlyCopyOfASongIsAnotherTake_isNotWhole() {
        List<DownloadTask> songs = album("Oasis", "Up in the Sky", 269, "Digsy's Dinner", 153);

        assertTrue(picker.folders(List.of(peer("a", "Oasis\\Definitely Maybe\\",
                        file("01 - Up In The Sky (Sawmills Outtake).flac", 270), file("02 - Digsy's Dinner.flac", 153))),
                songs, "Definitely Maybe", List.of("Oasis"), NOBODY_STALLING).isEmpty());
        assertEquals(1, picker.folders(List.of(peer("a", "Oasis\\Definitely Maybe\\",
                        file("01 - Up In The Sky.flac", 270), file("02 - Digsy's Dinner (Monnow Valley Version).flac", 153),
                        file("03 - Digsy's Dinner (Album Version).flac", 153))),
                songs, "Definitely Maybe", List.of("Oasis"), NOBODY_STALLING).size(),
                "'Album Version' is the plain take");
        // When YouTube's own title names the take, that take is the one asked for.
        List<DownloadTask> outtakes = album("Oasis", "Up In The Sky (Sawmills Outtake)", 273, "Digsy's Dinner", 153);
        assertEquals(1, picker.folders(List.of(peer("a", "Oasis\\Definitely Maybe\\",
                        file("01 - Up In The Sky (Sawmills Outtake).flac", 273), file("02 - Digsy's Dinner.flac", 153))),
                outtakes, "Definitely Maybe (30th Anniversary Deluxe Edition)", List.of("Oasis"), NOBODY_STALLING).size());
    }

    @Test
    void aPart_isAtLeastHalfTheSongsStillWaiting_andAtLeastTwo() {
        List<DownloadTask> five = album("Oasis", "Rock 'n' Roll Star", 324, "Shakermaker", 309, "Live Forever", 277,
                "Up in the Sky", 269, "Columbia", 377);
        SearchResponseItem three = peer("three", "Oasis\\Definitely Maybe\\", file("01 - Rock 'n' Roll Star.flac", 323),
                file("02 - Shakermaker.flac", 308), file("03 - Live Forever.flac", 276));
        SearchResponseItem two = peer("two", "Oasis\\Definitely Maybe\\", file("04 - Up in the Sky.flac", 268),
                file("05 - Columbia.flac", 377));

        assertEquals(List.of("three"), picker.folders(List.of(three, two), five, "Definitely Maybe", List.of("Oasis"),
                NOBODY_STALLING).stream().map(f -> f.peer().getUsername()).toList(), "3 of 5 is a part, 2 is not");
        // With two songs left, a part would be one song: only a whole folder will do.
        SearchResponseItem one = peer("one", "Oasis\\Definitely Maybe\\", file("04 - Up in the Sky.flac", 268));
        assertTrue(picker.folders(List.of(one), five.subList(3, 5), "Definitely Maybe", List.of("Oasis"),
                NOBODY_STALLING).isEmpty());
        assertEquals(1, picker.folders(List.of(two), five.subList(3, 5), "Definitely Maybe", List.of("Oasis"),
                NOBODY_STALLING).size());
    }

    @Test
    void aPartsSong_takesItsBackupsFromTheNextSharersThatHaveIt() {
        List<DownloadTask> five = album("Oasis", "Rock 'n' Roll Star", 324, "Shakermaker", 309, "Live Forever", 277,
                "Up in the Sky", 269, "Columbia", 377);
        SearchFile first = file("01 - Rock 'n' Roll Star.flac", 323);
        SearchFile second = file("02 - Shakermaker.flac", 308);
        SearchFile third = file("03 - Live Forever.flac", 276);
        SearchFile fourth = file("04 - Up in the Sky.flac", 268);
        String folder = "Oasis\\Definitely Maybe\\";
        List<AlbumFolderPicker.Folder> ranked = picker.folders(List.of(peer("a", folder, first, second, third, fourth),
                        peer("b", folder, first, second, third), peer("c", folder, first, second, third),
                        peer("d", folder, second, third, fourth)),
                five, "Definitely Maybe", List.of("Oasis"), NOBODY_STALLING);

        Map<UUID, List<DownloadCandidate>> picks = AlbumFolderPicker.candidates(ranked, 3);

        // b and c lack Up in the Sky; d, ranked after them, has it.
        assertEquals(List.of("a", "d"), picks.get(five.get(3).taskId()).stream().map(DownloadCandidate::username).toList());
        assertEquals(List.of("a", "b", "c"), picks.get(five.get(1).taskId()).stream().map(DownloadCandidate::username).toList());
    }

    @Test
    void aCompilation_isTheAlbumWhenItsFilesCarryEachSongsOwnArtist() {
        List<DownloadTask> songs = List.of(
                song("Don't You (Forget About Me)", "Simple Minds", 1, 261),
                song("Drive", "The Cars", 2, 235),
                song("Africa", "Toto", 3, 295));
        SearchResponseItem named = peer("a", "VA - Driving (2008)\\",
                file("01 - Simple Minds - Don't You (Forget About Me).flac", 262),
                file("02 - The Cars - Drive.flac", 235), file("03 - Toto - Africa.flac", 296));
        SearchResponseItem nameless = peer("b", "Driving\\",
                file("01 - Don't You (Forget About Me).flac", 262), file("02 - Drive.flac", 235),
                file("03 - Africa.flac", 296));

        List<AlbumFolderPicker.Folder> whole = picker.folders(List.of(named, nameless), songs, "Driving",
                List.of("Various Artists"), NOBODY_STALLING);

        assertEquals(List.of("a"), whole.stream().map(f -> f.peer().getUsername()).toList());
    }

    @Test
    void lengths_trackNumbersAndTitles() {
        assertEquals(Optional.of(2), AlbumFolderPicker.lengthGap(324, Optional.of(322), false));
        assertEquals(Optional.empty(), AlbumFolderPicker.lengthGap(422, Optional.of(459), false), "37 s off");
        assertEquals(Optional.of(10), AlbumFolderPicker.lengthGap(300, Optional.of(310), false), "max(10 s, 3%)");
        assertEquals(Optional.of(700), AlbumFolderPicker.lengthGap(301, Optional.of(1001), true),
                "the last track may hide another one");
        assertEquals(Optional.empty(), AlbumFolderPicker.lengthGap(null, Optional.of(21), false), "a 21 s stub");
        assertEquals(Optional.of(Integer.MAX_VALUE), AlbumFolderPicker.lengthGap(300, Optional.empty(), false),
                "no length is no evidence, and ranks last");

        assertEquals(4, AlbumFolderPicker.trackNumber("04 - Up In The Sky.flac"));
        assertEquals(4, AlbumFolderPicker.trackNumber("1-04 Up In The Sky.flac"));
        assertEquals(4, AlbumFolderPicker.trackNumber("104 - Up in the Sky.flac"));
        assertNull(AlbumFolderPicker.trackNumber("Oasis - Up in the Sky.flac"));

        assertTrue(AlbumFolderPicker.sameTitle("Rock 'n' Roll Star", "Oasis - Definitely Maybe - 01 - Rock ’n’ Roll Star.flac"));
        assertTrue(AlbumFolderPicker.sameTitle("Rock 'n' Roll Star", "1. Rock ’n’ Roll Star.flac"));
        assertFalse(AlbumFolderPicker.sameTitle("Up in the Sky", "209 - Up in the Sky (acoustic).flac"));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** The album's songs as admission writes them: "Title - Artist", the row's title, number and length. */
    private static List<DownloadTask> album(String artist, Object... titlesAndSeconds) {
        List<DownloadTask> songs = new ArrayList<>();
        for (int i = 0; i < titlesAndSeconds.length; i += 2) {
            songs.add(song((String) titlesAndSeconds[i], artist, i / 2 + 1, (Integer) titlesAndSeconds[i + 1]));
        }
        return songs;
    }

    private static DownloadTask song(String title, String artist, int number, int seconds) {
        return DownloadTask.initial(UUID.randomUUID(), "yt-" + number, title + " - " + artist, T0).toBuilder()
                .trackTitle(title).trackNumber(number).durationSeconds(seconds).build();
    }

    private static SearchFile file(String name, int seconds) {
        return new SearchFile(name, 30_000_000L, 1L, false, "", Optional.empty(), Optional.of(seconds));
    }

    private static SearchResponseItem peer(String username, String folder, SearchFile... files) {
        List<SearchFile> inFolder = java.util.Arrays.stream(files)
                .map(f -> new SearchFile(folder + f.getFilename(), f.getSize(), f.getCode(), false, "",
                        Optional.empty(), f.getLength()))
                .toList();
        return new SearchResponseItem(inFolder.size(), inFolder, true, 0, List.of(), 0, 1, 1_000_000, username);
    }

    private static String leaf(SearchFile file) {
        return LibraryOrganiser.baseName(file.getFilename());
    }

    /** Decoded by the production client, so a field slskd names differently than we do cannot hide. */
    private static List<SearchResponseItem> responses(String resource) {
        String body;
        try (InputStream in = AlbumFolderPickerTest.class.getResourceAsStream("/slskd/" + resource)) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build()))
                .build();
        return new SlskdService(webClient).getSearchWithResponses("fixture").block().getResponses();
    }
}
