package com.example.oops.service;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.common.*;
import com.example.oops.repository.VideoRepository;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:upload-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=1","spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=1000"})
@ActiveProfiles("test")
class UploadPreparationTransactionTest {
    @TempDir static Path root;
    @DynamicPropertySource static void storageRoot(DynamicPropertyRegistry registry) { registry.add("oops.storage.location",()->root.toString()); }
    @Autowired VideoService videos;
    @Autowired StorageService storage;
    @Autowired UploadPersistenceService persistence;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean VideoRepository rows;
    @MockitoBean AnalysisServerClient media;

    private MockMultipartFile file() { return new MockMultipartFile("file","v1.mp4","video/mp4",new byte[]{1,2,3}); }
    private void assertNoIncoming() throws Exception {
        if (!Files.exists(root.resolve("videos/.incoming"))) return;
        try(var paths=Files.list(root.resolve("videos/.incoming"))) { assertThat(paths.count()).isZero(); }
    }
    @Test void copyAndProbeDoNotHoldConnectionAndVideoAppearsOnlyAfterPreparation() throws Exception {
        long before=rows.count();
        var file=new MockMultipartFile("file","v1.mp4","video/mp4",new byte[]{1,2,3}) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                try(var c=dataSource.getConnection()) { assertThat(c.isClosed()).isFalse(); }
                catch(java.sql.SQLException e) { throw new AssertionError(e); }
                return super.getInputStream();
            }
        };
        when(media.probe(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try(var c=dataSource.getConnection()) { assertThat(c.isClosed()).isFalse(); }
            assertThat(rows.count()).isEqualTo(before);
            com.example.oops.domain.Video input=call.getArgument(0);
            assertThat(input.getId()).isNull(); assertThat(storage.resolve(input.getStorageKey())).exists();
            return Optional.of(new AnalysisServerClient.ProbeResponse(90,null,5400,true));
        });
        var video=videos.createFromUpload(file,"GENERAL");
        assertThat(video.getStorageKey()).isEqualTo("videos/"+video.getId()+"/original.mp4");
        assertThat(storage.resolve(video.getStorageKey())).hasBinaryContent(new byte[]{1,2,3});
        assertThat(rows.findById(video.getId())).isPresent(); assertNoIncoming();
    }
    @Test void durationRejectionLeavesNoRowOrFile() throws Exception {
        long before=rows.count();
        when(media.probe(any())).thenReturn(Optional.of(new AnalysisServerClient.ProbeResponse(6000,null,5400,false)));
        assertThatThrownBy(()->videos.createFromUpload(file(),"GENERAL")).isInstanceOf(BusinessException.class)
                .satisfies(e->assertThat(((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.MAX_VIDEO_DURATION_EXCEEDED));
        assertThat(rows.count()).isEqualTo(before); assertNoIncoming();
    }
    @Test void probeUnavailablePreservesExistingAcceptancePolicy() throws Exception {
        when(media.probe(any())).thenReturn(Optional.empty());
        var video=videos.createFromUpload(file(),null);
        assertThat(rows.findById(video.getId())).isPresent(); assertThat(storage.resolve(video.getStorageKey())).exists();
        assertNoIncoming();
    }
    @Test void dbFlushFailureRollsBackRowAndPromotedFile() throws Exception {
        long before=rows.count();
        when(media.probe(any())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("forced flush failure")).when(rows).flush();
        assertThatThrownBy(()->videos.createFromUpload(file(),null)).isInstanceOf(IllegalStateException.class);
        assertThat(rows.count()).isEqualTo(before); assertNoIncoming();
        try(var paths=Files.list(root.resolve("videos"))) {
            assertThat(paths.filter(p->!p.getFileName().toString().equals(".incoming")).count()).isEqualTo(before);
        }
    }
    @Test void outerTransactionIsRejectedBeforeCopyOrProbe() {
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->videos.createFromUpload(file(),null)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(media);
    }
    @Test void explicitRollbackRemovesOnlyNewUpload() throws Exception {
        var staged=storage.stageVideo(file()); long before=rows.count();
        var key=new TransactionTemplate(manager).execute(tx->{
            var video=persistence.persist(staged,"v1.mp4",null);
            tx.setRollbackOnly(); return video.getStorageKey();
        });
        assertThat(rows.count()).isEqualTo(before); assertThat(storage.resolve(key)).doesNotExist(); assertNoIncoming();
    }
}
