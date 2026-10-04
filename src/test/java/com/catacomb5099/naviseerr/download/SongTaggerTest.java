package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.download.SongTagger.Plan;
import com.catacomb5099.naviseerr.download.SongTagger.Tags;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.aiff.AiffTag;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.wav.WavTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tag rules on their own, then on real files. The files in src/test/resources/tagging/ are half a
 * second of tone made with ffmpeg, each tagged as a DIFFERENT edition of one album the way Soulseek files
 * arrive: seeded.mp3 (ID3v2.3, "Definitely Maybe (Remastered)", 2014, a MusicBrainz album id, ffmpeg's
 * TXXX:compilation and an ALBUMVERSION), seeded.flac ("Definitely Maybe [Deluxe]", DATE 1994-08-29 next
 * to RELEASEDATE 2014-05-19 and YEAR 2014, its own MusicBrainz id, TOTALTRACKS 44), seeded.m4a (©day
 * 2004, cpil, MusicBrainz ids), seeded.aiff (an ID3v2.3 chunk: "(Remastered)", 2014, TCMP), untagged.mp3 and
 * untagged.wav (no tag at all) and song.opus (a format the library cannot write).
 */
class SongTaggerTest {

    // Definitely Maybe on YouTube Music (MPREb_Hl8XJR59OrY): 11 tracks, 1994.
    private static final String ART = "https://yt3.googleusercontent.com/AQHkMsob37t4X5pBGKNbyfv7gQzEQmvexCkJ6pCsmPhz=w544-h544-l90-rj";

    @TempDir Path tmp;
    private final SongTagger tagger = new SongTagger();

    private static Tags definitelyMaybe(String title, int track) {
        return new Tags(title, List.of("Oasis"), "Definitely Maybe", List.of("Oasis"), 1994, track, 11, ART);
    }

    private static Tags noAlbum(String title, String... artists) {
        return new Tags(title, List.of(artists), null, List.of(), null, null, null, ART);
    }

