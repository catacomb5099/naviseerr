package com.catacomb5099.naviseerr.download;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<UUID, DownloadCandidate> files) {

    public static StoredFolder of(AlbumFolderPicker.Folder folder) {
        Map<UUID, DownloadCandidate> files = new LinkedHashMap<>();
        folder.files().forEach((taskId, file) ->
                files.put(taskId, DownloadCandidate.fromAlbumFolder(folder.peer(), file)));
        return new StoredFolder(folder.peer().getUsername(), folder.path(), folder.peer().getHasFreeUploadSlot(),
                folder.peer().getQueueLength(), folder.peer().getUploadSpeed(), folder.extras(), files);
    }

    public static List<StoredFolder> of(List<AlbumFolderPicker.Folder> folders, int limit) {
        return folders.stream().limit(limit).map(StoredFolder::of).toList();
    }
}
