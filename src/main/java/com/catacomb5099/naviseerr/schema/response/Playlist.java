package com.catacomb5099.naviseerr.schema.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * A playlist search hit.
 *
 * <p>{@code id} is the bare playlist id ({@code PL...}, the adapter's {@code playlistId} with the
 * {@code VL} browse prefix stripped), falling back to {@code browseId} when the adapter has no bare
 * id. The client must use this same id for both {@code GET /collections/{id}?type=PLAYLIST} and
 * {@code POST /download/collection/{id}?type=PLAYLIST}: the backend keys {@code media_items} by
 * whatever id is posted, so the two must agree.
 */
@Getter
@AllArgsConstructor
public class Playlist {
    String id;
    String iconURL;
    String name;
    List<String> artists; // author display names
    int trackCount;
}
