package com.catacomb5099.naviseerr.services.slskd;

import com.catacomb5099.naviseerr.schema.slskd.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SlskdDownloadProcessorTest {

    private final SlskdService slskdService = mock(SlskdService.class);

    private final SlskdDownloadProcessor slskdDownloadProcessor = new SlskdDownloadProcessor(slskdService);

    SearchResponseItem item1 = createSearchResponseItem("user1");
    SearchFile file1 = mock(SearchFile.class);
    SearchResponseItem item2 = createSearchResponseItem("user2");
    SearchFile file2 = mock(SearchFile.class);
    SearchResponseItem item3 = createSearchResponseItem("user3");
    SearchFile file3 = mock(SearchFile.class);
    SearchResponseItem item4 = createSearchResponseItem("user4");
    SearchFile file4 = mock(SearchFile.class);
    SearchResponseItem item5 = createSearchResponseItem("user5");
    SearchFile file5 = mock(SearchFile.class);


    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(slskdDownloadProcessor, "retryAttempts", 1);
        ReflectionTestUtils.setField(slskdDownloadProcessor, "maxPollAttempts", 3);
        ReflectionTestUtils.setField(slskdDownloadProcessor, "firstBackOffDuration", 10L);
        when(file1.getFilename()).thenReturn("file1.mp3");
        when(file2.getFilename()).thenReturn("file2.mp3");
        when(file3.getFilename()).thenReturn("file3.mp3");
        when(file4.getFilename()).thenReturn("file4.mp3");
        when(file5.getFilename()).thenReturn("file5.mp3");
    }

    @Test
    void pollWithNoFilesReturnsEmptyMono() {
        StepVerifier.create(slskdDownloadProcessor.pollUntilComplete(List.of()))
                .verifyComplete();
    }

    @Test
    void pollUntilComplete_Success_CallsEnqueueThenProgress() {
        List<Map.Entry<SearchResponseItem, SearchFile>> files = List.of(Map.entry(item1, file1));
        TransferedFile tf = createTransferedFile(item2.getUsername(), "123", TransferState.SUCCEEDED.name());

        QueueDownloadResponse qdr = new QueueDownloadResponse(List.of(tf), List.of());

        when(slskdService.enqueueDownload(anyString(), any())).thenReturn(Mono.just(qdr));
        when(slskdService.getDownloadProgress(anyString(), anyString())).thenReturn(Mono.just(tf));

        StepVerifier.create(slskdDownloadProcessor.pollUntilComplete(files))
                .expectNextMatches(result -> result.getId().equals("123"))
                .verifyComplete();

        verify(slskdService).enqueueDownload(anyString(), any());
        verify(slskdService).getDownloadProgress(any(), any());
    }

    @Test
    void pollUntilComplete_ProgressUntilSuccess() {
        List<Map.Entry<SearchResponseItem, SearchFile>> files = List.of(Map.entry(item1, file1));
        TransferedFile inProgress = createTransferedFile("user1", "123", TransferState.IN_PROGRESS.name());

        TransferedFile success = createTransferedFile("user1", "123", TransferState.SUCCEEDED.name());

        QueueDownloadResponse qdr = new QueueDownloadResponse(List.of(inProgress), List.of());

        when(slskdService.enqueueDownload(anyString(), any())).thenReturn(Mono.just(qdr));
        when(slskdService.getDownloadProgress(anyString(), anyString()))
                .thenReturn(Mono.just(inProgress))
                .thenReturn(Mono.just(inProgress))
                .thenReturn(Mono.just(success));

        StepVerifier.create(slskdDownloadProcessor.pollUntilComplete(files))
                .expectNext(success)
                .verifyComplete();
    }


    @ParameterizedTest
    @EnumSource(value = TransferState.class, names = {"CANCELLED", "TIMED_OUT", "ERRORED", "ABORTED", "REJECTED"})
    void pollUntilComplete_EnqueueFailureState_ReturnsErrorMono(TransferState failureState) {
        List<Map.Entry<SearchResponseItem, SearchFile>> files = List.of(Map.entry(item1, file1));

        when(slskdService.enqueueDownload(anyString(), any())).thenReturn(Mono.error(new RuntimeException("Enqueue failed with state: " + failureState.name())));

        StepVerifier.create(slskdDownloadProcessor.pollUntilComplete(files))
                .verifyError();
    }

    @ParameterizedTest
    @EnumSource(value = TransferState.class, names = {"CANCELLED", "TIMED_OUT", "ERRORED", "ABORTED", "REJECTED"})
    void pollUntilComplete_DownloadProgressFailureState_ReturnsErrorMono(TransferState failureState) {
        List<Map.Entry<SearchResponseItem, SearchFile>> files = List.of(Map.entry(item1, file1));
        TransferedFile inProgress = createTransferedFile("user1", "123", TransferState.IN_PROGRESS.name());

        TransferedFile failure = createTransferedFile("user1", "123", failureState.name());

        QueueDownloadResponse qdr = new QueueDownloadResponse(List.of(failure), List.of());

        when(slskdService.enqueueDownload(anyString(), any())).thenReturn(Mono.just(qdr));
        when(slskdService.getDownloadProgress(anyString(), anyString()))
                .thenReturn(Mono.just(inProgress))
                .thenReturn(Mono.just(failure));

        StepVerifier.create(slskdDownloadProcessor.pollUntilComplete(files))
                .verifyError();
    }


    @Test
    void pollUntilComplete_VerifyRetryCountsOnFailure() {
        List<Map.Entry<SearchResponseItem, SearchFile>> files = List.of(Map.entry(item1, file1));
        TransferedFile failure = createTransferedFile("user1", "123", TransferState.ERRORED.name());

        QueueDownloadResponse qdr = new QueueDownloadResponse(List.of(failure), List.of());

        when(slskdService.enqueueDownload(anyString(), any())).thenReturn(Mono.just(qdr));
        when(slskdService.getDownloadProgress(anyString(), anyString())).thenReturn(Mono.just(failure));

        StepVerifier.create(slskdDownloadProcessor.pollUntilComplete(files))
                .verifyError();

        // total attempts = initial (1) + retryAttempts (assigned 1 in setUp) = 2
        verify(slskdService, times(2)).enqueueDownload(anyString(), any());
        verify(slskdService, times(2)).getDownloadProgress(anyString(), any());
    }


    @Test
    void pollUntilComplete_ContinuesToNextCandidateOnFailure() {
        List<Map.Entry<SearchResponseItem, SearchFile>> files = List.of(Map.entry(item1, file1), Map.entry(item2, file2));

        TransferedFile failTf = createTransferedFile("user1", "2", TransferState.ERRORED.name());

        TransferedFile successTf = createTransferedFile("user2", "2", TransferState.SUCCEEDED.name());

        when(slskdService.enqueueDownload(eq("user1"), any())).thenReturn(Mono.just(new QueueDownloadResponse(List.of(failTf), List.of())));
        when(slskdService.getDownloadProgress(eq("user1"), any())).thenReturn(Mono.just(failTf));

        when(slskdService.enqueueDownload(eq("user2"), any())).thenReturn(Mono.just(new QueueDownloadResponse(List.of(successTf), List.of())));
        when(slskdService.getDownloadProgress(eq("user2"), any())).thenReturn(Mono.just(successTf));

        StepVerifier.create(slskdDownloadProcessor.pollUntilComplete(files))
                .expectNext(successTf)
                .verifyComplete();
    }

    @Test
    void pollUntilEveryFailure() {
        List<Map.Entry<SearchResponseItem, SearchFile>> files = List.of(Map.entry(item1, file1), Map.entry(item2, file2), Map.entry(item3, file3), Map.entry(item4, file4), Map.entry(item5, file5));

        TransferedFile failTf = createTransferedFile("user1", "2", TransferState.ERRORED.name());

        when(slskdService.enqueueDownload(any(), any())).thenReturn(Mono.just(new QueueDownloadResponse(List.of(failTf), List.of())));
        when(slskdService.getDownloadProgress(any(), any())).thenReturn(Mono.just(failTf));


        StepVerifier.create(slskdDownloadProcessor.pollUntilComplete(files))
                .verifyError();
        verify(slskdService, times(10)).enqueueDownload(anyString(), any());
        verify(slskdService, times(10)).getDownloadProgress(anyString(), any());
    }


    private SearchResponseItem createSearchResponseItem(String username) {
        return new SearchResponseItem(0, List.of(), false, 0, List.of(), 0, 0, 0, username);
    }

    private TransferedFile createTransferedFile(String username, String id, String state) {
        return new TransferedFile(id, username, "", "", 0L, 0L, state, "", "", "", "", 0L, 0.0f, 0L, "", 0.0f, "");
    }


}
