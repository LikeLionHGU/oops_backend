package com.example.oops.analyzer;

/** Opt-in reading-order change inside the existing call; no new output contract or semantic auto-correction. */
final class SituationFirstVerification {
    static final String VERSION = "situation-first-verification-v1";
    private static final String OPENING = """
            candidates는 탐색 모델의 가설이며 사실·판정 근거가 아니다. 후보마다 허용된 segmentIds의
              원문을 다시 읽고 정상 해석과 대조한다. 후보의 이유에 동의할 의무가 없다.
            """;
    private static final String EVIDENCE_FIRST = """
            먼저 proposedEvidence의 평가·대상·앞뒤 연결을 실제 raw에서 확인한 뒤 허용 문맥을 대조한다.
            """;
    private static final String READING_ORDER = """
            # 상황 이해 → 비판 해석 → 정상 해석 대조
            1. 후보 이유·과거 사례의 결론을 적용하기 전에 각 후보가 허용한 raw를 시간순으로 읽는다.
              무엇을 설명·평가하는지, 누가 무엇을 하거나 누구에게 요청하는지를 원문으로 구별한다.
              뜻풀이·인용 속 질문과 현재 상대에게 하는 질문, 자기 설명·자기 제안과 타인에게 하는 요구를
              혼동하지 않는다. 화자 신원·동일인·동의·거절·실행은 시간 인접이나 생략된 주어로 추정하지 않는다.
            2. 그 상황에서 누가 어떤 취급을 받는다는 비판인지 실제 표현과 연결한다.
              성적 소재와 성적 대상화, 시설 부재와 지역 가치 절하를 구별한다. 소재만으로 피해·수치심을 만들지 않는다.
              대상 공격이 없어도 구체적인 위협·신체 훼손 비유 등 표현 자체의 검토 이유는 별도로 확인한다.
            3. 뜻풀이·자기서사·리뷰 등 정상 해석과 대조하고도 남는 구체적 근거로 판정한다.
              정상 해석이 있으면 무조건 PASS도, 누군가 불쾌할 수 있으면 무조건 경고도 아니다.
              해석에 필수인 정보가 실제로 없으면 그 정보를 명시한다. 화자 불명 자체를 경고 근거로 삼지 않는다.
            candidates와 proposedEvidence는 탐색 가설·읽기 안내이지 사실이나 확정된 행위 관계가 아니다.
              과거 사례도 현재 원문의 관계를 대신하지 않는다. 후보 이유에 동의할 의무가 없다.
            별도 사고 과정·새 JSON 필드는 반환하지 않는다. 기존 reason과 alternativeInterpretation에
              판정에 필요한 원문 연결과 대조 결과만 간결하게 적고 나머지 출력 계약은 그대로 따른다.
            """;
    private SituationFirstVerification() {}

    static String prompt(String baseline) {
        int boundary = baseline.indexOf("# 대상 필드 형식 예시");
        if (!baseline.startsWith(CandidateReviewPrompts.POLICY) || boundary < 0)
            throw new IllegalArgumentException("Known verification prompt required");
        String body = baseline.substring(0, boundary);
        if (body.indexOf(OPENING) < 0 || body.indexOf(OPENING) != body.lastIndexOf(OPENING)
                || body.indexOf(EVIDENCE_FIRST) < 0 || body.indexOf(EVIDENCE_FIRST) != body.lastIndexOf(EVIDENCE_FIRST))
            throw new IllegalArgumentException("Known reading-order instructions required");
        return body.replace(OPENING, READING_ORDER).replace(EVIDENCE_FIRST,
                "허용 raw 전체의 상황을 확인한 뒤 proposedEvidence가 그 연결을 정확하게 나타내는지 대조한다.\n")
                + baseline.substring(boundary);
    }
}
