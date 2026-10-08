package com.catacomb5099.naviseerr.schema.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class Album {
    String id;
    String iconURL;
    String name;
    List<String> artists; // artist display names; the client shows them as-is
    /** Channel id per {@code artists} entry, index-aligned; null where YouTube gave none. See {@link Track#artistIds}. */
    List<String> artistIds;
    int year;
}
