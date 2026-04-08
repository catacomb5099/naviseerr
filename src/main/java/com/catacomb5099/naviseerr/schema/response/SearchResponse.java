package com.catacomb5099.naviseerr.schema.response;

import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class SearchResponse {
    @Nullable
    List<Song> songs;
    @Nullable
    List<Album> albums;
    @Nullable
    List<Artist> artists;
}

