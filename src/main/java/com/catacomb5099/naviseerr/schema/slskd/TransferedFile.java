package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TransferedFile {
    String id;
    String username;
    String direction;
    String filename;
    long size;
    long startOffset;
    String state;
    String requestedAt;
    String enqueuedAt;
    String startedAt;
    String endedAt;
    long bytesTransferred;
    float averageSpeed;
    long bytesRemaining;
    String elapsedTime;
    float percentComplete;
    String remainingTime;
}
