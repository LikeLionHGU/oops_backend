package com.example.oops.analyzer;

/** Comparison-only guidance; not a production flag or an automatic verdict rule. */
final class UtteranceDirectionPolicy {
    static final String REVISION="utterance-direction-verification-20261016-1";
    static final String GUIDANCE="""
            # 발언 관계 보존
            먼저 원문에서 누가 무엇을 누구에게 제안·질문·요청하는지 구분하고 이유에도 그 방향을 유지한다.
            자기 공개 제안을 타인에게 공개하라는 요구로, 용어 풀이·인용을 실제 질문이나 동조로 바꾸지 않는다.
            질문·제안·거절·동의·실제 실행은 서로 다른 정보다. 한 줄로 다른 단계나 같은 화자를 추정하지 않는다.
            대화 상대·행동 주체·평가 대상을 혼동하지 않는다. 대상 근거가 부족하면 만들어 채우지 않는다.
            이는 행위 해석 제약이지 판정 규칙이 아니다. 자발적 제안도 자동 정상은 아니고 질문도 자동 논란은 아니다.
            확인된 원문만으로 검토 이유가 있으면 유지하고, 필수 관계가 불명확하면 그 누락을 명시한다.
            """;
    private UtteranceDirectionPolicy() {}
    static String comparison(String baseline) {
        String marker="# 후보 근거 검증\n";
        if(!baseline.startsWith(CandidateReviewEngine.verificationPrompt(0)) || !baseline.contains(marker)
                || baseline.contains(GUIDANCE)) throw new IllegalArgumentException("Unmodified verification baseline required");
        int offset=baseline.indexOf(marker)+marker.length();
        return baseline.substring(0,offset)+GUIDANCE+baseline.substring(offset);
    }
}
