package com.catacomb5099.naviseerr.download;

import com.catacomb5099.naviseerr.schema.slskd.SearchFile;
import com.catacomb5099.naviseerr.schema.slskd.SearchResponseItem;
import com.catacomb5099.naviseerr.services.slskd.SlskdService;
import com.catacomb5099.naviseerr.support.SlskdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.catacomb5099.naviseerr.support.DownloadTaskFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlbumSearchStepTest {

    private static final Duration BUDGET = Duration.ofSeconds(120);
    private static final UUID DOWNLOAD = UUID.randomUUID();

    private DownloadTaskRepository repository;
    private SlskdService slskd;
    private AlbumFolderPicker picker;
    private AlbumSearchStep step;
    private final DownloadTask song = DownloadTask.initial(DOWNLOAD, "v1", "Myrrhman - Talk Talk", T0);

    @BeforeEach
    void setUp() {
        repository = mock(DownloadTaskRepository.class);
        slskd = mock(SlskdService.class);
        picker = mock(AlbumFolderPicker.class);
        step = new AlbumSearchStep(repository, slskd, picker, new StallingSharers(Duration.ofHours(6)),
                Duration.ofSeconds(2), BUDGET);
        when(repository.waitingAlbumSongs(any(), any())).thenReturn(Flux.just(song));
        when(repository.saveAlbumSearch(any(), any(), any(), any())).thenReturn(Mono.just(1L));
        when(repository.releaseAlbumSongs(any(), any(), any(), any(), any())).thenReturn(Mono.just(1L));
    }

    private static AlbumSearch album(DownloadPhase phase, int tier) {
        return AlbumSearch.builder().downloadId(DOWNLOAD).phase(phase).searchTier(tier)
                .searchId(phase == DownloadPhase.SEARCH_POLL ? "s1" : null)
                .phaseEnteredAt(T0).nextAttemptAt(T0).title("Laughing Stock").artists(List.of("Talk Talk")).build();
    }

    @Test
    void theFirstWordingNamesTheArtist_theSecondIsTheTitleAlone_andACompilationOnlyHasItsTitle() {
        assertEquals(List.of("Laughing Stock - Talk Talk", "Laughing Stock"), album(DownloadPhase.SEARCH_INIT, 0).wordings());
        assertEquals(List.of("Driving"), album(DownloadPhase.SEARCH_INIT, 0).toBuilder().title("Driving")
                .artists(List.of("Various Artists")).build().wordings());
    }

    @Test
    void starting_postsTheSearch_andHoldsTheSongsForAnotherTwoBudgets() {
        when(slskd.searchResults("Laughing Stock - Talk Talk"))
                .thenReturn(Mono.just(SlskdFixtures.searchState("s1", false, "InProgress")));

        step.step(album(DownloadPhase.SEARCH_INIT, 0), Map.of(), T0.plusSeconds(5), "me").block();

        verify(repository).saveAlbumSearch(argThat(a -> a.phase() == DownloadPhase.SEARCH_POLL
                        && "s1".equals(a.searchId()) && a.phaseEnteredAt().equals(T0.plusSeconds(5))),
                eq("me"), eq(T0.plusSeconds(5)), eq(T0.plusSeconds(5).plus(BUDGET.multipliedBy(2))));
    }

    @Test
    void withEverySongAlreadyStartedOnItsOwn_nothingIsSearched() {
        when(repository.waitingAlbumSongs(any(), any())).thenReturn(Flux.empty());

        step.step(album(DownloadPhase.SEARCH_INIT, 0), Map.of(), T0, "me").block();

        verifyNoInteractions(slskd);
        verify(repository).releaseAlbumSongs(DOWNLOAD, "me", AlbumSearch.Outcome.NOTHING_TO_SEARCH, Map.of(), T0);
    }

    @Test
    void aSearchSlskdWillNotStart_releasesTheSongsToTheirOwnSearches() {
        when(slskd.searchResults(any())).thenReturn(Mono.error(SlskdFixtures.responseFailure(429)));

        step.step(album(DownloadPhase.SEARCH_INIT, 0), Map.of(), T0, "me").block();

        verify(repository).releaseAlbumSongs(DOWNLOAD, "me", AlbumSearch.Outcome.SEARCH_FAILED, Map.of(), T0);
    }

    @Test
    void aSearchStillRunning_isPolledAgain_withoutFetchingItsResponses() {
        step.step(album(DownloadPhase.SEARCH_POLL, 0),
                Map.of("s1", SlskdFixtures.searchState("s1", false, "InProgress")), T0.plusSeconds(4), "me").block();

        verify(repository).saveAlbumSearch(argThat(a -> a.phase() == DownloadPhase.SEARCH_POLL
                && a.nextAttemptAt().equals(T0.plusSeconds(6))), eq("me"), any(), isNull());
        verify(slskd, never()).getSearchWithResponses(any());
    }

    @Test
    void nobodyAnsweringTheArtistWording_triesTheTitleAlone() {
        when(slskd.getSearchWithResponses("s1"))
                .thenReturn(Mono.just(SlskdFixtures.searchStateWithResponses("s1", true, "Completed", List.of())));

        step.step(album(DownloadPhase.SEARCH_POLL, 0),
                Map.of("s1", SlskdFixtures.searchState("s1", true, "Completed")), T0.plusSeconds(4), "me").block();

        verify(repository).saveAlbumSearch(argThat(a -> a.phase() == DownloadPhase.SEARCH_INIT
                && a.searchTier() == 1 && a.searchId() == null), eq("me"), any(), isNull());
        verify(repository, never()).releaseAlbumSongs(any(), any(), any(), any(), any());
    }

    @Test
    void aWholeFolder_handsEachSongItsFile() {
        SearchFile file = new SearchFile("TALK TALK\\LAUGHING STOCK\\1-01 Myrrhman.mp3", 1L, 1L, false, "",
                Optional.of(320), Optional.of(333));
        SearchResponseItem peer = new SearchResponseItem(1, List.of(file), true, 0, List.of(), 0, 1, 1, "Baron53");
        when(slskd.getSearchWithResponses("s1"))
                .thenReturn(Mono.just(SlskdFixtures.searchStateWithResponses("s1", true, "Completed", List.of(peer))));
        when(picker.wholeFolders(any(), eq(List.of(song)), eq("Laughing Stock"), eq(List.of("Talk Talk")), any()))
                .thenReturn(List.of(new AlbumFolderPicker.Folder(peer, "TALK TALK\\LAUGHING STOCK",
                        Map.of(song.taskId(), file), 0)));

        step.step(album(DownloadPhase.SEARCH_POLL, 0),
                Map.of("s1", SlskdFixtures.searchState("s1", true, "Completed")), T0.plusSeconds(4), "me").block();

        verify(repository).releaseAlbumSongs(eq(DOWNLOAD), eq("me"), eq(AlbumSearch.Outcome.WHOLE_FOLDER),
                eq(Map.of(song.taskId(), List.of(DownloadCandidate.fromAlbumFolder(peer, file)))), eq(T0.plusSeconds(4)));
    }

    @Test
    void aSearchStuckRunningPastItsBudget_isJudgedOnWhatItFound() {
        // slskd leaves some searches "InProgress" for days; the budget ends the wait, not the album.
        when(slskd.getSearchWithResponses("s1")).thenReturn(Mono.just(SlskdFixtures.searchStateWithResponses(
                "s1", false, "InProgress", List.of(new SearchResponseItem(0, List.of(), true, 0, List.of(), 0, 1, 1, "x")))));
        when(picker.wholeFolders(any(), any(), any(), any(), any())).thenReturn(List.of());

        step.step(album(DownloadPhase.SEARCH_POLL, 0),
                Map.of("s1", SlskdFixtures.searchState("s1", false, "InProgress")), T0.plus(BUDGET), "me").block();

        verify(repository).releaseAlbumSongs(DOWNLOAD, "me", AlbumSearch.Outcome.NO_WHOLE_FOLDER, Map.of(), T0.plus(BUDGET));
    }
}
