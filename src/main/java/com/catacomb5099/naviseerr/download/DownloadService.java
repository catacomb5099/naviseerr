package com.catacomb5099.naviseerr.download;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
public class DownloadService {

    private final R2dbcEntityTemplate entityTemplate;

    public DownloadService(R2dbcEntityTemplate entityTemplate) {
        this.entityTemplate = entityTemplate;
    }

    public Mono<Download> requestDownload(String songName) {
        Download download = Download.builder()
                .downloadId(UUID.randomUUID())
                .songName(songName)
                .status(DownloadStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        // insert() forces an INSERT; save() would treat the pre-set @Id as an UPDATE.
        return entityTemplate.insert(download);
    }
}
