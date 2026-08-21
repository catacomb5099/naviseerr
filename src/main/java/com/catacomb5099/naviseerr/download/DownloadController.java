package com.catacomb5099.naviseerr.download;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;

@RestController
public class DownloadController {

    private final DownloadService downloadService;
    private final ActiveDownloadRepository activeDownloadRepository;
    private final long pollIntervalMs;

    public DownloadController(DownloadService downloadService,
                              ActiveDownloadRepository activeDownloadRepository,
                              @Value("${download-task.download-poll-interval-ms:5000}") Duration pollInterval) {
        this.downloadService = downloadService;
        this.activeDownloadRepository = activeDownloadRepository;
        this.pollIntervalMs = pollInterval.toMillis();
    }

    @PostMapping("/download/{songName}")
    Mono<ResponseEntity<Download>> download(@PathVariable String songName) {
        if (songName == null || songName.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return downloadService.requestDownload(songName)
                .map(saved -> ResponseEntity.status(HttpStatus.ACCEPTED).body(saved))
                .onErrorResume(error -> Mono.just(
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()));
    }

    @GetMapping("/downloads/active")
    Mono<ActiveDownloadsResponse> activeDownloads() {
        return activeDownloadRepository.findActive()
                .collectList()
                .map(downloads -> new ActiveDownloadsResponse(pollIntervalMs, downloads));
    }
}