    private Path copy(String fixture, String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/tagging/" + fixture)) {
            Path dir = Files.createDirectories(tmp.resolve(name + "-dir"));
            Path file = dir.resolve(name);
            Files.copy(in, file);
            return file;
        }
    }

    private static Tag read(Path file) throws Exception {
        return AudioFileIO.read(file.toFile()).getTag();
    }

    private static byte[] jpeg(Color color) throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) image.setRGB(x, y, color.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    // ---- the plan --------------------------------------------------------------------------------

    @Test
    void titleAndArtist_areOnlyFilled_neverOverwritten() {
        Plan kept = SongTagger.plan("Live Forever (Remastered)", "Oasis", true, noAlbum("Live Forever", "Oasis"));
        Plan filled = SongTagger.plan("", null, true, noAlbum("Live Forever", "Oasis", "Noel Gallagher"));

        assertEquals(Map.of(), kept.set(), "the file's own title and artist stay");
        assertEquals(Map.of(FieldKey.TITLE, "Live Forever", FieldKey.ARTIST, "Oasis / Noel Gallagher"), filled.set());
    }

    @Test
    void aTrustedAlbum_setsTheAlbumIdentity_andRemovesWhatWouldSplitIt() {
        Plan plan = SongTagger.plan("Supersonic", "Oasis", true, definitelyMaybe("Supersonic", 6));

        Map<FieldKey, String> expected = new LinkedHashMap<>();
        expected.put(FieldKey.ALBUM, "Definitely Maybe");
        expected.put(FieldKey.ALBUM_ARTIST, "Oasis");
        expected.put(FieldKey.YEAR, "1994");
        expected.put(FieldKey.TRACK, "6");
        expected.put(FieldKey.TRACK_TOTAL, "11");
        expected.put(FieldKey.DISC_NO, "1");
        expected.put(FieldKey.DISC_TOTAL, "1");
        assertEquals(expected, plan.set());
        assertEquals(SongTagger.SPLITS_AN_ALBUM, plan.delete());
        assertTrue(plan.album());
        assertTrue(plan.cover(), "the album's cover replaces the file's");
    }

    @Test
    void aTrustedAlbumWithoutAYear_removesTheDate_soEverySongOfItAgrees() {
        Tags tags = new Tags("Supersonic", List.of("Oasis"), "Definitely Maybe", List.of(), null, null, null, null);

        Plan plan = SongTagger.plan("Supersonic", "Oasis", false, tags);

        assertTrue(plan.delete().containsAll(List.of(FieldKey.YEAR, FieldKey.ALBUM_ARTIST)));
        assertFalse(plan.set().containsKey(FieldKey.TRACK), "an unknown track number leaves the file's");
    }

    @Test
    void withoutATrustedAlbum_noAlbumTagIsTouched_andACoverOnlyFillsAGap() {
        Plan withCover = SongTagger.plan("Lose Yourself", "Eminem", true, noAlbum("Lose Yourself", "Eminem"));
        Plan withoutCover = SongTagger.plan("Lose Yourself", "Eminem", false, noAlbum("Lose Yourself", "Eminem"));

        assertEquals(Map.of(), withCover.set());
        assertEquals(List.of(), withCover.delete());
        assertFalse(withCover.album());
        assertFalse(withCover.cover(), "the file's own picture stays");
        assertTrue(withoutCover.cover());
    }

    @Test
    void coverUrl_isYouTubeMusicAlbumArtAt1200px_neverAVideoFrame() {
        assertEquals("https://yt3.googleusercontent.com/AQHkMsob37t4X5pBGKNbyfv7gQzEQmvexCkJ6pCsmPhz=w1200-h1200-l90-rj",
                SongTagger.coverUrl(ART));
        assertEquals("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
                SongTagger.coverUrl("https://lh3.googleusercontent.com/abc"));
        assertNull(SongTagger.coverUrl("https://i.ytimg.com/vi/3aatEBIZHNU/hq720.jpg?sqp=-oay"));
        assertNull(SongTagger.coverUrl("https://i.ytimg.com/vi/6hzrDeceEKc/hqdefault.jpg"));
        assertNull(SongTagger.coverUrl("http://yt3.googleusercontent.com/abc=w544-h544"), "https only");
        assertNull(SongTagger.coverUrl(null));
    }

    // ---- real files ------------------------------------------------------------------------------

    /**
     * The fields Navidrome builds an album's id from ({@code musicbrainz_albumid|albumartistid, album,
     * albumversion, releasedate}) plus the compilation flag, read through the same aliases it reads
     * (its mappings.yaml): releasedate is TDRL, RELEASEDATE or YEAR, or ©day. A WAV's or AIFF's is its ID3 chunk.
     */
    private static Map<String, String> navidromeAlbumKey(Path file) throws Exception {
        Tag tag = switch (read(file)) {
            case WavTag wav -> wav.getID3Tag();
            case AiffTag aiff -> aiff.getID3Tag();
            case Tag other -> other;
        };
        Map<String, String> key = new LinkedHashMap<>();
        key.put("album", tag.getFirst(FieldKey.ALBUM));
        key.put("albumartist", tag.getFirst(FieldKey.ALBUM_ARTIST));
        key.put("musicbrainz_albumid", tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID));
        key.put("albumversion", raw(tag, "ALBUMVERSION") + raw(tag, "MUSICBRAINZ_ALBUMCOMMENT"));
        key.put("releasedate", switch (tag) {
            case AbstractID3v2Tag id3 -> id3.getFirst("TDRL") + raw(tag, "RELEASEDATE") + raw(tag, "YEAR");
            case FlacTag flac -> raw(tag, "RELEASEDATE").isEmpty() ? raw(tag, "YEAR") : raw(tag, "RELEASEDATE");
            case Mp4Tag mp4 -> mp4.getFirst(FieldKey.YEAR);
            default -> throw new IllegalStateException(tag.getClass().getName());
        });
        key.put("compilation", tag.getFirst(FieldKey.IS_COMPILATION) + raw(tag, "COMPILATION"));
        return key;
    }

    /** A free-form key: ID3 TXXX by description, a Vorbis comment, an iTunes ---- atom. */
    private static String raw(Tag tag, String name) {
        return switch (tag) {
            case AbstractID3v2Tag id3 -> id3.getFields("TXXX").stream()
                    .map(f -> ((AbstractID3v2Frame) f).getBody())
                    .filter(b -> b instanceof FrameBodyTXXX t && t.getDescription().equalsIgnoreCase(name))
                    .map(b -> ((FrameBodyTXXX) b).getFirstTextValue())
                    .findFirst().orElse("");
            case FlacTag flac -> flac.getFirst(name);
            case Mp4Tag mp4 -> mp4.getFirst("----:com.apple.iTunes:" + name);
            default -> "";
        };
    }

    @Test
    void anMp3AFlacAnM4aAWavAndAnAiff_taggedAsDifferentEditions_endUpWithOneNavidromeAlbum() throws Exception {
        Path mp3 = copy("seeded.mp3", "03 Live Forever.mp3");
        Path flac = copy("seeded.flac", "06 Supersonic.flac");
        Path m4a = copy("seeded.m4a", "08 Cigarettes & Alcohol.m4a");
        Path wav = copy("untagged.wav", "09 Married With Children.wav");
        Path aiff = copy("seeded.aiff", "10 Slide Away.aiff");
        List<Path> files = List.of(mp3, flac, m4a, wav, aiff);
        assertEquals(5, files.stream().map(f -> {
            try { return navidromeAlbumKey(f); } catch (Exception e) { throw new AssertionError(e); }
        }).distinct().count(), "the fixtures start as five different albums");
        byte[] cover = jpeg(Color.RED);

        for (int run = 0; run < 2; run++) { // twice: a filing retried after a failed move tags again
            tagger.tag(mp3, definitelyMaybe("Live Forever", 3), cover);
            tagger.tag(flac, definitelyMaybe("Supersonic", 6), cover);
            tagger.tag(m4a, definitelyMaybe("Cigarettes & Alcohol", 8), cover);
            tagger.tag(wav, definitelyMaybe("Married With Children", 9), cover);
            tagger.tag(aiff, definitelyMaybe("Slide Away", 10), cover);
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("album", "Definitely Maybe");
        expected.put("albumartist", "Oasis");
        expected.put("musicbrainz_albumid", "");
        expected.put("albumversion", "");
        expected.put("releasedate", "1994");
        expected.put("compilation", "");
        assertEquals(expected, navidromeAlbumKey(mp3), "mp3");
        assertEquals(expected, navidromeAlbumKey(flac), "flac");
        assertEquals(expected, navidromeAlbumKey(m4a), "m4a");
        assertEquals(expected, navidromeAlbumKey(wav), "wav");
        assertEquals(expected, navidromeAlbumKey(aiff), "aiff: its ID3v2.3 chunk converted, as an MP3's");

        Tag id3 = read(mp3);
        assertInstanceOf(ID3v24Tag.class, id3, "converted from v2.3: TDRL exists only in v2.4");
        assertEquals("Live Forever", id3.getFirst(FieldKey.TITLE));
        assertEquals("Britpop", id3.getFirst(FieldKey.GENRE), "what YouTube does not know is kept");
        assertEquals(List.of("3", "11", "1", "1"), List.of(id3.getFirst(FieldKey.TRACK),
                id3.getFirst(FieldKey.TRACK_TOTAL), id3.getFirst(FieldKey.DISC_NO), id3.getFirst(FieldKey.DISC_TOTAL)));
        Tag vorbis = read(flac);
        assertEquals("", raw(vorbis, "TOTALTRACKS"), "the stale Vorbis total is gone");
        assertEquals("11", vorbis.getFirst(FieldKey.TRACK_TOTAL));
        assertEquals("6", vorbis.getFirst(FieldKey.TRACK));
        for (Path file : files) {
            assertArrayEquals(cover, read(file).getFirstArtwork().getBinaryData(), file.getFileName().toString());
            try (Stream<Path> siblings = Files.list(file.getParent())) {
                assertEquals(1, siblings.count(), "no temporary file left beside " + file.getFileName());
            }
        }
    }

    @Test
    void aSongRippedFromACompilation_losesFoobarsAlbumArtistToo_soItJoinsTheArtistsAlbum() throws Exception {
        // foobar2000 writes the album artist as "ALBUM ARTIST" (TXXX in an MP3 ffmpeg made from its FLAC),
        // and Navidrome reads it beside ALBUMARTIST/TPE2: left behind, the song's album artist would be
        // "Oasis • Various Artists", an album of its own. A spaced MusicBrainz id alone would be the album.
        Path flac = copy("seeded.flac", "06 Supersonic.flac");
        Path mp3 = copy("seeded.mp3", "03 Live Forever.mp3");
        AudioFile flacFile = AudioFileIO.read(flac.toFile());
        FlacTag vorbis = (FlacTag) flacFile.getTag();
        vorbis.setField("ALBUM ARTIST", "Various Artists");
        vorbis.setField("ALBUM_ARTIST", "Various Artists");
        vorbis.setField("MUSICBRAINZ ALBUM ID", "44444444-4444-4444-4444-444444444444");
        vorbis.setField("ORIGINALDATE", "2014"); // Picard's spellings of the original date
        vorbis.setField("ORIGINALYEAR", "2014");
        flacFile.commit();
        AudioFile mp3File = AudioFileIO.read(mp3.toFile());
        AbstractID3v2Tag id3 = (AbstractID3v2Tag) mp3File.getTag();
        AbstractID3v2Frame txxx = id3.createFrame("TXXX");
        ((FrameBodyTXXX) txxx.getBody()).setDescription("ALBUM ARTIST");
        ((FrameBodyTXXX) txxx.getBody()).setText("Various Artists");
        id3.addField(txxx);
        mp3File.commit();

        tagger.tag(flac, definitelyMaybe("Supersonic", 6), SongTagger.NO_COVER);
        tagger.tag(mp3, definitelyMaybe("Live Forever", 3), SongTagger.NO_COVER);

        Tag flacTag = read(flac);
        assertEquals(List.of("", "", "", "", ""), List.of(raw(flacTag, "ALBUM ARTIST"), raw(flacTag, "ALBUM_ARTIST"),
                raw(flacTag, "MUSICBRAINZ ALBUM ID"), raw(flacTag, "ORIGINALDATE"), raw(flacTag, "ORIGINALYEAR")));
        assertEquals("Oasis", flacTag.getFirst(FieldKey.ALBUM_ARTIST));
        assertEquals("", raw(read(mp3), "ALBUM ARTIST"));
        assertEquals("Oasis", read(mp3).getFirst(FieldKey.ALBUM_ARTIST));
    }

    @Test
    void underATrustedAlbum_YouTubesAlbumAndCoverReplaceTheFiles_butTheTitleIsKept() throws Exception {
        Path flac = copy("seeded.flac", "06 Supersonic.flac");
        tagger.tag(flac, noAlbum("ignored", "ignored"), jpeg(Color.BLUE)); // the file now has a cover of its own

        tagger.tag(flac, definitelyMaybe("Supersonic (Remastered)", 6), jpeg(Color.RED));

        Tag tag = read(flac);
        assertEquals("Definitely Maybe", tag.getFirst(FieldKey.ALBUM), "was 'Definitely Maybe [Deluxe]'");
        assertEquals("Supersonic", tag.getFirst(FieldKey.TITLE));
        assertArrayEquals(jpeg(Color.RED), tag.getFirstArtwork().getBinaryData());
    }

    @Test
    void withoutATrustedAlbum_theFilesAlbumTagsAndCoverAreUntouched_andOnlyGapsAreFilled() throws Exception {
        Path flac = copy("seeded.flac", "Supersonic.flac");
        Path bare = copy("untagged.mp3", "Wonderwall.mp3");
        Map<String, String> before = navidromeAlbumKey(flac);

        tagger.tag(flac, noAlbum("Supersonic", "Oasis"), jpeg(Color.RED));
        tagger.tag(flac, noAlbum("Supersonic", "Oasis"), jpeg(Color.BLUE));
        tagger.tag(bare, noAlbum("Wonderwall", "Oasis"), jpeg(Color.RED));

        assertEquals(before, navidromeAlbumKey(flac));
        assertEquals("44", raw(read(flac), "TOTALTRACKS"));
        assertArrayEquals(jpeg(Color.RED), read(flac).getFirstArtwork().getBinaryData(), "filled once, then kept");
        Tag filled = read(bare);
        assertEquals("Wonderwall", filled.getFirst(FieldKey.TITLE));
        assertEquals("Oasis", filled.getFirst(FieldKey.ARTIST));
        assertEquals("", filled.getFirst(FieldKey.ALBUM));
    }

    @Test
    void anOpusFile_aMislabelledFile_andABrokenFile_areLeftExactlyAsTheyWere() throws Exception {
        Path opus = copy("song.opus", "Slide Away.opus");
        Path flacNamedMp3 = copy("seeded.flac", "Supersonic.mp3");
        Path broken = Files.writeString(Files.createDirectories(tmp.resolve("broken")).resolve("Live Forever.m4a"),
                "not audio at all");
        List<Path> files = List.of(opus, flacNamedMp3, broken);
        List<byte[]> before = files.stream().map(SongTaggerTest::bytes).toList();

        for (Path file : files) {
            tagger.tag(file, definitelyMaybe("Supersonic", 6), jpeg(Color.RED)); // must not throw
        }

        for (int i = 0; i < files.size(); i++) {
            assertTrue(Arrays.equals(before.get(i), bytes(files.get(i))), files.get(i).getFileName() + " changed");
            try (Stream<Path> siblings = Files.list(files.get(i).getParent())) {
                assertEquals(1, siblings.count(), "nothing left beside " + files.get(i).getFileName());
            }
        }
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
