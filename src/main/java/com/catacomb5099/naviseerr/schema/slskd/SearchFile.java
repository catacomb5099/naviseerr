package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Optional;

@Getter
@AllArgsConstructor
public class SearchFile {
    String filename;
    long size;
    long code;
    Boolean isLocked;
    String extension;
    Optional<Integer> bitRate;
    // Duration in seconds, as reported by slskd. Missing for some peers.
    Optional<Integer> length;
}
