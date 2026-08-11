package com.example.videoguard.fusion;

import com.example.videoguard.domain.RiskFinding;

import java.util.Comparator;

/** 논란 후보 정렬 기준을 한 곳에서 관리한다. */
public final class FindingOrder {

    private FindingOrder() {
    }

    /** 우선순위 내림차순 → 같으면 영상 앞쪽부터 */
    public static Comparator<RiskFinding> byPriority() {
        Comparator<RiskFinding> byPriorityDesc =
                Comparator.comparingInt((RiskFinding f) -> f.getPriority()).reversed();
        return byPriorityDesc.thenComparingLong(RiskFinding::getStartMs);
    }

    /** 영상 시간순 */
    public static Comparator<RiskFinding> byTime() {
        return Comparator.comparingLong((RiskFinding f) -> f.getStartMs());
    }
}
