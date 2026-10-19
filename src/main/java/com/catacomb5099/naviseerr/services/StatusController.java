package com.catacomb5099.naviseerr.services;

import com.catacomb5099.naviseerr.download.LibraryOrganiser;
import com.catacomb5099.naviseerr.schema.slskd.SlskdOptions;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService;
import com.catacomb5099.naviseerr.services.ytmusic.YtMusicService.Readiness;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code GET /status}: is this install's Soulseek client logged in? The one newcomer question the app
 * could not answer before -- a wrong or taken username left the web app silent until a download
 * failed two minutes later. The web app polls it with the downloads feed and shows a strip while
 * {@code loggedIn} is false. Since 09-10-2026 it also says whether the music library can be written
 * to ({@code library}): a wrong or unmounted folder used to show only as WARN lines in the log while
 * the web app said "Downloaded". Since 19-10-2026 also whether YouTube Music can be reached
 * ({@code youtubeMusic}): search and new downloads both wait on it, and until then the web app could
 * only say "Couldn't load" page by page, never why.
 */
@RestController
public class StatusController {

    /** {@code state} when slskd itself does not answer: not a Soulseek state, so it cannot be mistaken for one. */
    static final String UNREACHABLE = "UNREACHABLE";
    /** Per dependency call: a down dependency is an answer, and the strip must not wait a 15 s timeout for it. */
    private static final Duration BUDGET = Duration.ofSeconds(3);

    private final SlskdService slskdService;
    private final LibraryOrganiser organiser;
    private final YtMusicService ytMusicService;

    public StatusController(SlskdService slskdService, LibraryOrganiser organiser, YtMusicService ytMusicService) {
        this.slskdService = slskdService;
        this.organiser = organiser;
        this.ytMusicService = ytMusicService;
    }

    /** Always 200: a down slskd is an answer, not an error, so the strip never has to guess. */
    @GetMapping("/status")
    Mono<StatusView> status() {
        // The configured name from slskd's options: known before any login, which is when the strip
        // shows it. Not knowing it (an error or a stall on that call alone) is null, never a failed
        // status: each call has its own budget, so only /server can make slskd UNREACHABLE.
        Mono<Optional<String>> username = slskdService.getOptions()
                .timeout(BUDGET)
                .map(o -> Optional.ofNullable(o.soulseek()).map(SlskdOptions.Soulseek::username).filter(u -> !u.isBlank()))
                .onErrorReturn(Optional.empty());
        Mono<SoulseekStatus> soulseek = Mono.zip(slskdService.getServerState().timeout(BUDGET), username)
                .map(t -> new SoulseekStatus(t.getT1().isConnected(), t.getT1().isLoggedIn(), t.getT1().getState(), null,
                        t.getT2().orElse(null)))
                .onErrorResume(e -> Mono.just(new SoulseekStatus(false, false, UNREACHABLE, reason(e), null)));
        // The adapter's cached probe, with its own budget: the adapter not answering at all is a
        // different sentence from the adapter saying YouTube is away, and the strip wants neither to
        // wait on the other dependency.
        Mono<Readiness> youtubeMusic = ytMusicService.readiness().timeout(BUDGET)
                .onErrorResume(e -> Mono.just(new Readiness(false, "ytmusic-adapter not reachable: " + reason(e))));
        return Mono.zip(soulseek, youtubeMusic)
                .map(t -> new StatusView(t.getT1(),
                        new LibraryStatus(organiser.isEnabled(), organiser.libraryRoot(), organiser.libraryProblem()),
                        t.getT2()));
    }

    private static String reason(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** {@code youtubeMusic} (19-10-2026): see {@link YtMusicService#readiness()}; the web app shows a third amber strip while {@code reachable} is false. */
    public record StatusView(SoulseekStatus soulseek, LibraryStatus library, Readiness youtubeMusic) {}

    /**
     * {@code enabled} false when no library is configured or its paths overlap slskd's folders (then
     * {@code root} and {@code problem} are null; the log says why); {@code problem} one plain sentence while the folder is missing or cannot be written to.
     */
    public record LibraryStatus(boolean enabled, String root, String problem) {}

    /**
     * {@code state} is slskd's own word: "None" before it has ever tried to connect, "Disconnected",
     * "Connected, LoggedIn"; {@code detail} only with UNREACHABLE; {@code username} the Soulseek
     * account this install logs in with, null when slskd cannot be reached or does not say.
     */
    public record SoulseekStatus(boolean connected, boolean loggedIn, String state, String detail, String username) {}
}
