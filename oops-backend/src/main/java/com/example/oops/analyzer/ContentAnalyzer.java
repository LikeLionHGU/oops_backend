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

    /**
     * 영상이 길어지면 AI 호출 수도 같이 늘어나는지.
     *
     * 대부분의 분석기는 **아니다.** 상한이 걸려 있다.
     *   entity-check    확인할 주장 6개까지    → 길어도 7회
     *   context-check   주제 8개까지           → 길어도 9회
     *   context-lexicon 표현 24개까지, 12개씩  → 길어도 2회
     *
     * 대본을 창 단위로 훑는 분석기만 길이에 비례한다.
     * 20줄씩 자르므로 대본이 두 배면 호출도 두 배다.
     *
     * 이 값이 필요한 이유는 요청 한도 예측 때문이다.
     * 1분짜리로 시험한 결과를 그냥 60배 하면 상한이 걸린 분석기까지
     * 같이 늘어나서 실제보다 몇 배 크게 나온다.
     * 짧은 영상일수록 고정 호출의 비중이 커서 오차가 심해진다.
     */
    default boolean scalesWithLength() {
        return false;
    }
}
