package com.catacomb5099.naviseerr.schema.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class Song {
    String id;
    String iconURL;
    String streamURL;
    String name;
    List<String> artists; // List of artist IDs
    String albumId;
    int year;
}

