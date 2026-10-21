package com.catacomb5099.naviseerr.learning;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /search-learning/reports}: the reports of your own successful searches, newest first, in exactly the
 * shape a send would carry ({@code {schema, reports}}). Behind the login like every other route; works whatever the
 * "Help improve search" switch says, so it is both the export and the "see what is sent" view.
 */
@RestController
public class SearchLearningController {

    /** The report shape's version; a receiver keys on it if the shape ever changes. */
    public static final int SCHEMA = 1;

    public record Reports(int schema, List<SearchReport> reports) {}

    private final SearchLearningRepository repository;

    public SearchLearningController(SearchLearningRepository repository) {
        this.repository = repository;
    }

    /** {@code before}: page back with the last report's {@code finishedAt}. 400 for a limit outside 1-1000. */
    @GetMapping("/search-learning/reports")
    Mono<ResponseEntity<Reports>> reports(@RequestParam(required = false) Instant before,
                                          @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > 1000) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return repository.newest(before, limit).collectList().map(list -> ResponseEntity.ok(new Reports(SCHEMA, list)));
    }
}
