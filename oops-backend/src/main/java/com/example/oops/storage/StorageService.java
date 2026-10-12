package com.example.oops.storage;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.config.OopsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * 파일 저장소.
 *
 * 바깥에는 절대경로 대신 storageKey(예: videos/12/original.mp4)만 노출한다.
 * 나중에 S3 로 바꿀 때 이 클래스만 갈아끼우면 되게 하기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    /** API 명세 2-1 의 MVP 지원 형식 */
    private static final List<String> ALLOWED_EXTENSIONS = List.of("mp4", "mov", "avi");

    private final OopsProperties properties;

    public Path root() {
        return Paths.get(properties.storage().location()).toAbsolutePath().normalize();
    }

    public Path resolve(String storageKey) {
        Path resolved = root().resolve(storageKey).normalize();
        // 경로 조작으로 저장소 밖 파일을 읽으려는 시도를 막는다
        if (!resolved.startsWith(root())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "잘못된 저장소 경로입니다.");
        }
        return resolved;
    }

    /** API 명세 2-1: 500MB 상한. Spring 설정과 별개로 코드에서도 검증한다. */
    private static final long MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024;

    public void validateVideoFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "영상 파일이 필요합니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.MAX_UPLOAD_SIZE_EXCEEDED,
                    "업로드 가능한 파일 크기(500MB)를 초과했습니다. (%dMB)"
                            .formatted(file.getSize() / (1024 * 1024)));
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_VIDEO_FORMAT,
                    "지원하지 않는 영상 형식입니다. (%s)".formatted(String.join(", ", ALLOWED_EXTENSIONS)));
        }
    }

    /** An opaque ownership handle; cleanup never recursively deletes a video directory. */
    public static final class StagedUpload {
        private final Path directory;
        private final Path file;
        private Path promotedDirectory;
        private boolean completionManaged;
        private StagedUpload(Path directory, Path file) { this.directory = directory; this.file = file; }
        public boolean isCompletionManaged() { return completionManaged; }
        public void manageCompletion() { completionManaged = true; }
    }

    /** Heavy copy happens before any database transaction or visible Video row. */
    public StagedUpload stageVideo(MultipartFile file) {
        validateVideoFile(file);
        StagedUpload staged = null;
        try {
            Path parent = resolve("videos/.incoming");
            rejectLinkedPath(parent);
            Files.createDirectories(parent);
            Path directory = Files.createTempDirectory(parent, "upload-");
            staged = new StagedUpload(directory, directory.resolve("original." + extensionOf(file.getOriginalFilename())));
            try (var in = file.getInputStream()) {
                Files.copy(in, staged.file);
            }
            return staged;
        } catch (IOException | RuntimeException e) {
            if (staged != null) discardUpload(staged);
            log.error("임시 업로드 저장 실패", e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다.");
        }
    }

    public String stagedStorageKey(StagedUpload staged) { return toStorageKey(staged.file); }

    /** Only a same-filesystem atomic rename is allowed inside the short DB commit. */
    public String promoteUpload(StagedUpload staged, Long videoId) {
        if (videoId == null || videoId <= 0 || staged.promotedDirectory != null) {
            throw new IllegalArgumentException("Invalid upload promotion");
        }
        Path directory = resolve("videos/" + videoId);
        try {
            rejectLinkedPath(staged.file);
            rejectLinkedPath(directory);
            Files.createDirectory(directory); // Never replace another video's existing files.
            staged.promotedDirectory = directory;
            Path target = directory.resolve(staged.file.getFileName());
            Files.move(staged.file, target, StandardCopyOption.ATOMIC_MOVE);
            return toStorageKey(target);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "영상 파일 등록에 실패했습니다.");
        }
    }

    private void rejectLinkedPath(Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(root())) throw new IOException("Upload outside storage root");
        Path cursor = root();
        if (Files.isSymbolicLink(cursor)) throw new IOException("Linked storage root");
        for (Path part : root().relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Linked upload path");
        }
    }

    /** Best-effort compensation only for paths created by this ownership handle. */
    public void discardUpload(StagedUpload staged) {
        try {
            if (staged.promotedDirectory != null) {
                rejectLinkedPath(staged.promotedDirectory.resolve(staged.file.getFileName()));
                Files.deleteIfExists(staged.promotedDirectory.resolve(staged.file.getFileName()));
                Files.deleteIfExists(staged.promotedDirectory);
            }
            releaseUploadStaging(staged);
        } catch (IOException | RuntimeException e) {
            log.warn("[upload] 소유 파일 정리 실패; 수동 확인 필요: {}", staged.directory, e);
        }
    }

    public void releaseUploadStaging(StagedUpload staged) {
        try {
            rejectLinkedPath(staged.file);
            Files.deleteIfExists(staged.file);
            Files.deleteIfExists(staged.directory);
        } catch (IOException | RuntimeException e) {
            log.warn("[upload] 임시 파일 정리 실패; 수동 확인 필요: {}", staged.directory, e);
        }
    }

    /** OCR 프레임을 보관할 폴더 (절대경로). 분석 서버에 넘겨준다. */
    public Path frameDir(Long videoId) {
        return resolve("frames/%d".formatted(videoId));
    }

    private static final String OCR_COMPLETE = ".ocr-complete";
    private static final String ANALYSIS_RELEASED = ".analysis-released";

    public record JobFrames(Long videoId, Long jobId) {}

    /** Only folders in the new job-scoped layout are eligible; never follow links. */
    private Path safeJobFrameDir(Long videoId, Long jobId) throws IOException {
        if (videoId == null || jobId == null || videoId <= 0 || jobId <= 0) {
            throw new IOException("Invalid frame owner");
        }
        Path directory = frameDir(videoId).resolve("jobs/" + jobId);
        Path cursor = root();
        if (Files.isSymbolicLink(cursor)) throw new IOException("Linked storage root");
        for (Path part : root().relativize(directory)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Linked frame path");
        }
        return directory;
    }

    /** Called only after a valid OCR response, not after timeout or connection failure. */
    public void markOcrComplete(Long videoId, Long jobId) {
        try {
            Path directory = safeJobFrameDir(videoId, jobId);
            if (!Files.isDirectory(directory)) return; // No retained images, nothing to collect.
            Files.writeString(directory.resolve(OCR_COMPLETE), "ocr-response-received\n",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            // A missing marker means conservative retention, not failed analysis.
            log.warn("[frame-retention] 완료 표시 실패 videoId={} jobId={}: {}", videoId, jobId, e.getMessage());
        }
    }

    /** Called from the pipeline finally block; cancellation alone does not release files. */
    public void releaseJobFrames(Long videoId, Long jobId) {
        try {
            Path directory = safeJobFrameDir(videoId, jobId);
            if (!Files.isRegularFile(directory.resolve(OCR_COMPLETE), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            Files.writeString(directory.resolve(ANALYSIS_RELEASED), "analysis-returned\n",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.warn("[frame-retention] 해제 표시 실패 videoId={} jobId={}: {}", videoId, jobId, e.getMessage());
        }
    }

    /** At most 1000 eligible candidates; database checks happen under the video lock. */
    public List<JobFrames> expiredJobFrames(java.time.Instant threshold) {
        return expiredJobFrames(threshold, 0);
    }

    public List<JobFrames> expiredJobFrames(java.time.Instant threshold, long offset) {
        Path frames = resolve("frames");
        if (!Files.isDirectory(frames) || Files.isSymbolicLink(root()) || Files.isSymbolicLink(frames)) return List.of();
        try (var paths = Files.walk(frames, 3)) {
            return paths.filter(p -> p.getNameCount() == frames.getNameCount() + 3)
                    .map(p -> {
                        try {
                            Path relative = frames.relativize(p);
                            if (!relative.getName(1).toString().equals("jobs")
                                    || !relative.getName(0).toString().matches("[1-9][0-9]*")
                                    || !relative.getName(2).toString().matches("[1-9][0-9]*")) return null;
                            var owner = new JobFrames(Long.valueOf(relative.getName(0).toString()), Long.valueOf(relative.getName(2).toString()));
                            return hasExpiredMarker(safeJobFrameDir(owner.videoId(), owner.jobId()), threshold) ? owner : null;
                        } catch (IOException | NumberFormatException e) { return null; }
                    }).filter(java.util.Objects::nonNull).skip(Math.max(0, offset)).limit(1000).toList();
        } catch (IOException e) {
            log.warn("[frame-retention] 후보 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean hasExpiredMarker(Path directory, java.time.Instant threshold) throws IOException {
        Path marker = directory.resolve(ANALYSIS_RELEASED);
        return Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(directory.resolve(OCR_COMPLETE), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.getLastModifiedTime(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(threshold);
    }

    /** The caller must hold the video row lock and exclude running/referenced work. */
    public boolean deleteExpiredJobFrames(Long videoId, Long jobId, java.time.Instant threshold) throws IOException {
        Path directory = safeJobFrameDir(videoId, jobId);
        if (!hasExpiredMarker(directory, threshold)) return false;
        List<Path> entries;
        try (var paths = Files.walk(directory)) { entries = paths.toList(); }
        // Even an internal link is rejected, rather than guessing the intended target.
        if (entries.stream().anyMatch(Files::isSymbolicLink)) return false;
        for (Path entry : entries.stream().sorted(java.util.Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
            // Keep the marker until last so interrupted cleanup remains discoverable.
            if (!entry.equals(directory) && !entry.equals(directory.resolve(OCR_COMPLETE))
                    && !entry.equals(directory.resolve(ANALYSIS_RELEASED))) Files.deleteIfExists(entry);
        }
        Files.deleteIfExists(directory.resolve(OCR_COMPLETE));
        Files.deleteIfExists(directory.resolve(ANALYSIS_RELEASED));
        Files.deleteIfExists(directory);
        return true;
    }

    /** 분석 서버가 돌려준 절대경로를 storageKey 로 되돌린다. */
    public String toStorageKey(Path absolutePath) {
        return root().relativize(absolutePath.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    /** 영상 하나에 딸린 파일을 전부 지운다. 원본과 프레임 모두. */
    public void deleteVideoFiles(Long videoId) {
        deleteDirectory(resolve("videos/%d".formatted(videoId)));
        deleteDirectory(frameDir(videoId));
    }

    /** 원본 영상만 지우고 OCR 프레임과 분석 결과는 남긴다. */
    public void deleteSourceFile(Long videoId) {
        deleteDirectory(resolve("videos/%d".formatted(videoId)));
    }

    /** 저장소가 지금 몇 바이트를 쓰고 있는지 */
    public long usedBytes() {
        Path root = root();
        if (!Files.isDirectory(root)) return 0;
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            log.warn("저장소 용량 계산 실패", e);
            return 0;
        }
    }

    /** 원본 업로드를 저장할 수 있는지 확인한다. health check에서 사용한다. */
    public boolean isWritable() {
        try {
            Files.createDirectories(root());
            return Files.isWritable(root());
        } catch (IOException e) {
            log.warn("저장소 쓰기 가능 여부 확인 실패", e);
            return false;
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var paths = Files.walk(dir)) {
            // 깊은 것부터 지워야 디렉터리가 비워진 뒤 삭제된다
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 한둘 못 지워도 나머지는 계속 진행한다
                        }
                    });
        } catch (IOException e) {
            log.warn("파일 정리 실패 {}", dir, e);
        }
    }

    public void deleteFrames(Long videoId) {
        Path dir = frameDir(videoId);
        if (!Files.isDirectory(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 지우지 못해도 분석에는 영향이 없다
                        }
                    });
        } catch (IOException e) {
            log.warn("프레임 정리 실패 videoId={}", videoId, e);
        }
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
