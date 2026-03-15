package com.catacomb5099.naviseerr.schema.slskd;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Optional;

@Getter
@AllArgsConstructor
public class SearchState {
    Optional<String> endedAt;
    int fileCount;
    String id;
    Boolean isComplete;
    int lockedFileCount;
    int responseCount;
    List<SearchResponseItem> responses;
    String searchText;
    String startedAt;
    String state;
    int token;
}
