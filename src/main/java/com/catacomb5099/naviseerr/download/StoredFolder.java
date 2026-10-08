package com.catacomb5099.naviseerr.download;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One sharer's album folder as the album search judged it, kept in {@code album_searches.folders}
 * (V15) so a person can later choose another folder without searching again. {@code files} is the
 * file matched to each of the album's songs, keyed by task id, already in the shape a song downloads
 * from ({@link DownloadCandidate#fromAlbumFolder}); the sharer's own stats are repeated at folder
 * level so the folder list needs no file to read them from.
 */
public record StoredFolder(
        String username,
        String path,
        Boolean hasFreeUploadSlot,
        Integer queueLength,
        Integer uploadSpeed,
        /** Usable audio files in the folder that matched no song: bonus tracks, other takes (a deluxe sign). */
        int extras,
        Map<UUID, DownloadCandidate> files,
        /**
         * Whether the album search itself would take this folder ({@link AlbumFolderPicker#folders}), or
         * it is kept only for a person to choose ({@link AlbumFolderPicker#allFolders}, 08-10-2026): too
         * few of the album's songs, no artist in its path, a low bit rate or a stalling sharer. Null on
         * rows written before 08-10-2026, which were all judged.
         */
        Boolean judged) {

    public static StoredFolder of(AlbumFolderPicker.Folder folder, boolean judged) {
        Map<UUID, DownloadCandidate> files = new LinkedHashMap<>();
        folder.files().forEach((taskId, file) ->
                files.put(taskId, DownloadCandidate.fromAlbumFolder(folder.peer(), file)));
        return new StoredFolder(folder.peer().getUsername(), folder.path(), folder.peer().getHasFreeUploadSlot(),
                folder.peer().getQueueLength(), folder.peer().getUploadSpeed(), folder.extras(), files, judged);
    }

    /** The judged folders first (in their order), then every other folder of {@code all}. */
    public static List<StoredFolder> of(List<AlbumFolderPicker.Folder> judged, List<AlbumFolderPicker.Folder> all) {
        Set<String> taken = new HashSet<>();
        List<StoredFolder> out = new ArrayList<>();
        for (AlbumFolderPicker.Folder folder : judged) {
            if (taken.add(folder.key())) {
                out.add(of(folder, true));
            }
        }
        for (AlbumFolderPicker.Folder folder : all) {
            if (taken.add(folder.key())) {
                out.add(of(folder, false));
            }
        }
        return out;
    }
}
