package com.catacomb5099.naviseerr.library;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Which of these YouTube songs do I already have, or is one on its way?" for a player such as Feishin,
 * which shows YouTube rows beside its own library: {@code GET /library/songs?ids=a,b,c}. Database and
 * file checks only, never a media-server call, so it is cheap enough for every page and search.
 */
@RestController
public class LibraryController {

    static final int MAX_IDS = 200;

    public enum State { IN_LIBRARY, ON_ITS_WAY }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SongState(State state, UUID downloadId) {}

    private final LibrarySongs songs;
    private final LibraryOrganiser organiser;
    private final Clock clock;

    public LibraryController(LibrarySongs songs, LibraryOrganiser organiser, Clock clock) {
        this.songs = songs;
        this.organiser = organiser;
        this.clock = clock;
    }

    @GetMapping("/library/songs")
    Mono<ResponseEntity<Object>> songs(@RequestParam List<String> ids) {
        List<String> distinct = ids.stream().map(String::strip).filter(id -> !id.isEmpty()).distinct().toList();
        if (distinct.isEmpty() || distinct.size() > MAX_IDS) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("message", "Ask for between 1 and " + MAX_IDS + " song ids.")));
        }
        Instant now = clock.instant();
        return songs.rows(distinct).collectList()
                .flatMap(rows -> filed(distinct, rows).map(found -> states(distinct, rows, found, organiser.cutoff(now))))
                .map(ResponseEntity::ok);
    }

    /** Per id (same order), a filed file of it still in the library, else null. Organiser off: any finished song counts, as the 409 does. */
    private Mono<List<String>> filed(List<String> ids, List<LibrarySongs.Row> rows) {
        if (!organiser.isEnabled()) {
            return Mono.just(ids.stream().map(id -> rows.stream()
                    .filter(r -> r.youtubeId().equals(id) && r.finished()).findFirst()
                    .map(r -> "").orElse(null)).toList());
        }
        List<LibraryOrganiser.FiledCopy> copies = new ArrayList<>();
        for (LibrarySongs.Row row : rows) {
            if (row.libraryPath() != null) {
                copies.add(new LibraryOrganiser.FiledCopy(ids.indexOf(row.youtubeId()) + 1, row.libraryPath()));
            }
        }
        return organiser.stillFiled(ids.size(), copies)
                .map(found -> found.stream().map(copy -> copy == null ? null : copy.libraryPath()).toList());
    }

    /**
     * IN_LIBRARY when a file of the song is still there; else ON_ITS_WAY when a row is still being worked on, or
     * finished so recently that the organiser may still file it; else the id is left out (not known: Download).
     */
    static Map<String, SongState> states(List<String> ids, List<LibrarySongs.Row> rows, List<String> filed, Instant cutoff) {
        Map<String, SongState> answer = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (filed.get(i) != null) {
                answer.put(id, new SongState(State.IN_LIBRARY, null));
                continue;
            }
            rows.stream()
                    .filter(r -> r.youtubeId().equals(id))
                    .filter(r -> !r.finished() || (r.libraryPath() == null && r.finishedAt() != null && r.finishedAt().isAfter(cutoff)))
                    .findFirst()
                    .ifPresent(r -> answer.put(id, new SongState(State.ON_ITS_WAY, r.downloadId())));
        }
        return answer;
    }
}
