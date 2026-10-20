package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.schema.slskd.SearchState;
import com.catacomb5099.naviseerr.util.TrackMatchingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static reactor.netty.http.HttpConnectionLiveness.log;

@Component
public class SlskdSearchResultProcessor {
    private final SlskdService slskdService;
    private final TrackMatchingService trackMatchingService;

    @Value("${slskd-service.max-files-per-download}")
    int maxFilesPerDownload;
    @Value("${download-task.max-sharer-queue:50}")
    int maxSharerQueue;

    /** One shared file that is the song, with how well it answers the request. */
    public record Pick(SearchResponseItem peer, SearchFile file, TrackMatchingService.Match grade) {}

    // A free slot is a fact about now; uploadSpeed is the peer's own unverified claim about its
    // history. So: can they start at all, then how many people are ahead of you, then speed.
    private static final Comparator<SearchResponseItem> BY_AVAILABILITY =
            Comparator
                    .comparing((SearchResponseItem peer) -> !Boolean.TRUE.equals(peer.getHasFreeUploadSlot()))
                    .thenComparingInt(SearchResponseItem::getQueueLength)
                    .thenComparingInt(peer -> -peer.getUploadSpeed());

    public SlskdSearchResultProcessor(SlskdService slskdService, TrackMatchingService trackMatchingService) {
        this.slskdService = slskdService;
        this.trackMatchingService = trackMatchingService;
    }

    /**
     * The files worth trying, in the order to try them: every file in the requested version before any
     * other version of the song (the candidate list is walked in order, so a live take or remix is only
     * downloaded once every exact option has failed), then within each grade the chosen quality tier first
     * and, when it has nothing, every higher tier before any lower one, nearest first either way
     * ({@link DownloadQuality#distance}, the owner's call of 20-10-2026), then the length most
     * files share, then who can serve fastest; then spread so that no sharer's second file comes before
     * every other sharer's first within the same grade and tier ({@link #spreadAcrossSharers}). A lossy file whose bit rate slskd did not report has no tier
     * and is not tried (2.7% of files, mostly m4a and opus); it stays on the manual list. Capped at
     * {@code maxFilesPerDownload}: other versions take whatever places the exact ones leave, which is all
     * of them when none is exact and none when ten are.
     *
     * <p>A file whose path does not name the artist, found by a wording that did not name it either
     * ({@code UNVERIFIED}, see {@link TrackMatchingService#grade(String, String, String)}), is the song by
     * name only. It is kept as the last resort -- a YouTube "artist" that is really a channel name appears
     * in no path at all, and a song shared only under a collaborator's folder has no other copy -- but never
     * beside a file that does name the artist.
     *
     * <p>For an album's song searching on its own (P6): a file whose length slskd gives and that is not
     * within {@link #lengthTolerance} of the album track's is dropped before anything else is judged,
     * whatever its name says. "Live at Town Hall/03 - Mr. Jones.flac" is graded the requested version (the
     * name carries no "live"), and only its 379 s against the album's 270 s gives it away. A file with no
     * length (3.5% of them) is no evidence either way and stays.
     *
     * @param query             the cleaned song name the files are judged against ({@code SearchQueryTiers.pickerName})
     * @param wording           what was actually searched
     * @param albumTrackSeconds the album row's length; null for a song or playlist track, which keeps
     *                          any length
     * @param chosen            the download quality the install chose ({@code install_settings.download_quality},
     *                          read once per completed search)
     */
    public Mono<List<Pick>> selectBestFiles(SearchState state, String query, String wording,
                                            Integer albumTrackSeconds, DownloadQuality chosen) {
        return Mono.fromCallable(() -> {
            // Null rather than empty when the caller handed us a search fetched without
            // includeResponses. Degrade to "no candidates" instead of an NPE, so the failure reads as
            // what it is in the log below rather than as a generic step error.
            List<SearchResponseItem> responses =
                    state.getResponses() == null ? List.of() : state.getResponses();
            List<Pick> candidates = graded(state, query, wording, file -> DownloadQuality.of(file).isPresent(), true);
            if (albumTrackSeconds != null) {
                List<Pick> sameLength = candidates.stream()
                        .filter(pick -> pick.file().getLength()
                                .map(seconds -> Math.abs(seconds - albumTrackSeconds) <= lengthTolerance(albumTrackSeconds))
                                .orElse(true))
                        .toList();
                if (sameLength.size() < candidates.size()) {
                    log.info("query='{}': {} file(s) dropped for not lasting the album track's {} s", query,
                            candidates.size() - sameLength.size(), albumTrackSeconds);
                }
                candidates = sameLength;
            }
            long unverified = candidates.stream().filter(pick -> pick.grade() == TrackMatchingService.Match.UNVERIFIED).count();
            if (unverified < candidates.size()) {
                candidates = candidates.stream().filter(pick -> pick.grade() != TrackMatchingService.Match.UNVERIFIED).toList();
            }

            // Most candidates share the duration of the mainstream release; remixes, live takes and
            // album re-records are the odd lengths out. Prefer the most shared length, then fall back
            // to who can serve the file fastest. Measured 80% -> 93% correct top pick on 718 songs.
            // Counted within a grade, so the other versions cannot outvote the exact ones. The chosen
            // quality tier sits in front of the duration vote: measured on the same 718 songs, a Lossless
            // chooser gets lossless 97.8% of the time with the tier first against 95.9% after the vote,
            // the right version equally often either way (17 songs where the FLAC rip runs a second off
            // the MP3 majority), so the tier decides before "who is quickest" and never before the grade.
            //
            // One exception sits in front of both (but behind the grade): a sharer with
            // no free upload slot AND more than max-sharer-queue files already waiting goes to the
            // back of its grade, whatever its file's length. The duration vote protects against the
            // wrong version; this protects against a sharer that will not reach us inside
            // queued-budget-ms at all (27-09-2026: two such sharers cost ten minutes per song, 58
            // times). Putting the demotion inside each duration group instead would change nothing,
            // because BY_AVAILABILITY already sorts busy sharers to the end of their group. The price
            // is small: only when EVERY sharer of the majority length is that overloaded does an
            // odd-length file get tried first, and the threshold is set high enough that "busy" alone
            // never triggers it.
            candidates = rank(candidates, chosen);

            long exact = candidates.stream().filter(pick -> pick.grade() == TrackMatchingService.Match.EXACT).count();
            log.info("Completed candidate selection for query='{}' (searched '{}') - {} response(s), {} total files, {} relevant candidates ({} in the requested version, {} other versions, {} unverified artist{}); limiting to {} by maxFilesPerDownload",
                    query, wording, responses.size(), state.getFileCount(), candidates.size(), exact,
                    candidates.size() - exact - Math.min(unverified, candidates.size()), Math.min(unverified, candidates.size()),
                    unverified < candidates.size() || unverified == 0 ? "" : ", no file names the artist", maxFilesPerDownload);
            return spreadAcrossSharers(candidates, chosen).stream().limit(maxFilesPerDownload).toList();
        });
    }

