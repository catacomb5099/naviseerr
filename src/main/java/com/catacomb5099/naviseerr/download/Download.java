package com.catacomb5099.naviseerr.download;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One user request: a song, an album, or a playlist. The low-churn, user-facing record — written
 * twice (at request, at conclusion) against the several-times-a-second churn of
 * {@link DownloadTask}.
 *
 * <p>It holds an opaque YouTube id and a type, and nothing about tracks. What the id resolves to is
 * fetched from ytmusic-adapter at admission and written to {@code download_tasks}, one row per song.
 * A playlist has no single track to name at request time, which is why {@link #songName} is not set
 * by the request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("downloads")
public class Download {

    @Id
    @Column("download_id")
    private UUID downloadId;

    /**
     * What the client asked for, as YouTube Music identifies it: a {@code videoId} for a song, a
     * {@code browseId} for an album, a playlist id for a playlist. Which of the three it is, is
     * {@link #downloadType}.
     */
    @Column("youtube_id")
    private String youtubeId;

    @Column("download_type")
    private DownloadType downloadType;

    /**
     * The download's display title — the song's title, or the album/playlist's title — and the only
     * field on this row the client renders as text.
     *
     * <p>Null between the request being accepted and its metadata arriving, because the request
     * carries only an id. Admission writes it in the same statement that creates the task rows. The
     * per-song titles that word each Soulseek query are on {@code download_tasks.song_name}; for a
     * single-song download the two agree, for a collection they do not.
     */
    @Column("song_name")
    private String songName;

    @Column("status")
    private DownloadStatus status;

    @Column("created_at")
    private Instant createdAt;
}
