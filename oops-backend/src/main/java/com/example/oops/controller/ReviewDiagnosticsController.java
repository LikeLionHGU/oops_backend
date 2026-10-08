package com.example.oops.controller;

import com.example.oops.common.*;
import com.example.oops.repository.VideoRepository;
import com.example.oops.service.ReviewDiagnosticsStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Must not be enabled on a public server before ownership checks are implemented. */
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oops.analysis", name = "review-diagnostics-enabled", havingValue = "true")
public class ReviewDiagnosticsController {
    private final VideoRepository videos;
    private final ReviewDiagnosticsStore store;
    public record Response(boolean available, String message, ReviewDiagnosticsStore.Snapshot snapshot) {}

    @GetMapping("/{videoId}/analysis/diagnostics")
    public ApiResponse<Response> diagnostics(@PathVariable String videoId) {
        Long id = Ids.parse(videoId);
        if (!videos.existsById(id)) throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
        var snapshot = store.find(id).orElse(null);
        return ApiResponse.ok(new Response(snapshot != null, snapshot == null
                ? "진단 기록이 없거나 만료되었습니다. 활성화 후 새로 분석한 영상만 조회할 수 있습니다."
                : "검증 전후의 구간별 판정 기록입니다. 최종 리포트나 정확도 지표가 아닙니다.", snapshot));
    }
}
