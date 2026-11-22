package com.example.oops.analyzer;

/** Offline comparison only. Does not alter production policy or the completed comparison 40. */
final class BanterBoundaryPolicy {
    static final String REVISION = "2026-10-15-banter-boundary-comparison-41";

    private BanterBoundaryPolicy() {}

    static final String ORIGINAL_BOUNDARY = """
            정상적인 리뷰·비교·추억·농담·비판적 인용과 대조하되, 정상 해석이 가능하다는 이유만으로
              원문으로 설명되는 별도의 모욕·조롱을 지우지 않는다. 농담도 자동 면책은 아니다.
            """;

    static final String BOUNDARY = """
            무엇을 평가하는지, 인용한 평가를 현재 발화가 채택하는지 먼저 확인한다.
            특정 행동에 대한 강한 비판을 그 강도만으로 인격 공격으로 바꾸지 않는다.
            반대로 사람 자체의 가치·외모를 깎아내리거나 배제를 부추기는 구체적 표현은 한 번이어도 검토할 수 있다.
            게임·역할극·상호 공방은 원문에 드러난 행동과 대화 연결로 판단한다.
            부정적 평가어가 있다는 이유만으로 그 연결과 별개인 모욕이 입증됐다고 하지 않는다.
            농담·인용은 자동 면책이 아니다. 장난이라는 주장이나 대화가 이어진 사실만으로 합의를 추정하지 않는다.
            중단 요청 뒤의 공격·직접적인 폄하·위협 등 별도 근거가 있으면 그 표현과 대상을 구체적으로 설명한다.
            인용한 공격을 비판·거부하는 서술을 현재 화자의 공격으로 바꾸지 않되, 실제 동조·추가 공격은 구별한다.
            정상 해석과 대조해 구체적인 검토 근거가 없으면 PASS다. 판단에 필수인 대상·문맥이 실제로 빠져 있으면
              UNCERTAIN으로 부족한 정보를 명시한다. 단독 문장·농담이라는 형식만으로 어느 판정도 자동 선택하지 않는다.
            화자 구분·웃음·억양·친밀도·합의를 원문 밖에서 보충하지 않는다.
            """;

    static String replacePolicy(String baseline) {
        if (baseline == null || !baseline.startsWith(CandidateReviewPrompts.POLICY))
            throw new IllegalArgumentException("Known production policy prefix required");
        String policy = CandidateReviewPrompts.POLICY;
        int offset = policy.indexOf(ORIGINAL_BOUNDARY);
        if (offset < 0 || offset != policy.lastIndexOf(ORIGINAL_BOUNDARY))
            throw new IllegalStateException("Exactly one known boundary required");
        return policy.substring(0, offset) + BOUNDARY
                + policy.substring(offset + ORIGINAL_BOUNDARY.length())
                + baseline.substring(policy.length());
    }

    record Pair(String baselineSystem, String comparisonSystem, String input) {}

    static Pair pair(String baselineSystem, String input) {
        if (input == null || input.isBlank())
            throw new IllegalArgumentException("Frozen input required");
        return new Pair(baselineSystem, replacePolicy(baselineSystem), input);
    }
}
