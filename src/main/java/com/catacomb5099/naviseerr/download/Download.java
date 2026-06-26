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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("downloads")
public class Download {

    @Id
    @Column("download_id")
    private UUID downloadId;

    @Column("song_name")
    private String songName;

    @Column("status")
    private DownloadStatus status;

    @Column("created_at")
    private Instant createdAt;
}
