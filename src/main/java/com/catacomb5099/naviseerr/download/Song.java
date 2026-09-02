package com.catacomb5099.naviseerr.download;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.List;
import java.util.UUID;

/**
 * One song's metadata for one download: name, artists, and cover image. Written once, at request
 * time, by the same statement that creates its {@code downloads} row -- see
 * {@link DownloadService#requestDownload(String, List, String)} -- so a download without a song
 * cannot exist. {@code artists} round-trips through Spring Data R2DBC's Postgres array support as
 * {@code List<String>} without needing {@code String[]}; see {@code SongEntityIT} for the test that
 * settled it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("songs")
public class Song {

    @Id
    @Column("song_id")
    private UUID songId;

    @Column("download_id")
    private UUID downloadId;

    @Column("name")
    private String name;

    @Column("artists")
    private List<String> artists;

    @Column("image_url")
    private String imageUrl;
}
