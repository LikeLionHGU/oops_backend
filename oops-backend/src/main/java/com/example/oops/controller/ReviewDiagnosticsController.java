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
    private final com.example.oops.repository.TranscriptSegmentRepository transcripts;
    private final com.example.oops.transcript.SttReviewPlanner sttReviewPlanner;
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

    /** Same local-only gate as diagnostics; never re-transcribes or alters stored raw. */
    @GetMapping("/{videoId}/analysis/stt-review-plan")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ApiResponse<com.example.oops.transcript.SttReviewPlanner.Plan> sttReviewPlan(@PathVariable String videoId) {
        Long id = Ids.parse(videoId);
        var video = videos.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));
        var input = com.example.oops.analyzer.ReviewInput.from(transcripts.findByVideoIdOrderByStartMsAsc(id), java.util.List.of());
        long duration = video.durationMs() == null ? input.segments().stream().mapToLong(s -> s.endMs()).max().orElse(0)
                : video.durationMs();
        return ApiResponse.ok(sttReviewPlanner.plan(input, duration, store.find(id).orElse(null)));
    }
}
