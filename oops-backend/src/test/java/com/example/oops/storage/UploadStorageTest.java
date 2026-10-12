package com.example.oops.storage;

import com.example.oops.config.OopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class UploadStorageTest {
    @TempDir Path root;
    private StorageService storage() { return new StorageService(new OopsProperties(new OopsProperties.Storage(root.toString(),0,0),null,null)); }
    private MockMultipartFile file() { return new MockMultipartFile("file","v1.mp4","video/mp4",new byte[]{1,2,3}); }

    @Test void promotesAndCleansOnlyOwnedFiles() throws Exception {
        var storage=storage(); var first=storage.stageVideo(file()); var other=storage.stageVideo(file());
        var otherPath=storage.resolve(storage.stagedStorageKey(other));
        var key=storage.promoteUpload(first,1L);
        assertThat(key).isEqualTo("videos/1/original.mp4");
        storage.releaseUploadStaging(first);
        assertThat(storage.resolve(key)).hasBinaryContent(new byte[]{1,2,3});
        storage.discardUpload(first);
        assertThat(storage.resolve(key)).doesNotExist(); assertThat(otherPath).exists();
        storage.discardUpload(first); // idempotent compensation
    }
    @Test void existingFinalDirectoryIsNeverOverwrittenOrDeleted() throws Exception {
        var storage=storage(); var staged=storage.stageVideo(file());
        Path original=root.resolve("videos/1/original.mp4");
        Files.createDirectories(original.getParent()); Files.writeString(original,"preserve");
        assertThatThrownBy(() -> storage.promoteUpload(staged,1L)).isInstanceOf(com.example.oops.common.BusinessException.class);
        storage.discardUpload(staged);
        assertThat(original).hasContent("preserve");
    }
    @Test void interruptedCopyCleansItsPartialFile() throws Exception {
        var file=new MockMultipartFile("file","bad.mp4","video/mp4",new byte[]{1}) {
            @Override public InputStream getInputStream() {
                return new InputStream() { int reads;
                    @Override public int read() throws IOException { if (++reads>2) throw new IOException("copy failed"); return 1; }
                };
            }
        };
        assertThatThrownBy(() -> storage().stageVideo(file)).isInstanceOf(com.example.oops.common.BusinessException.class);
        try (var children=Files.list(root.resolve("videos/.incoming"))) { assertThat(children.count()).isZero(); }
    }
    @Test void linkedParentCannotWriteOrDeleteExternalFiles() throws Exception {
        Files.createDirectories(root.resolve("outside")); Files.createDirectories(root.resolve("videos"));
        Path keep=root.resolve("outside/keep.mp4"); Files.writeString(keep,"keep");
        Files.createSymbolicLink(root.resolve("videos/.incoming"),root.resolve("outside"));
        assertThatThrownBy(() -> storage().stageVideo(file())).isInstanceOf(com.example.oops.common.BusinessException.class);
        assertThat(keep).hasContent("keep");
    }
}
