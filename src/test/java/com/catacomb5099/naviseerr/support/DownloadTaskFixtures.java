package com.catacomb5099.naviseerr.support;

import com.catacomb5099.naviseerr.download.DownloadCandidate;
import com.catacomb5099.naviseerr.download.DownloadPhase;
import com.catacomb5099.naviseerr.download.DownloadTask;
import com.catacomb5099.naviseerr.schema.request.TrackQuery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DownloadTaskFixtures {

    public static final Instant T0 = Instant.parse("2026-08-13T12:00:00Z");
    public static final UUID ID = UUID.fromString("7f3a0000-0000-0000-0000-000000000001");
    public static final TrackQuery QUERY =
            new TrackQuery("never gonna give you up", List.of("Rick Astley"));

    private DownloadTaskFixtures() {}

    public static DownloadCandidate candidate(String username) {
        return new DownloadCandidate(username, "music/" + username + "/song.flac", "flac",
                1411, 1000L, 42L, false);
    }

    public static List<DownloadCandidate> candidates(String... usernames) {
        return java.util.Arrays.stream(usernames).map(DownloadTaskFixtures::candidate).toList();
    }

    public static DownloadTask at(DownloadPhase phase) {
        return DownloadTask.initial(ID, QUERY, T0).withPhase(phase, T0);
    }

    public static DownloadTask searchPolling(String searchId) {
        DownloadTask base = at(DownloadPhase.SEARCH_POLL);
        return new DownloadTask(base.downloadId(), base.query(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), searchId, List.of(), 0, 0,
                null, null, null, null);
    }

    public static DownloadTask downloadPolling(List<DownloadCandidate> candidates,
                                              int candidateIndex, int retryIndex,
                                              String transferId) {
        DownloadTask base = at(DownloadPhase.DOWNLOAD_POLL);
        DownloadCandidate current = candidates.get(candidateIndex);
        return new DownloadTask(base.downloadId(), base.query(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), "s1", candidates, candidateIndex,
                retryIndex, current.username(), current.filename(), transferId, null);
    }

    public static DownloadTask downloadInit(List<DownloadCandidate> candidates,
                                            int candidateIndex, int retryIndex) {
        DownloadTask base = at(DownloadPhase.DOWNLOAD_INIT);
        return new DownloadTask(base.downloadId(), base.query(), base.phase(),
                base.phaseEnteredAt(), base.nextAttemptAt(), "s1", candidates, candidateIndex,
                retryIndex, null, null, null, null);
    }
}
