package com.catacomb5099.naviseerr.settings;

import com.catacomb5099.naviseerr.learning.SearchLearningRepository;
import com.catacomb5099.naviseerr.learning.SearchLearningSender;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code /settings/search-learning}: the switch "Help improve search", off on every install until the person says yes
 * (Apple's "Share iPhone Analytics", Home Assistant's analytics opt-in). On means songs that finish from now on are
 * sent; the history before is never sent. {@code configured} is false when the install file sets no address
 * ({@code SEARCH_LEARNING_URL}): the web app then shows the switch disabled.
 */
@RestController
@RequestMapping("/settings/search-learning")
public class SearchLearningSettingsController {

    static final String NOT_CONFIGURED = "This install has no address to send search reports to (SEARCH_LEARNING_URL).";

    public record Change(boolean on) {}

    /**
     * {@code since}: when it went on, null while off; {@code sendsTo}: the receiver's host[:port], null with no address;
     * {@code problem}: the last send's problem, null when fine.
     */
    public record View(boolean on, boolean configured, String sendsTo, Instant since, long waiting, long sent, String problem) {}

    private final InstallSettingsRepository settings;
    private final SearchLearningRepository reports;
    private final SearchLearningSender sender;

    public SearchLearningSettingsController(InstallSettingsRepository settings, SearchLearningRepository reports,
                                            SearchLearningSender sender) {
        this.settings = settings;
        this.reports = reports;
        this.sender = sender;
    }

    @GetMapping
    Mono<View> get() {
        return settings.searchLearningSince().map(Optional::of).defaultIfEmpty(Optional.empty())
                .zipWith(reports.waiting().zipWith(reports.sent()))
                .map(t -> new View(t.getT1().isPresent(), sender.isConfigured(), sender.sendsTo(), t.getT1().orElse(null),
                        t.getT2().getT1(), t.getT2().getT2(), sender.problem()));
    }

    /** 200 with the saved switch; 400 when switched on with no address to send to. Off is always allowed. */
    @PutMapping
    Mono<View> save(@RequestBody Change body) {
        if (body.on() && !sender.isConfigured()) {
            return Mono.error(new SettingsProblem(NOT_CONFIGURED));
        }
        return settings.setSearchLearning(body.on()).then(get());
    }
}
