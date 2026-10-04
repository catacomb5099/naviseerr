package com.catacomb5099.naviseerr.schema.slskd;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * slskd search states, mirroring {@link TransferState}'s shape. Unlike a transfer, {@code TimedOut}
 * is a normal way for a search to finish, not a failure.
 *
 * <p>These strings must be confirmed against the live slskd API; the design is robust to getting them
 * wrong, because an unrecognised state falls through to the "completed with no candidates" failure
 * path rather than being treated as success.
 */
@Getter
@RequiredArgsConstructor
public enum SlskdSearchState {
    NONE("None"),
    REQUESTED("Requested"),
    IN_PROGRESS("InProgress"),
    COMPLETED("Completed"),
    TIMED_OUT("TimedOut"),
    RESPONSE_LIMIT_REACHED("ResponseLimitReached"),
    FILE_LIMIT_REACHED("FileLimitReached"),
    CANCELLED("Cancelled", true),
    ERRORED("Errored", true);

    private final String value;
    private final boolean failure;

    SlskdSearchState(String value) {
        this(value, false);
    }

    /** slskd reports compound states like {@code "Completed, TimedOut"}. */
    public static List<SlskdSearchState> parse(String state) {
        if (state == null || state.isBlank()) return List.of();
        return Arrays.stream(state.split(","))
                .map(String::trim)
                .map(part -> Arrays.stream(values())
                        .filter(candidate -> candidate.getValue().equalsIgnoreCase(part))
                        .findFirst())
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    /**
     * {@code "Completed, Errored"} with responses is not a failure: slskd says that when more answers
     * arrive than its 250-response cap allows, and it still holds 251-252 good ones (5 of 1,463 searches in
     * slskd's history on 04-10-2026, "Daft Punk Discovery" among them). Errored with none is a failure.
     */
    public static boolean isFailure(String state, int responseCount) {
        return parse(state).stream().anyMatch(s -> s.failure && (s != ERRORED || responseCount == 0));
    }
}
