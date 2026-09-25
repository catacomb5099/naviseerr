package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.services.ytmusic.model.YoutubeSongInfo;

import java.util.List;

/**
 * One {@code media_items} row: what a YouTube id IS, as far as the download view needs to know —
 * a name, artists, a picture. Songs and collections share the shape; {@link #durationSeconds} is
 * only ever set for a song and {@link #trackCount} only for a collection.
 *
 * <p>Serialised whole as JSON for {@code DownloadTaskRepository.UPSERT_MEDIA_SQL}, so the component
 * names here ARE the column names that statement's {@code jsonb_to_recordset} declares. Rename one,
 * rename the other.
 */
public record MediaItem(String youtubeId, String title, List<String> artists, String imageUrl,
                        Integer durationSeconds, Integer trackCount) {

    public static MediaItem of(YoutubeSongInfo song) {
        return new MediaItem(song.id(), song.name(), song.authorNames(), song.imageUrl(),
                song.durationSeconds(), null);
    }
}
