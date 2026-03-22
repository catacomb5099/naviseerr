package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TransferState {
    NONE("None"),
    REQUESTED("Requested"),
    QUEUED("Queued"),
    INITIALIZING("Initializing"),
    IN_PROGRESS("InProgress"),
    COMPLETED("Completed"),
    SUCCEEDED("Succeeded", true, false),
    CANCELLED("Cancelled", false, true),
    TIMED_OUT("TimedOut", false, true),
    ERRORED("Errored", false, true),
    REJECTED("Rejected", false, true),
    ABORTED("Aborted", false, true),
    LOCALLY("Locally"),
    REMOTELY("Remotely");

    private final String value;
    private final boolean success;
    private final boolean failure;

    TransferState(String value) {
        this(value, false, false);
    }
}
