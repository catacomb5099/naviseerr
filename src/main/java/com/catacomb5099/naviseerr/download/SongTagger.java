package com.catacomb5099.naviseerr.download;

import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.wav.WavOptions;
import org.jaudiotagger.audio.wav.WavSaveOptions;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.KeyNotFoundException;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.reference.ID3V2Version;
import org.jaudiotagger.tag.reference.PictureTypes;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes what YouTube Music knows about a finished song into its file's tags, so Navidrome and Jellyfin
 * show it on the right album (docs/decisions/youtube-album-tags-04-10-2026.md). {@link LibraryOrganiser}
 * calls it on the file still in slskd's downloads folder, just before moving it into the library, so no
 * scanner ever reads a half-written file.
 *
 * <ul>
 *   <li><b>With a trusted album (P1)</b> (an album download's own, or a song's from {@link SongAlbumResolver}),
 *   the album follows YouTube Music: album, album artist, year in every release-date field the format
 *   has, track, track total, disc 1 of 1 and the album's cover are written over what the file had, and
 *   the tags that split one album in two in Navidrome or Jellyfin are removed ({@link #SPLITS_AN_ALBUM}).</li>
 *   <li><b>Everything else only fills a gap (P2)</b>: the file's own title and artist (and genre, lyrics,
 *   composer...) are kept; without a trusted album the album tags are left as they are.</li>
 * </ul>
 *
 * <p>Never fails a song (P10): a file the tag library cannot read or write (Opus, APE, WavPack, a broken or
 * mislabelled file) is logged and filed as it is.
 */
@Slf4j
@Component
public class SongTagger {

    /**
     * What one song's tags are written from, as {@code DownloadTaskRepository.tasksToOrganise} reads it.
     * {@code album} is null when the song has no trusted album; {@code coverUrl} is then the song's own
     * picture, used only to fill a file that has none.
     */
    public record Tags(String title, List<String> artists, String album, List<String> albumArtists,
                       Integer year, Integer track, Integer trackTotal, String coverUrl) {}

    /**
     * What tagging one file changes. {@code album}: the album identity is rewritten, which also means
     * format-specific leftovers and the release date ({@link #clearRaw}, {@link #releaseDate}).
     * {@code cover}: the cover is written (replaced under a trusted album, filled otherwise).
     */
    record Plan(Map<FieldKey, String> set, List<FieldKey> delete, boolean album, boolean cover) {}

    /** No cover to embed: the album has none on YouTube Music, it is not album art, or it did not load. */
    static final byte[] NO_COVER = new byte[0];

    /**
     * Tags that tie a file to one particular release, and would split it from the rest of a YouTube Music
     * album: MusicBrainz release-level ids (Navidrome takes the album id alone as the album; Jellyfin
     * takes a majority vote of them), compilation flag, edition and disc subtitle, original date, the
     * sort and plural album-artist forms, label and catalogue. Track-level MusicBrainz ids stay.
     */
    static final List<FieldKey> SPLITS_AN_ALBUM = List.of(
            FieldKey.MUSICBRAINZ_RELEASEID, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID,
            FieldKey.MUSICBRAINZ_RELEASEARTISTID, FieldKey.MUSICBRAINZ_RELEASE_TYPE,
            FieldKey.MUSICBRAINZ_RELEASE_STATUS, FieldKey.MUSICBRAINZ_RELEASE_COUNTRY,
            FieldKey.MUSICBRAINZ_DISC_ID, FieldKey.MUSICBRAINZ_RELEASE_TRACK_ID,
            FieldKey.IS_COMPILATION, FieldKey.DISC_SUBTITLE,
            FieldKey.ALBUM_ARTIST_SORT, FieldKey.ALBUM_SORT, FieldKey.ALBUM_ARTISTS,
            FieldKey.ORIGINAL_YEAR, FieldKey.ORIGINALRELEASEDATE,
            FieldKey.CATALOG_NO, FieldKey.BARCODE, FieldKey.RECORD_LABEL);

    /**
     * Free-form keys other taggers write that the generic fields above miss, and that Navidrome reads as
     * the album's version, compilation flag or release date (ID3 TXXX, Vorbis comment, iTunes ----).
     * ffmpeg's TXXX:compilation and a Vorbis YEAR next to DATE are the common ones.
     */
    private static final Set<String> RAW_SPLITS_AN_ALBUM = Set.of("ALBUMVERSION", "MUSICBRAINZ_ALBUMCOMMENT",
            "MUSICBRAINZ ALBUM COMMENT", "RELEASETYPE", "MUSICBRAINZ ALBUM TYPE", "COMPILATION", "TCMP",
            "RELEASEDATE", "YEAR", "TOTALTRACKS", "TOTALDISCS");

    /** Several artists in one tag: Navidrome splits on " / " into separate artists. */
    private static final String ARTIST_SEPARATOR = " / ";

    /** YouTube Music album art; a video frame (i.ytimg.com) is not album art and is never embedded. */
    private static final Pattern ALBUM_ART =
            Pattern.compile("^(https://(?:lh3|yt3)\\.googleusercontent\\.com/[^=?#]+)(?:=[^?#]*)?$");
    /** 1200 px, JPEG ("-rj"); YouTube's own is 544 px and its largest 1400 px. */
    private static final String COVER_SIZE = "=w1200-h1200-l90-rj";
    private static final Duration COVER_TIMEOUT = Duration.ofSeconds(10);
    private static final int COVER_MAX_BYTES = 4 * 1024 * 1024;

    // jaudiotagger logs routine steps at SEVERE through java.util.logging; application.yaml turns its
    // loggers off in the app, this keeps tests quiet too. JUL holds loggers weakly: a collected logger
    // forgets its level, hence the field.
    private static final Logger JAUDIOTAGGER_LOG = Logger.getLogger("org.jaudiotagger");

    static {
        JAUDIOTAGGER_LOG.setLevel(Level.OFF);
        TagOptionSingleton options = TagOptionSingleton.getInstance();
        // New and converted MP3 tags are ID3v2.4, the only version with a release-date frame (TDRL).
        options.setID3V2Version(ID3V2Version.ID3_V24);
        // WAV: an ID3 chunk (kept in step with the INFO list); INFO alone cannot hold ids or a picture.
        options.setWavOptions(WavOptions.READ_ID3_ONLY_AND_SYNC);
        options.setWavSaveOptions(WavSaveOptions.SAVE_BOTH_AND_SYNC);
    }

    private final WebClient http = WebClient.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(COVER_MAX_BYTES))
            .build();
    // ponytail: the last 16 covers (about 7 MB at 1200 px), so one album's songs fetch it once; an album
    // whose songs finish far apart, with 16 others between, fetches it again.
    private final Map<String, Mono<byte[]>> covers = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Mono<byte[]>> eldest) {
                    return size() > 16;
                }
            });

    /**
     * The cover to embed for a song: its album's art at 1200 px, fetched once per album and kept. Empty
     * when there is none to use; a failed or non-JPEG answer is logged, not kept, and the song is filed
     * without one.
     */
    public Mono<byte[]> cover(Tags tags) {
        String url = tags == null ? null : coverUrl(tags.coverUrl());
        return url == null ? Mono.empty() : covers.computeIfAbsent(url, this::fetch);
    }

    private Mono<byte[]> fetch(String url) {
        return http.get().uri(URI.create(url))
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(COVER_TIMEOUT)
                .filter(SongTagger::isJpeg)
                .onErrorResume(error -> {
                    log.info("Could not load the cover {}; songs are filed without it: {}", url, error.toString());
                    return Mono.empty();
                })
                .cache(bytes -> Duration.ofMillis(Long.MAX_VALUE), error -> Duration.ZERO, () -> Duration.ZERO);
    }

    /**
     * Writes YouTube Music's details into one file (P1/P2 above). Never throws: a file that cannot be
     * tagged is logged and left exactly as it was (P10).
     *
     * @param cover JPEG bytes, or {@link #NO_COVER}
     */
    public void tag(Path file, Tags tags, byte[] cover) {
        if (tags == null) {
            return;
        }
        try {
            if (write(file, tags, cover)) {
                log.info("Tagged {} from YouTube Music ({})", file.getFileName(),
                        tags.album() == null ? "no trusted album: gaps filled" : "album '" + tags.album() + "'");
            }
        } catch (Exception | OutOfMemoryError | StackOverflowError e) {
            // A broken file can make the reader allocate or recurse without bound; the song is still filed.
            log.warn("Filing {} with the tags it came with: {}", file.getFileName(), e.toString());
        }
    }

    // ponytail: one tagging at a time, process-wide. The tag library's options are one global and its
    // MP4 writer keeps state between calls; a file takes milliseconds, so the lock costs nothing yet.
    private static synchronized boolean write(Path file, Tags tags, byte[] cover) throws Exception {
        // By extension, which must match the content: a FLAC named .mp3 fails here and is left alone.
        AudioFile audio = AudioFileIO.read(file.toFile());
        Tag tag = audio.getTagAndConvertOrCreateAndSetDefault();
        Plan plan = plan(tag.getFirst(FieldKey.TITLE), tag.getFirst(FieldKey.ARTIST),
                tag.getFirstArtwork() != null, tags);
        boolean writeCover = plan.cover() && cover.length > 0;
        if (plan.set().isEmpty() && plan.delete().isEmpty() && !writeCover) {
            return false;
        }
        for (Map.Entry<FieldKey, String> field : plan.set().entrySet()) {
            tag.setField(field.getKey(), field.getValue());
        }
        for (FieldKey key : plan.delete()) {
            try {
                tag.deleteField(key);
            } catch (KeyNotFoundException | UnsupportedOperationException notInThisFormat) {
                // nothing to remove
            }
        }
        if (plan.album()) {
            clearRaw(tag);
            releaseDate(tag, plan.set().get(FieldKey.YEAR));
        }
        if (writeCover) {
            try {
                Artwork art = ArtworkFactory.getNew();
                art.setBinaryData(cover);
                art.setMimeType("image/jpeg");
                art.setPictureType(PictureTypes.DEFAULT_ID);
                tag.deleteArtworkField();
                tag.setField(art);
            } catch (Exception e) {
                log.info("Tagging {} without its cover: {}", file.getFileName(), e.toString());
            }
        }
        audio.commit();
        return true;
    }

    // ---- pure rules ------------------------------------------------------------------------------

    /**
     * What to change in a file whose title, artist and cover are as given. Title and artist are only
     * ever filled (P2). A trusted album sets the album identity (P1); the fields of Navidrome's album key
     * (album artist, release date) are removed when YouTube has no value, so every song of the album
     * agrees, while track and track total are only set when known.
     */
    static Plan plan(String title, String artist, boolean hasCover, Tags yt) {
        Map<FieldKey, String> set = new LinkedHashMap<>();
        List<FieldKey> delete = new ArrayList<>();
        fill(set, FieldKey.TITLE, title, yt.title());
        fill(set, FieldKey.ARTIST, artist, join(yt.artists()));
        boolean album = !blank(yt.album());
        if (album) {
            set.put(FieldKey.ALBUM, yt.album());
            setOrDelete(set, delete, FieldKey.ALBUM_ARTIST, join(yt.albumArtists()));
            setOrDelete(set, delete, FieldKey.YEAR, yt.year() == null ? null : yt.year().toString());
            // Track before its total: some formats keep both in one field.
            if (yt.track() != null) set.put(FieldKey.TRACK, yt.track().toString());
            if (yt.trackTotal() != null) set.put(FieldKey.TRACK_TOTAL, yt.trackTotal().toString());
            // Always: a mix of "disc 1" and no disc shows as two disc groups.
            set.put(FieldKey.DISC_NO, "1");
            set.put(FieldKey.DISC_TOTAL, "1");
            delete.addAll(SPLITS_AN_ALBUM);
        }
        return new Plan(set, delete, album, album || !hasCover);
    }

    /** The album art at {@link #COVER_SIZE}, or null when the picture is not YouTube Music album art. */
    static String coverUrl(String imageUrl) {
        Matcher m = imageUrl == null ? null : ALBUM_ART.matcher(imageUrl);
        return m != null && m.matches() ? m.group(1) + COVER_SIZE : null;
    }

    static boolean isJpeg(byte[] bytes) {
        return bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    }

    private static void fill(Map<FieldKey, String> set, FieldKey key, String current, String value) {
        if (blank(current) && !blank(value)) {
            set.put(key, value);
        }
    }

    private static void setOrDelete(Map<FieldKey, String> set, List<FieldKey> delete, FieldKey key, String value) {
        if (blank(value)) {
            delete.add(key);
        } else {
            set.put(key, value);
        }
    }

    private static String join(List<String> values) {
        return values == null ? null : String.join(ARTIST_SEPARATOR, values.stream().filter(v -> !blank(v)).toList());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---- per-format leftovers --------------------------------------------------------------------

    /** Removes {@link #RAW_SPLITS_AN_ALBUM} in each format's own free-form shape. */
    private static void clearRaw(Tag tag) throws Exception {
        switch (tag) {
            case AbstractID3v2Tag id3 -> {
                List<TagField> txxx = id3.getFields("TXXX");
                List<TagField> keep = txxx.stream()
                        .filter(f -> !(((AbstractID3v2Frame) f).getBody() instanceof FrameBodyTXXX body
                                && RAW_SPLITS_AN_ALBUM.contains(body.getDescription().toUpperCase(Locale.ROOT))))
                        .toList();
                if (keep.size() != txxx.size()) {
                    id3.deleteField("TXXX");
                    for (TagField f : keep) {
                        id3.addField(f);
                    }
                }
            }
            case FlacTag flac -> RAW_SPLITS_AN_ALBUM.forEach(k -> flac.deleteField(k.replace(' ', '_')));
            case VorbisCommentTag vorbis -> RAW_SPLITS_AN_ALBUM.forEach(k -> vorbis.deleteField(k.replace(' ', '_')));
            case Mp4Tag mp4 -> {
                RAW_SPLITS_AN_ALBUM.forEach(k -> mp4.deleteField("----:com.apple.iTunes:" + k));
                mp4.deleteField("----:com.apple.iTunes:MusicBrainz Album Comment");
            }
            default -> { } // WAV, AIFF, WMA: rare; their generic fields above are cleared
        }
    }

    /**
     * Navidrome's album key includes the release date, which it reads from TDRL (ID3v2.4), RELEASEDATE
     * (Vorbis) or ©day (M4A, which is YEAR and already written). The year goes in each, or each is
     * removed when YouTube has none, so an MP3, a FLAC and an M4A of one album agree.
     */
    private static void releaseDate(Tag tag, String year) throws Exception {
        switch (tag) {
            case AbstractID3v2Tag id3 -> {
                if (year == null) {
                    id3.deleteField("TDRL");
                    return;
                }
                AbstractID3v2Frame tdrl = id3.createFrame("TDRL");
                ((AbstractFrameBodyTextInfo) tdrl.getBody()).setText(year);
                id3.setField(tdrl);
            }
            case FlacTag flac -> {
                if (year != null) flac.setField("RELEASEDATE", year);
            }
            case VorbisCommentTag vorbis -> {
                if (year != null) vorbis.setField("RELEASEDATE", year);
            }
            default -> { } // M4A ©day and WMA WM/Year are YEAR itself
        }
    }
}
