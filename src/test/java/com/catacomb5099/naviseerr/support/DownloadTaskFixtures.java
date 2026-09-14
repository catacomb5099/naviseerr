package com.catacomb5099.naviseerr.support;

import com.catacomb5099.naviseerr.download.DownloadCandidate;
import com.catacomb5099.naviseerr.download.DownloadPhase;
import com.catacomb5099.naviseerr.download.DownloadTask;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DownloadTaskFixtures {

    public static final Instant T0 = Instant.parse("2026-08-13T12:00:00Z");
    public static final UUID ID = UUID.fromString("7f3a0000-0000-0000-0000-000000000001");
    public static final UUID TASK_ID = UUID.fromString("7f3a0000-0000-0000-0000-0000000000a1");
    public static final String YOUTUBE_ID = "dQw4w9WgXcQ";

    private DownloadTaskFixtures() {}

    public static DownloadCandidate candidate(String username) {
        return new DownloadCandidate(username, "music/" + username + "/song.flac", "flac",
                1411, 1000L, 42L, false);
    }

    public static List<DownloadCandidate> candidates(String... usernames) {
        return java.util.Arrays.stream(usernames).map(DownloadTaskFixtures::candidate).toList();
    }

    /** Fixed ids, so a test can assert on the task the state machine returned, not a random one. */
    public static DownloadTask at(DownloadPhase phase) {
        return DownloadTask.initial(ID, YOUTUBE_ID, "never gonna give you up", T0)
                .toBuilder().taskId(TASK_ID).build()
                .withPhase(phase, T0);
    }

    public static DownloadTask searchPolling(String searchId) {
        return at(DownloadPhase.SEARCH_POLL).toBuilder().searchId(searchId).build();
    }

    public static DownloadTask downloadPolling(List<DownloadCandidate> candidates,
                                              int candidateIndex, int retryIndex,
                                              String transferId) {
        DownloadCandidate current = candidates.get(candidateIndex);
        return at(DownloadPhase.DOWNLOAD_POLL).toBuilder()
                .searchId("s1")
                .candidates(candidates)
                .candidateIndex(candidateIndex)
                .retryIndex(retryIndex)
                .slskdUsername(current.username())
                .slskdFilename(current.filename())
                .slskdTransferId(transferId)
                .build();
    }

    public static DownloadTask downloadInit(List<DownloadCandidate> candidates,
                                            int candidateIndex, int retryIndex) {
        return at(DownloadPhase.DOWNLOAD_INIT).toBuilder()
                .searchId("s1")
                .candidates(candidates)
                .candidateIndex(candidateIndex)
                .retryIndex(retryIndex)
                .build();
    }
}
