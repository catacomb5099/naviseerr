package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.request.TrackQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The one place that decides how a {@link TrackQuery} is worded for Soulseek. Before this class
 * existed, {@code DownloadStepExecutor} passed the bare song name to slskd with no artist at all,
 * and {@code TrackMatchingService} separately guessed at an artist by splitting the song name on
 * a hyphen -- two different, uncoordinated guesses at the same problem.
 */
@Component
public class SlskdQueryBuilder {

    // Soulseek matches tokens against file paths, so punctuation such as a hyphen is a token that
    // matches nothing in a real filename -- strip everything but letters, digits and spaces.
    private static final String STRIP_PUNCTUATION_PATTERN = "[^a-zA-Z0-9\\s]";

    // A four-way collab is rarely filed under all four artist names on Soulseek, so joining every
    // credited artist narrows the search too hard. Primary-artist-only is the safer default; the
    // toggle exists for catalogs/uploaders that DO credit everyone.
    @Value("${slskd-service.query-builder.use-all-artists}")
    private boolean useAllArtists;

    /**
     * Builds "artist song", space-joined, punctuation stripped -- e.g. {@code "Vance Joy Riptide"}.
     * Empty artists degrades to the song name alone.
     *
     * <p>{@code TrackMatchingService.buildFuzzyComposite} builds this exact same shape, as its own
     * private helper, to use as the fuzzy-comparison input -- including its own copy of
     * {@code useAllArtists}, injected from the same property key, so the fuzzy composite still
     * matches this method's output when the toggle is flipped to {@code true} and not just at the
     * default. The two cannot share code: this class lives in {@code services.slskd}, which
     * already depends on {@code util} (for {@code TrackMatchingService} itself, via
     * {@code SlskdSearchResultProcessor}), so a call the other way --
     * {@code TrackMatchingService} calling into {@code SlskdQueryBuilder} -- would make that
     * package dependency circular. The duplication is deliberate; keep the two wordings, and the
     * toggle, in sync by hand.
     */
    public String build(TrackQuery query) {
        String artistPrefix = artistPrefix(query.artists());
        String composite = artistPrefix.isEmpty()
                ? query.songName()
                : artistPrefix + " " + query.songName();
        return stripPunctuation(composite);
    }

    private String artistPrefix(List<String> artists) {
        if (artists.isEmpty()) {
            return "";
        }
        return useAllArtists
                ? String.join(" ", artists)
                : artists.get(0);
    }

    private String stripPunctuation(String value) {
        return value.replaceAll(STRIP_PUNCTUATION_PATTERN, "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
