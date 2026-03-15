package com.catacomb5099.naviseerr.schema.slskd;

import java.util.List;
import java.util.Optional;

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

    // Constructor
    public SearchState(Optional<String> endedAt, int fileCount, String id, Boolean isComplete,
                       int lockedFileCount, int responseCount, List<SearchResponseItem> responses,
                       String searchText, String startedAt, String state, int token) {
        this.endedAt = endedAt;
        this.fileCount = fileCount;
        this.id = id;
        this.isComplete = isComplete;
        this.lockedFileCount = lockedFileCount;
        this.responseCount = responseCount;
        this.responses = responses;
        this.searchText = searchText;
        this.startedAt = startedAt;
        this.state = state;
        this.token = token;
    }

    public Optional<String> getEndedAt() {
        return endedAt;
    }

    public int getFileCount() {
        return fileCount;
    }

    public String getId() {
        return id;
    }

    public Boolean getComplete() {
        return isComplete;
    }

    public int getLockedFileCount() {
        return lockedFileCount;
    }

    public int getResponseCount() {
        return responseCount;
    }

    public List<SearchResponseItem> getResponses() {
        return responses;
    }

    public String getSearchText() {
        return searchText;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getState() {
        return state;
    }

    public int getToken() {
        return token;
    }
}
