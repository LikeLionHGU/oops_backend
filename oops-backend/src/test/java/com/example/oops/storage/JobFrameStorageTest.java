package com.example.oops.storage;

import com.example.oops.config.OopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class JobFrameStorageTest {
    @TempDir Path temporary;
    private StorageService storage() {
        return new StorageService(new OopsProperties(new OopsProperties.Storage(temporary.toString(), 0, 0), null, null));
    }
    private Path fixture(long video, long job) throws Exception {
        Path directory=temporary.resolve("frames/" + video + "/jobs/" + job);
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/frame.jpg"), "fixture");
        return directory;
    }
    private void expire(Path directory) throws Exception {
        Files.setLastModifiedTime(directory.resolve(".analysis-released"), FileTime.from(Instant.now().minusSeconds(100)));
    }
    @Test void deletesOnlyExpiredAcknowledgedJobDirectoryAndPreservesOtherFiles() throws Exception {
        var storage=storage();
        Path old=fixture(1, 10), other=fixture(1, 11);
        Path original=temporary.resolve("videos/1/original.mp4");
        Files.createDirectories(original.getParent()); Files.writeString(original, "original");
        storage.markOcrComplete(1L, 10L); storage.releaseJobFrames(1L, 10L); expire(old);
        assertThat(storage.expiredJobFrames(Instant.now())).containsExactly(new StorageService.JobFrames(1L, 10L));
        assertThat(storage.deleteExpiredJobFrames(1L, 10L, Instant.now())).isTrue();
        assertThat(old).doesNotExist(); assertThat(other.resolve("nested/frame.jpg")).exists(); assertThat(original).exists();
    }
    @Test void missingOrFreshMarkerAndLegacyDirectoryAreNotCollected() throws Exception {
        var storage=storage(); Path directory=fixture(1, 10);
        assertThat(storage.deleteExpiredJobFrames(1L, 10L, Instant.now())).isFalse();
        storage.markOcrComplete(1L, 10L);
        assertThat(storage.deleteExpiredJobFrames(1L, 10L, Instant.now())).isFalse();
        storage.releaseJobFrames(1L, 10L);
        assertThat(storage.deleteExpiredJobFrames(1L, 10L, Instant.now().minusSeconds(10))).isFalse();
        Files.writeString(temporary.resolve("frames/1/legacy.jpg"), "legacy");
        assertThat(storage.expiredJobFrames(Instant.now().minusSeconds(10))).isEmpty();
        assertThat(directory.resolve("nested/frame.jpg")).exists();
    }
    @Test void rejectsLinkedAncestorAndInternalLinkWithoutTouchingTheirTargets() throws Exception {
        var storage=storage(); Path directory=fixture(1, 10);
        Path external=temporary.resolve("external"); Files.createDirectories(external);
        Files.writeString(external.resolve("keep.jpg"), "keep");
        storage.markOcrComplete(1L, 10L); storage.releaseJobFrames(1L, 10L); expire(directory);
        Files.createSymbolicLink(directory.resolve("link"), external);
        assertThat(storage.deleteExpiredJobFrames(1L, 10L, Instant.now())).isFalse();
        assertThat(external.resolve("keep.jpg")).exists();
        Files.createSymbolicLink(temporary.resolve("frames/2"), external);
        assertThatThrownBy(() -> storage.deleteExpiredJobFrames(2L, 10L, Instant.now())).isInstanceOf(java.io.IOException.class);
        assertThat(storage.expiredJobFrames(Instant.now())).doesNotContain(new StorageService.JobFrames(2L, 10L));
    }
    @Test void invalidIdsCannotTargetStorageRoot() {
        assertThatThrownBy(() -> storage().deleteExpiredJobFrames(0L, 1L, Instant.now())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> storage().deleteExpiredJobFrames(1L, -1L, Instant.now())).isInstanceOf(java.io.IOException.class);
    }
    @Test void noOcrAcknowledgementMeansNoReleaseOrCollection() throws Exception {
        Path directory=fixture(1, 10); var storage=storage();
        storage.releaseJobFrames(1L, 10L);
        assertThat(directory.resolve(".analysis-released")).doesNotExist();
        assertThat(storage.expiredJobFrames(Instant.now())).isEmpty();
        assertThat(directory.resolve("nested/frame.jpg")).exists();
    }
}
