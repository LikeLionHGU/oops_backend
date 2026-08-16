package com.example.oops.controller;

import com.example.oops.common.ApiResponse;
import com.example.oops.dto.ReviewMetricsResponse;
import com.example.oops.service.ReviewMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "3. 지표", description = "검수 품질 측정")
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final ReviewMetricsService metricsService;

    @Operation(summary = "검수 품질 지표",
            description = """
                    제작자가 검토 후보를 실제로 어떻게 처리했는지 집계한다. (명세 18-4)

                    - `acceptanceRate`: 처리한 것 중 쓸모 있다고 본 비율
                    - `editingActionRate`: 실제 편집까지 이어진 비율
                    - `falsePositiveRate`: 오탐 비율
                    - `byCandidateType`: 유형별 오탐 현황. 오탐이 많은 순서다

                    아직 아무도 처리하지 않았으면 비율은 `null` 이다.
                    `0.0` 으로 주면 "측정했더니 0%" 로 읽히는데 실제로는 "아직 안 봤다" 다.

                    팀 내부 확인용이라 프론트 화면에 안 써도 된다.
                    """)
    @GetMapping
    public ApiResponse<ReviewMetricsResponse> metrics() {
        return ApiResponse.ok("지표 조회 성공", metricsService.collect());
    }
}
