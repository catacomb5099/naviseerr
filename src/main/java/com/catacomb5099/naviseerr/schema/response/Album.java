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
    List<String> artists; // List of artist IDs
    int year;
}

