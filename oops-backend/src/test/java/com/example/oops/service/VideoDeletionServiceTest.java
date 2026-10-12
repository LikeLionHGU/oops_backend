package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoDeletionServiceTest {
    private final VideoRepository videos = mock(VideoRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final VideoDeletionService service = new VideoDeletionService(videos, jobs,
            mock(AnalysisReportRepository.class), mock(RiskFindingRepository.class), mock(ReviewReferenceRepository.class),
            mock(ReviewActionRepository.class), mock(AnalysisCoverageRepository.class), mock(ScreenTextRepository.class),
            mock(TranscriptSegmentRepository.class), mock(VideoFrameRepository.class), storage,
            mock(ReviewDiagnosticsStore.class), mock(ExpressionOccurrenceRepository.class));

    @AfterEach void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    private void requestDeletion() {
        when(videos.findByIdForUpdate(1L)).thenReturn(Optional.of(
                Video.builder().sourceType(SourceType.UPLOAD).filename("fixture.mp4").build()));
        TransactionSynchronizationManager.initSynchronization();
        service.delete(1L);
        verify(videos).findByIdForUpdate(1L);
        verifyNoInteractions(storage);
    }

    @Test void sourceAndFramesAreDeletedOnlyAfterCommit() {
        requestDeletion();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(storage).deleteVideoFiles(1L);
    }

    @Test void rollbackNeverDeletesUserFiles() {
        requestDeletion();
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(storage);
    }

    @Test void runningJobIsRejectedBeforeWaitingOnVideoLock() {
        when(jobs.existsByVideoIdAndStatusIn(eq(1L), anyList())).thenReturn(true);
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(com.example.oops.common.BusinessException.class);
        verifyNoInteractions(videos, storage);
    }

    @Test void retryBetweenInitialCheckAndLockIsRejected() {
        when(jobs.existsByVideoIdAndStatusIn(eq(1L), anyList())).thenReturn(false, true);
        when(videos.findByIdForUpdate(1L)).thenReturn(Optional.of(
                Video.builder().sourceType(SourceType.UPLOAD).filename("fixture.mp4").build()));
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(com.example.oops.common.BusinessException.class);
        verify(videos).findByIdForUpdate(1L);
        verifyNoInteractions(storage);
    }
}
