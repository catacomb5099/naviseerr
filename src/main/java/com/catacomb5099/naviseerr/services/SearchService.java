package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.util.LastFMAPIMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class SearchService {
    private final LastFMService lastFMService;
    private final SlskdService slskdService;

    public SearchService(LastFMService lastFMService, SlskdService slskdService) {
        this.lastFMService = lastFMService;
        this.slskdService = slskdService;
    }

    @RequestMapping("/search/{query}")
    Mono<String> search(@PathVariable String query) {
        return lastFMService.getResults(query, LastFMAPIMethod.TRACK_SEARCH);
    }

    @RequestMapping("/download/{query}")
    Mono<String> download(@PathVariable String query) {
        return slskdService.getResults(query);
    }
}
