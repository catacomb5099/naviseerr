package com.catacomb5099.naviseerr.download;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class DownloadController {

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
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
}
