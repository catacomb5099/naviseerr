package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A slskd transfer. Numeric fields are boxed because slskd omits them depending on transfer state
 * (it never sends {@code startOffset} at all), and an absent primitive is a decoding error rather
 * than a defaulted zero. Treat any of them as possibly null.
 */
@Getter
@AllArgsConstructor
public class TransferedFile {
    String id;
    String username;
    String direction;
    String filename;
    Long size;
    Long startOffset;
    String state;
    String requestedAt;
    String enqueuedAt;
    String startedAt;
    String endedAt;
    Long bytesTransferred;
    Float averageSpeed;
    Long bytesRemaining;
    String elapsedTime;
    Float percentComplete;
    String remainingTime;
}