    /**
     * Every audio file the search returned -- any grade, NONE included, any format or bit rate -- in
     * the order the automatic picker would try them, the files the matcher calls another song last.
     * For a person to choose from (manual pick, 07-10-2026; every file since 08-10-2026): the format
     * tier rule, the album-length rule, the unverified-artist rule and the name match itself are the
     * picker's judgement calls, and the whole point of choosing by hand is to overrule them, so none
     * drops a file here; the grade travels on each file instead and is shown as a badge, and the order
     * is still the picker's (the chosen tier first, a file with no tier last). Cover
     * art, cue sheets and logs are left out: a song cannot download as one. Nothing is capped either:
     * the caller keeps as many as it wants to store.
     */
    public List<Pick> allFiles(SearchState state, String query, String wording, DownloadQuality chosen) {
        return rank(graded(state, query, wording, SlskdSearchResultProcessor::isAudio, false), chosen);
    }

    /** Every file passing {@code keep}, graded against the song; with {@code dropNone} the files the matcher rejects are gone. */
    private List<Pick> graded(SearchState state, String query, String wording, Predicate<SearchFile> keep, boolean dropNone) {
        List<SearchResponseItem> responses = state.getResponses() == null ? List.of() : state.getResponses();
        return responses.stream()
                .flatMap(item -> item.getFiles().stream()
                        .filter(keep)
                        .map(file -> new Pick(item, file, trackMatchingService.grade(query, file.getFilename(), wording))))
                .filter(pick -> !dropNone || pick.grade() != TrackMatchingService.Match.NONE)
                .toList();
    }

    /** The picker's order: grade, overloaded sharers last, the chosen tier then the higher ones then the lower ones, the length most files share, availability. */
    private List<Pick> rank(List<Pick> candidates, DownloadQuality chosen) {
        Map<TrackMatchingService.Match, Map<Integer, Long>> countByLength = candidates.stream()
                .filter(pick -> pick.file().getLength().isPresent())
                .collect(Collectors.groupingBy(Pick::grade,
                        Collectors.groupingBy(pick -> pick.file().getLength().get(), Collectors.counting())));
        return candidates.stream()
                .sorted(Comparator
                        .comparing(Pick::grade)
                        .thenComparing(pick -> isOverloaded(pick.peer()))
                        .thenComparingInt(pick -> tierDistance(pick.file(), chosen))
                        .thenComparingLong((Pick pick) -> -pick.file().getLength()
                                .map(length -> countByLength.get(pick.grade()).get(length)).orElse(0L))
                        .thenComparing(Pick::peer, BY_AVAILABILITY))
                .toList();
    }

