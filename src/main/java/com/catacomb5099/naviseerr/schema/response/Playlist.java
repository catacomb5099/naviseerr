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
    /**
     * Channel id per {@code artists} entry, index-aligned; null where YouTube gave none, which for a
     * playlist is the usual case (fan-made). A playlist's author is a channel, not necessarily an
     * artist page, so the client does not link these. See {@link Track#artistIds}.
     */
    List<String> artistIds;
    int trackCount;
    /**
     * How often the playlist itself was viewed, in YouTube Music's own wording ("375K views"), as
     * its search row shows it: passed through unparsed and never relabelled "plays" (a song's count).
     * Null where YouTube Music shows a song count instead (its own editorial playlists), on the
     * Top result card, and from an adapter image older than 19-10-2026.
     */
    String views;
}
