package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.util.LastFMAPIMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class SearchService {
    private final LastFMService lastFMService;

    public SearchService(LastFMService lastFMService) {
        this.lastFMService = lastFMService;
    }

    @RequestMapping("/search/{query}")
    Mono<String> search(@PathVariable String query) {
        return lastFMService.getResults(query, LastFMAPIMethod.TRACK_SEARCH);
    }
}