    /**
     * Same order, but no sharer gets a second file in the list until every other sharer has had
     * its first, then a third until every one has had a second, and so on. A failover list made of
     * one sharer's ten pressings of the same album is ten chances for the same "Queued, Remotely"
     * (27-09-2026: songs with three to five files all from one stalling sharer waited 30-50 minutes
     * before running out). Spreading the list means a sharer that stalls or throttles costs one
     * candidate, not the whole list. Within a round the ranking above still decides who goes first.
     * Done within each run of files that share the ranking's first three keys (grade, overloaded, tier
     * distance), so every file in the requested version still comes before any other version, and every
     * file in the next tier of the fallback order before any file of the one after: the spread never promotes a fallback above
     * an exact match, nor (review of 20-10-2026; until 19-10 the list held lossless and 320 kbps files
     * only, so a spread per grade never cost a tier) another sharer's 128 kbps file above the first
     * sharer's second lossless copy. The price: a sharer alone in its run keeps its files in a row, so
     * one that stalls with the only copies in your tier costs one budget per file before the next tier.
     */
    List<Pick> spreadAcrossSharers(List<Pick> ranked, DownloadQuality chosen) {
        List<Pick> spread = new java.util.ArrayList<>(ranked.size());
        // ranked is sorted by these three keys first, so the runs come out in the ranking's order.
        Map<Run, Map<String, java.util.ArrayDeque<Pick>>> runs = new java.util.LinkedHashMap<>();
        for (Pick pick : ranked) {
            runs.computeIfAbsent(new Run(pick.grade(), isOverloaded(pick.peer()), tierDistance(pick.file(), chosen)), k -> new java.util.LinkedHashMap<>())
                    .computeIfAbsent(pick.peer().getUsername(), k -> new java.util.ArrayDeque<>()).add(pick);
        }
        for (Map<String, java.util.ArrayDeque<Pick>> bySharer : runs.values()) {
            int remaining = bySharer.values().stream().mapToInt(java.util.ArrayDeque::size).sum();
            while (remaining > 0) {
                for (java.util.ArrayDeque<Pick> queue : bySharer.values()) {
                    if (!queue.isEmpty()) {
                        spread.add(queue.poll());
                        remaining--;
                    }
                }
            }
        }
        return spread;
    }

    /** The ranking's first three keys: the spread above never moves a file across a change in them. */
    private record Run(TrackMatchingService.Match grade, boolean overloaded, int tierDistance) {}

    /**
     * How far a file's length may be from the YouTube track's and still be the same recording:
     * max(10 s, 3%). Per track, 90% of the album folders measured 04-10-2026 were within 1-12 s of the
     * usual length; a live take or another edit is usually further off. Also the whole-album picker's rule.
     */
    public static int lengthTolerance(int expectedSeconds) {
        return Math.max(10, (int) Math.round(expectedSeconds * 0.03));
    }

    /** No slot for us now and a long line ahead of us: the profile of a sharer that never serves. */
    public boolean isOverloaded(SearchResponseItem sharer) {
        return !Boolean.TRUE.equals(sharer.getHasFreeUploadSlot())
                && sharer.getQueueLength() > maxSharerQueue;
    }

    /** What a song can download as. Cover art, cue sheets, logs and playlists are not an option, so the manual list skips them. */
    private static final Set<String> AUDIO = Set.of("flac", "wav", "aif", "aiff", "aifc", "ape", "wv", "mp3", "m4a", "aac",
            "ogg", "oga", "opus", "wma", "alac", "mpc", "dsf");

    public static boolean isAudio(SearchFile file) {
        return AUDIO.contains(format(file));
    }

    /** A file with no tier (the manual list only; the automatic pick never holds one) sorts after every tier. */
    private static int tierDistance(SearchFile file, DownloadQuality chosen) {
        return DownloadQuality.of(file).map(chosen::distance).orElse(Integer.MAX_VALUE);
    }

    /**
     * The format, lower-cased, from the file name's suffix. Not slskd's {@code extension} field: it is
     * blank on most files (8,972 of 14,188 FLAC files on 04-10-2026) and sometimes wrong (218 FLAC files
     * claimed "mp3"). Falls back to that field only for a name with no suffix.
     */
    private static String format(SearchFile file) {
        return format(file.getFilename(), file.getExtension());
    }

    /** The same from the two stored fields of a candidate; public so the manual picker's views can show it. */
    public static String format(String filename, String extension) {
        String name = filename == null ? "" : filename;
        String leaf = name.substring(Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/')) + 1);
        int dot = leaf.lastIndexOf('.');
        String format = dot >= 0 ? leaf.substring(dot + 1) : extension;
        return format == null ? "" : format.toLowerCase(Locale.ROOT);
    }

}
