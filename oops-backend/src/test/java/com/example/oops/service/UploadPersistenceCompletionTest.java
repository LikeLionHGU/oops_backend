package com.example.oops.service;

import com.example.oops.domain.Video;
import com.example.oops.repository.VideoRepository;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class UploadPersistenceCompletionTest {
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void completionStatusDeterminesCompensationAndUnknownPreservesFiles(int status) {
        var rows=mock(VideoRepository.class); var storage=mock(StorageService.class);
        var staged=mock(StorageService.StagedUpload.class); var video=mock(Video.class);
        when(rows.save(any())).thenReturn(video); when(video.getId()).thenReturn(1L);
        when(storage.promoteUpload(staged,1L)).thenReturn("videos/1/original.mp4");
        TransactionSynchronizationManager.initSynchronization();
        try {
            new UploadPersistenceService(rows,storage).persist(staged,"v1.mp4",null);
            verify(storage,never()).discardUpload(any()); verify(storage,never()).releaseUploadStaging(any());
            var callbacks=TransactionSynchronizationManager.getSynchronizations();
            assertThat(callbacks).hasSize(1);
            callbacks.get(0).afterCompletion(status);
            if(status==0) verify(storage).releaseUploadStaging(staged);
            else verify(storage,never()).releaseUploadStaging(any());
            if(status==1) verify(storage).discardUpload(staged);
            else verify(storage,never()).discardUpload(any());
            verify(staged).manageCompletion();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
