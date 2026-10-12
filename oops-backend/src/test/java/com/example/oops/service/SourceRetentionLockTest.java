package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.VideoRepository;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class SourceRetentionLockTest {
    @Autowired VideoRepository videos;
    @Autowired SourceRetentionService retention;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean StorageService storage;

    @Test void lockedRetryStatusChangeIsVisibleBeforeAnySourceDeletion() throws Exception {
        var tx = new TransactionTemplate(manager);
        Long id = tx.execute(s -> {
            var v = Video.builder().sourceType(SourceType.UPLOAD).filename("lock-fixture.mp4").build();
            v.updateStatus(AnalysisStatus.FAILED);
            return videos.saveAndFlush(v).getId();
        });
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var retry = pool.submit(() -> tx.executeWithoutResult(s -> {
                var v = videos.findByIdForUpdate(id).orElseThrow();
                v.updateStatus(AnalysisStatus.PROCESSING);
                videos.flush();
                held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lock timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var purge = pool.submit(() -> {
                attempted.countDown();
                return retention.purgeIfExpired(id, LocalDateTime.now().plusHours(1));
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> purge.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verifyNoInteractions(storage);
            release.countDown();
            retry.get(5, TimeUnit.SECONDS);
            assertThat(purge.get(5, TimeUnit.SECONDS)).isFalse();
            verifyNoInteractions(storage);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
