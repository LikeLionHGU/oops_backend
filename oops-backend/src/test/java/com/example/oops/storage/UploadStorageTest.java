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
    private MockMultipartFile file() { return new MockMultipartFile("file","v1.mp4","video/mp4",VideoBytes.mp4(1,2,3)); }
    private MockMultipartFile named(String name, byte[] content) { return new MockMultipartFile("file",name,"video/mp4",content); }

    @Test void renamedNonVideoIsRejectedBeforeAnythingIsStored() {
        for (var fake : java.util.List.of(named("fake.mp4","hello world, not a video".getBytes()),
                named("short.mov",new byte[]{0,0,0}), named("riff.mp4",VideoBytes.AVI_HEADER),
                named("box.avi",VideoBytes.MP4_HEADER))) {
            assertThatThrownBy(() -> storage().stageVideo(fake)).isInstanceOf(com.example.oops.common.BusinessException.class)
                    .extracting("errorCode").isEqualTo(com.example.oops.common.ErrorCode.UNSUPPORTED_VIDEO_FORMAT);
        }
        assertThat(root.resolve("videos/.incoming")).doesNotExist();
    }
    @Test void containerSignaturesMatchingTheExtensionAreAccepted() {
        byte[] quickTime = {0,0,0,8,'w','i','d','e'};
        for (var real : java.util.List.of(named("a.mp4",VideoBytes.MP4_HEADER), named("b.mov",quickTime),
                named("c.MOV",VideoBytes.MP4_HEADER), named("d.avi",VideoBytes.AVI_HEADER))) {
            assertThatCode(() -> storage().validateVideoFile(real)).doesNotThrowAnyException();
        }
    }

    @Test void promotesAndCleansOnlyOwnedFiles() throws Exception {
        var storage=storage(); var first=storage.stageVideo(file()); var other=storage.stageVideo(file());
        var otherPath=storage.resolve(storage.stagedStorageKey(other));
        var key=storage.promoteUpload(first,1L);
        assertThat(key).isEqualTo("videos/1/original.mp4");
        storage.releaseUploadStaging(first);
        assertThat(storage.resolve(key)).hasBinaryContent(VideoBytes.mp4(1,2,3));
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
        var file=new MockMultipartFile("file","bad.mp4","video/mp4",VideoBytes.MP4_HEADER) {
            @Override public InputStream getInputStream() {
                return new InputStream() { int reads;
                    @Override public int read() throws IOException {
                        if (reads<VideoBytes.MP4_HEADER.length) return VideoBytes.MP4_HEADER[reads++] & 0xff;
                        if (++reads>VideoBytes.MP4_HEADER.length+2) throw new IOException("copy failed");
                        return 1;
                    }
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
