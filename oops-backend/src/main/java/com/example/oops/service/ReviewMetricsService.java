package com.example.oops.service;

import com.example.oops.domain.CandidateType;
import com.example.oops.domain.ReviewAction;
import com.example.oops.domain.ReviewActionType;
import com.example.oops.domain.RiskFinding;
import com.example.oops.dto.ReviewMetricsResponse;
import com.example.oops.repository.ReviewActionRepository;
import com.example.oops.repository.RiskFindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 검수 품질 지표 집계. (명세 §18-4)
 *
 * 오탐이 어디서 나오는지 숫자로 본다.
 * "요즘 좀 이상한 게 뜨는 것 같다" 로는 무엇을 고쳐야 할지 알 수 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewMetricsService {

    private final RiskFindingRepository findingRepository;
    private final ReviewActionRepository actionRepository;

    public ReviewMetricsResponse collect() {
        List<RiskFinding> findings = findingRepository.findAll();
        List<ReviewAction> actions = actionRepository.findAll();

        Map<ReviewActionType, Integer> counts = new EnumMap<>(ReviewActionType.class);
        for (ReviewActionType type : ReviewActionType.values()) {
            counts.put(type, 0);
        }

        // findingId → 그 후보에 대한 처리
        Map<Long, ReviewActionType> byFinding = new HashMap<>();
        for (ReviewAction action : actions) {
            counts.merge(action.getAction(), 1, Integer::sum);
            byFinding.put(action.getFinding().getId(), action.getAction());
        }

        int reviewed = byFinding.size();
        int notUseful = counts.get(ReviewActionType.NOT_USEFUL);

        return new ReviewMetricsResponse(
                findings.size(),
                reviewed,
                counts,
                ratio(reviewed - notUseful, reviewed),
                ratio(counts.get(ReviewActionType.EDITED), reviewed),
                ratio(notUseful, reviewed),
                breakdown(findings, byFinding)
        );
    }

    /** 후보 유형별로 오탐이 얼마나 나오는지 */
    private List<ReviewMetricsResponse.ByType> breakdown(
            List<RiskFinding> findings, Map<Long, ReviewActionType> byFinding) {

        Map<CandidateType, int[]> stats = new EnumMap<>(CandidateType.class);

        for (RiskFinding f : findings) {
            CandidateType type = CandidateType.from(f.getCategory(), f.getEventType());
            int[] row = stats.computeIfAbsent(type, k -> new int[3]);   // total, reviewed, notUseful
            row[0]++;

            ReviewActionType action = byFinding.get(f.getId());
            if (action != null) {
                row[1]++;
                if (action == ReviewActionType.NOT_USEFUL) {
                    row[2]++;
                }
            }
        }

        List<ReviewMetricsResponse.ByType> result = new ArrayList<>();
        stats.forEach((type, row) -> result.add(new ReviewMetricsResponse.ByType(
                type, row[0], row[1], row[2], ratio(row[2], row[1]))));

        // 오탐이 많은 유형을 위에 둔다. 먼저 손봐야 할 곳이다.
        result.sort((a, b) -> Integer.compare(b.notUseful(), a.notUseful()));
        return result;
    }

    /**
     * 분모가 0이면 null 을 준다.
     *
     * 0.0 으로 주면 "측정했더니 0%" 로 읽히는데 실제로는 "아직 아무도 안 봤다" 다.
     * 이 프로젝트에서 반복해서 문제가 됐던 구분이다.
     */
    private Double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return Math.round(numerator * 1000.0 / denominator) / 1000.0;
    }
}
