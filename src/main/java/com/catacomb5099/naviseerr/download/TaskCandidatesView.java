package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.services.slskd.SlskdSearchResultProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every file a song's search found that is the song, for a person to choose from
 * ({@code GET /downloads/{id}/tasks/{taskId}/candidates}). Read from the row's remembered search
 * results (V15), or for an album's song that never searched on its own, from the album's remembered
 * folders. The automatic picker's filters are not applied: the grade is shown instead.
 *
 * @param reason     with {@link PickListStatus#NONE}: {@code ALREADY_IN_LIBRARY} (nothing was searched),
 *                   {@code NO_OWN_SEARCH} (the song never searched on its own: its file came from the album
 *                   search's folder or a manual pick), {@code SEARCH_FAILED} / {@code SOULSEEK_OFFLINE} (its
 *                   own search never completed: slskd refused it, Soulseek was offline, or it ran out of
 *                   time), {@code NO_RESULTS} (the search completed and found nothing relevant) or
 *                   {@code BEFORE_CACHE} (it searched before lists were kept); null otherwise
 * @param query      what was asked of Soulseek, so the client can show it
 * @param searchId   slskd's id for the song's own search, to find it in slskd's history; null when it never
 *                   searched on its own or slskd never took the search
 * @param searchedAt when the list was remembered; null when never searched
 * @param songStage  the song's own stage, as {@code GET /downloads/{id}} reports it
 * @param current    the file the song downloads (or downloaded) from; null before one was chosen
 */
public record TaskCandidatesView(UUID taskId, PickListStatus status, String reason, String query, String searchId,
                                 Instant searchedAt, DownloadStage songStage, Current current,
                                 List<Candidate> candidates) {

    public record Current(String username, String filename) {
        static Current of(DownloadCandidate candidate) {
            return candidate == null ? null : new Current(candidate.username(), candidate.filename());
        }
    }

    /**
     * @param filename   the full slskd path, verbatim: the pick sends it back unchanged
     * @param bitrateKbps slskd's bit rate; null for lossless or unknown
     * @param extension  lower-case, from the file name's suffix (slskd's own field is unreliable)
     * @param uploadSpeed bytes per second as the sharer claims
     * @param freeSlot   null when unknown
     */
    public record Candidate(String username, String filename, long size, Integer bitrateKbps, Integer lengthSeconds,
                            String extension, Integer uploadSpeed, Boolean freeSlot, Integer queueLength, String grade,
                            boolean isCurrent) {
        static Candidate of(DownloadCandidate c, DownloadCandidate current) {
            return new Candidate(c.username(), c.filename(), c.size(), c.bitRate(), c.length(),
                    SlskdSearchResultProcessor.format(c.filename(), c.extension()), c.uploadSpeed(),
                    c.hasFreeUploadSlot(), c.queueLength(), c.grade(), sameFile(c, current));
        }
    }

    /** The same shared file: one sharer, one path. */
    static boolean sameFile(DownloadCandidate a, DownloadCandidate b) {
        return a != null && b != null && a.username().equals(b.username()) && a.filename().equals(b.filename());
    }
}
