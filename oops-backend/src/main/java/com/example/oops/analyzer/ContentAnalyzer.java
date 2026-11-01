package com.example.oops.analyzer;

import com.example.oops.domain.RiskFinding;

import java.util.List;
import java.util.Optional;

/**
 * 논란 탐지기 하나의 계약.
 *
 * 새 분석기를 붙이는 방법:
 *   1. 이 인터페이스를 구현한 @Component 를 만든다
 *   2. key() 값을 application.yml 의 oops.analysis.enabled-analyzers 에 추가한다
 * 오케스트레이터 코드는 건드릴 필요가 없다.
 */
public interface ContentAnalyzer {

    /** application.yml 에서 on/off 할 때 쓰는 식별자 */
    String key();

    /** 진행률 표시에 쓰는 사람이 읽는 이름 */
    String displayName();

    /** 이 영상에 대해 돌릴 수 있는 상태인지 (예: 자막이 있어야 함) */
    boolean supports(AnalysisContext context);

    List<RiskFinding> analyze(AnalysisContext context);

    /** supports() 가 false 일 때 사용자에게 알릴 이유. 없으면 파이프라인 기본 문구를 쓴다. */
    default Optional<String> unsupportedReason(AnalysisContext context) {
        return Optional.empty();
    }

    /** Consume an optional notice when an analyzer intentionally processed only part of its input. */
    default Optional<String> consumeCoverageNotice(AnalysisContext context) {
        return Optional.empty();
    }

    /**
     * 방금 analyze() 의 내부 기록(후보·제외·중단·검색어·판정)을 꺼낸다. 같은 스레드에서 analyze() 직후에 부른다.
     * 파이프라인이 결과와 함께 analysis_trace 에 저장한다. (redesign-v3 8단계)
     */
    default Optional<AnalyzerTrace> consumeTrace(AnalysisContext context) {
        return Optional.empty();
    }

    /** Bounded input is a caveat, not evidence that processing failed or a response was missing. */
    default Optional<String> consumeContextLimitNotice(AnalysisContext context) {
        return Optional.empty();
    }
}
