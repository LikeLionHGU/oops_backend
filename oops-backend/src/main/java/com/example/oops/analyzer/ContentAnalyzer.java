package com.example.oops.analyzer;

import com.example.oops.domain.RiskFinding;

import java.util.List;

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
}
