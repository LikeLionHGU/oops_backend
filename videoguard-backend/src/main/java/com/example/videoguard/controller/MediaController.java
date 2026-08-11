package com.example.videoguard.controller;

import com.example.videoguard.common.BusinessException;
import com.example.videoguard.common.ErrorCode;
import com.example.videoguard.domain.Video;
import com.example.videoguard.domain.VideoFrame;
import com.example.videoguard.repository.VideoFrameRepository;
import com.example.videoguard.service.VideoService;
import com.example.videoguard.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 영상 스트리밍과 프레임 이미지. (API 명세 7, 8)
 *
 * 바이너리 응답이라 공통 JSON 래퍼를 쓰지 않는다.
 */
@Tag(name = "2. 미디어", description = "영상 재생과 화면 캡처 이미지 (JSON 이 아니라 바이너리 응답)")
@Slf4j
@RestController
@RequestMapping("/api/v1/videos/{videoId}")
@RequiredArgsConstructor
public class MediaController {

    /** 한 번에 내려주는 최대 청크. 너무 크면 seek 반응이 느려진다. */
    private static final long CHUNK_SIZE = 1024 * 1024;

    private final VideoService videoService;
    private final VideoFrameRepository videoFrameRepository;
    private final StorageService storageService;

    /**
     * 영상 재생. HTTP Range 를 지원해야 타임라인 카드 클릭 시 해당 구간으로 바로 이동한다.
     * Range 헤더가 없으면 200 전체, 있으면 206 부분 응답.
     */
    @Operation(summary = "영상 재생",
            description = """
                    HTTP Range 를 지원한다. 브라우저가 알아서 구간 요청을 보내므로
                    프론트는 `<video src="/api/v1/videos/{id}/stream">` 로 쓰고
                    카드 클릭 시 `video.currentTime = event.startMs / 1000` 만 하면 된다.

                    - Range 헤더 없음 → 200, 전체
                    - Range 헤더 있음 → 206, 부분 (1MB 씩)

                    업로드한 영상만 재생된다. 유튜브 링크로 등록한 건 404 다.
                    """)
    @GetMapping("/stream")
    public ResponseEntity<Resource> stream(
            @PathVariable Long videoId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Video video = videoService.getEntity(videoId);
        if (!video.isStreamable()) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND,
                    "업로드된 영상만 스트리밍할 수 있습니다. 유튜브 영상은 원본 링크를 사용하세요.");
        }

        Path path = storageService.resolve(video.getStorageKey());
        if (!Files.isReadable(path)) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND, "영상 파일이 존재하지 않습니다.");
        }

        FileSystemResource resource = new FileSystemResource(path);
        long contentLength = sizeOf(path);
        MediaType mediaType = mediaTypeOf(path);

        if (rangeHeader == null || rangeHeader.isBlank()) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentLength(contentLength)
                    .body(resource);
        }

        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength)
                    .build();
        }
        if (ranges.isEmpty()) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength)
                    .build();
        }

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        long length = Math.min(CHUNK_SIZE, end - start + 1);

        ResourceRegion region = new ResourceRegion(resource, start, length);

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes %d-%d/%d".formatted(start, start + length - 1, contentLength))
                .contentLength(length)
                .body(new ResourceRegionResource(region));
    }

    @Operation(summary = "화면 캡처 이미지",
            description = "리포트의 `frameUrl` 이 가리키는 실제 이미지. `<img src={event.frameUrl}>` 로 쓰면 된다.")
    @GetMapping("/frames/{frameId}")
    public ResponseEntity<Resource> frame(@PathVariable Long videoId, @PathVariable Long frameId) {
        videoService.getEntity(videoId);

        VideoFrame frame = videoFrameRepository.findByIdAndVideoId(frameId, videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FRAME_NOT_FOUND));

        Path path = storageService.resolve(frame.getStorageKey());
        if (!Files.isReadable(path)) {
            throw new BusinessException(ErrorCode.FRAME_NOT_FOUND, "프레임 파일이 존재하지 않습니다.");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(frame.getContentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)))
                .body(new FileSystemResource(path));
    }

    /** 파일 크기. 읽을 수 없으면 404 로 바꿔서 던진다. */
    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            log.warn("파일 크기를 읽을 수 없습니다: {}", path, e);
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND, "영상 파일을 읽을 수 없습니다.");
        }
    }

    private MediaType mediaTypeOf(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".mov")) return MediaType.parseMediaType("video/quicktime");
        if (name.endsWith(".avi")) return MediaType.parseMediaType("video/x-msvideo");
        return MediaType.parseMediaType("video/mp4");
    }

    /**
     * ResourceRegion 을 Resource 로 감싸는 어댑터.
     * ResponseEntity<Resource> 시그니처를 유지하면서 부분 전송을 하기 위한 장치다.
     */
    private static class ResourceRegionResource extends org.springframework.core.io.AbstractResource {

        private final ResourceRegion region;

        ResourceRegionResource(ResourceRegion region) {
            this.region = region;
        }

        @Override
        public String getDescription() {
            return "ResourceRegion of " + region.getResource().getDescription();
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
            java.io.InputStream in = region.getResource().getInputStream();
            long skipped = 0;
            while (skipped < region.getPosition()) {
                long n = in.skip(region.getPosition() - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            return new LimitedInputStream(in, region.getCount());
        }

        @Override
        public long contentLength() {
            return region.getCount();
        }
    }

    /** 지정한 바이트 수만 읽고 끊어주는 스트림 */
    private static class LimitedInputStream extends java.io.FilterInputStream {

        private long remaining;

        LimitedInputStream(java.io.InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(length, remaining);
            int read = super.read(buffer, offset, toRead);
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
