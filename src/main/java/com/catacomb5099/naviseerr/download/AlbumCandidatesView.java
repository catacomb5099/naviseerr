package com.catacomb5099.naviseerr.download;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The folders an album's search judged, for a person to choose one from
 * ({@code GET /downloads/{id}/album-candidates}), each with the file it holds for every song of the
 * album. Read from the album search's remembered folders (V15).
 *
 * @param reason     with {@link PickListStatus#NONE}: {@code NO_WHOLE_FOLDER} (nobody shared enough of the
 *                   album as one folder), {@code BEFORE_CACHE} (searched before lists were kept) or
 *                   {@code NO_ALBUM_SEARCH} (admitted before albums searched as a whole); null otherwise
 * @param query      the wording the album search used, so the client can show it
 * @param searchedAt when the folders were remembered; null when never
 * @param songCount  how many songs the album has, to read {@code fileCount} against
 */
public record AlbumCandidatesView(UUID downloadId, PickListStatus status, String reason, String query, Instant searchedAt,
                                  int songCount, List<Folder> folders) {

    /**
     * @param folder       the shared folder's path, verbatim: the pick sends it back unchanged
     * @param fileCount    how many of the album's songs the folder holds
     * @param totalSize    bytes, over those files
     * @param extras       other usable audio files there: a deluxe-edition hint, never downloaded
     * @param songsCurrent how many of the album's songs download (or downloaded) from this folder
     * @param judged       whether the album search itself would take this folder; false for one kept only for
     *                     a person to choose (too few songs, no artist in its path, low bit rate, stalling sharer)
     */
    public record Folder(String username, String folder, int fileCount, long totalSize, Integer uploadSpeed,
                         Boolean freeSlot, Integer queueLength, int extras, int songsCurrent, boolean isCurrent,
                         boolean judged, List<File> files) {
    }

    /**
     * @param index  the song's 1-based place in the album; null for a row admitted before order was kept
     * @param name   the file's own name, without its folder
     * @param title  the song's title, from the album row
     */
    public record File(Integer index, UUID taskId, String name, String title, long size, Integer bitrateKbps,
                       Integer lengthSeconds, String extension) {
    }
}
