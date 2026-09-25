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
 * a handful of times (request, admission, conclusion) against the several-times-a-second churn of
 * {@link DownloadTask}.
 *
 * <p>It holds an opaque YouTube id, a type, a status and three timestamps, and nothing about what
 * the id is. What it resolves to — title, artists, artwork — is fetched from ytmusic-adapter at
 * admission and written to {@code media_items}, keyed by that same id; the track list goes to
 * {@code download_tasks}, one row per song. Everything that renders a download joins for the name.
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
     * {@link #downloadType}. Also the key into {@code media_items}.
     */
    @Column("youtube_id")
    private String youtubeId;

    @Column("download_type")
    private DownloadType downloadType;

    @Column("status")
    private DownloadStatus status;

    /**
     * Only for a failure that happens before any task row exists — ytmusic-adapter could not
     * resolve the id. Every other failure is recorded on the song that failed.
     */
    @Column("failure_reason")
    private String failureReason;

    /** When the request arrived. */
    @Column("created_at")
    private Instant createdAt;

    /** When the loop fetched the metadata and created the task rows. Null until then. */
    @Column("admitted_at")
    private Instant admittedAt;

    /** When the last song settled, or when admission failed. Null while anything is still running. */
    @Column("finished_at")
    private Instant finishedAt;
}
