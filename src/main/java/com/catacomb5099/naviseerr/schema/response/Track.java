package com.catacomb5099.naviseerr.schema.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class Track {
    String id;
    String iconURL;
    String streamURL;
    String name;
    List<String> artists; // List of artist IDs
    String albumId;
    int year;
    /**
     * YouTube Music's play count in its own wording ("7.2M plays"), never parsed: search results and
     * an artist's top songs carry one. Null when YouTube gave none (the Top result card, an older adapter).
     */
    String plays;
}

