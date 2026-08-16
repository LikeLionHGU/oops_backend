package com.example.oops.dto;

import com.example.oops.domain.CandidateType;
import com.example.oops.domain.ReviewActionType;

import java.util.List;
import java.util.Map;

/**
 * 검수 품질 지표. 명세 §18-4.
 *
 * "AI 가 몇 건 찾았나" 가 아니라
 * "그중 제작자가 실제로 쓸모 있다고 봤나" 를 보기 위한 값이다.
 *
 * 이 숫자가 없으면 품질을 느낌으로만 말하게 된다.
 * 프롬프트를 고칠 때마다 좋아졌는지 나빠졌는지 알 방법이 없다.
 */
public record ReviewMetricsResponse(

        /** 지금까지 만들어진 검토 후보 총계 */
        int totalCandidates,

        /** 그중 제작자가 처리한 것 */
        int reviewedCandidates,

        /** 액션별 개수 */
        Map<ReviewActionType, Integer> actions,

        /**
         * 처리한 것 중 쓸모 있다고 본 비율 (CONFIRMED + EDITED + HOLD).
         * 처리한 게 없으면 null — 0.0 으로 주면 "다 쓸모없다" 로 읽힌다.
         */
        Double acceptanceRate,

        /** 실제로 편집까지 이어진 비율 (EDITED) */
        Double editingActionRate,

        /** 오탐 비율 (NOT_USEFUL) */
        Double falsePositiveRate,

        /**
         * 후보 유형별 오탐 현황.
         * 어느 분석기가 쓰레기를 만드는지 여기서 드러난다.
         */
        List<ByType> byCandidateType
) {
    public record ByType(
            CandidateType candidateType,
            int total,
            int reviewed,
            int notUseful,
            Double falsePositiveRate
    ) {}
}
