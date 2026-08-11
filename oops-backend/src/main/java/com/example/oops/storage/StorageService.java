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

    public void validateVideoFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "영상 파일이 필요합니다.");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_VIDEO_FORMAT,
                    "지원하지 않는 영상 형식입니다. (%s)".formatted(String.join(", ", ALLOWED_EXTENSIONS)));
        }
    }

    /** 영상 원본을 videos/{videoId}/original.{ext} 로 저장하고 storageKey 를 돌려준다. */
    public String storeVideo(Long videoId, MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        String storageKey = "videos/%d/original.%s".formatted(videoId, extension);
        Path target = resolve(storageKey);

        try {
            Files.createDirectories(target.getParent());
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return storageKey;
        } catch (IOException e) {
            log.error("파일 저장 실패 videoId={}", videoId, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다.");
        }
    }

    /** OCR 프레임을 보관할 폴더 (절대경로). 분석 서버에 넘겨준다. */
    public Path frameDir(Long videoId) {
        return resolve("frames/%d".formatted(videoId));
    }

    /** 분석 서버가 돌려준 절대경로를 storageKey 로 되돌린다. */
    public String toStorageKey(Path absolutePath) {
        return root().relativize(absolutePath.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
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
