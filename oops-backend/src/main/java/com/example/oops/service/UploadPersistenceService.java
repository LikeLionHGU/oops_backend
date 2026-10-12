package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.VideoRepository;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publish the Video only after preparation; the only filesystem write here is atomic rename. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadPersistenceService {
    private final VideoRepository videos;
    private final StorageService storage;

    @Transactional
    public Video persist(StorageService.StagedUpload staged, String filename, String genre) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) storage.discardUpload(staged);
                else if (status == STATUS_COMMITTED) storage.releaseUploadStaging(staged);
                else log.error("[upload] DB 커밋 결과 불명확; 파일 보존 후 수동 확인 필요");
            }
        });
        staged.manageCompletion();
        var video = videos.save(Video.builder().sourceType(SourceType.UPLOAD)
                .filename(filename).title(filename).genre(ContentGenre.fromOrDefault(genre, null)).build());
        video.assignStorageKey(storage.promoteUpload(staged, video.getId()));
        videos.flush();
        return video;
    }
}
