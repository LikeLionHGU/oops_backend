package com.example.oops.analyzer;

/** Observability inside one verification response. Validates provenance/shape, never speech-act truth. */
final class SourceUnderstandingContract {
    static final String VERSION = "source-understanding-v1";
    private static final String JSON_START = "JSON: {\"verifications\":[{\"candidateId\":\"요청 후보 ID\",\"assessment\":{";
    private static final String PREVIOUS_OUTPUT = """
            별도 사고 과정·새 JSON 필드는 반환하지 않는다. 기존 reason과 alternativeInterpretation에
              판정에 필요한 원문 연결과 대조 결과만 간결하게 적고 나머지 출력 계약은 그대로 따른다.
            """;
    private static final String OUTPUT = """
            각 검증 항목은 assessment 전에 sourceUnderstanding을 반환한다. 별도 사고 과정이 아니라
              현재 원문을 어떤 발화 행위로 읽었는지에 대한 짧은 분류와 근거 ID만 기록한다.
            sourceUnderstanding={"acts":[{"segmentId":"허용 원문 ID","actType":"아래 유형","contextIds":[]}]}.
              anchor의 행위를 반드시 포함하고, 그 해석을 결정하는 질문·설명·연결 발언만 추가한다.
              모든 원문을 나열하지 않는다. contextIds에는 그 해석에 필요한 다른 허용 raw ID만 넣는다.
              단일 줄에 여러 행위가 실제로 있으면 서로 다른 유형을 함께 기록할 수 있다. 같은 ID·유형은 반복하지 않는다.
            actType: TERM_EXPLANATION(말의 뜻풀이), QUOTED_SPEECH(인용·재현), DIRECT_REQUEST(현재 타인에게 요구·질문),
              SELF_DESCRIPTION(자기 경험·상태 설명), SELF_OFFER(자신의 행동을 제안), EVALUATION(대상·상황 평가),
              OTHER(다른 명확한 행위), UNRESOLVED(제공 원문으로 행위 해석이 불명확).
              뜻을 설명한 질문문과 실제 상대에게 묻는 행위를 구분한다. 문장부호·성적 단어만으로 분류하지 않는다.
              자기 제안을 타인 요구로 바꾸거나, 제안이 실행·동의됐다고 가정하지 않는다.
            분류는 판정이 아니다. 뜻풀이·인용·자기 제안도 별도 모욕·위협 근거가 있으면 검토할 수 있다.
              DIRECT_REQUEST라고 자동 경고하지 않으며 UNRESOLVED 자체도 자동 경고·UNCERTAIN 사유가 아니다.
              원문에서 무엇이 부족하며 판정에 필수인지 별도로 확인한다.
            기존 assessment의 reason에는 분류와 판정을 연결하는 원문 근거를 간결히 적는다.
              행위 분류와 판정 설명이 다르면 실제 raw를 재확인하되 필드 간 형식을 맞추려고 원문을 바꾸지 않는다.
              기존 assessment·reviewBasis(요청된 경우)의 인용·대조·대상·판정 계약은 유지한다.
            """;
    private SourceUnderstandingContract() {}
    static String prompt(String baseline) {
        String ordered = SituationFirstVerification.prompt(baseline);
        int boundary = ordered.indexOf("# 대상 필드 형식 예시");
        String body = ordered.substring(0, boundary);
        if (body.indexOf(PREVIOUS_OUTPUT) < 0 || body.indexOf(PREVIOUS_OUTPUT) != body.lastIndexOf(PREVIOUS_OUTPUT)
                || body.indexOf(JSON_START) < 0 || body.indexOf(JSON_START) != body.lastIndexOf(JSON_START))
            throw new IllegalArgumentException("Known situation-first output instructions required");
        // Keep the output example consistent with the added sibling field. References remain byte-identical.
        body = body.replace(PREVIOUS_OUTPUT, OUTPUT).replace(
                JSON_START,
                "JSON: {\"verifications\":[{\"candidateId\":\"요청 후보 ID\",\"sourceUnderstanding\":{\"acts\":[{\"segmentId\":\"anchorId\",\"actType\":\"실제 행위 유형\",\"contextIds\":[]}]},\"assessment\":{");
        return body + ordered.substring(boundary);
    }
    static String failure(CandidateReviewEngine.Candidate candidate, SourceUnderstanding understanding, boolean enabled) {
        if (!enabled) return understanding == null ? null : "UNEXPECTED_SOURCE_UNDERSTANDING";
        return understanding == null ? "SOURCE_UNDERSTANDING_REQUIRED" : understanding.failure(candidate);
    }
    static CandidateReviewEngine.Validation validate(CandidateReviewEngine.Candidate candidate,
            CandidateReviewEngine.Verification response, ReviewInput input, int limit, boolean finalBasis, boolean enabled) {
        if (response == null || !candidate.candidateId().equals(response.candidateId()))
            return FinalReviewBasisContract.validate(candidate, response, input, limit, finalBasis);
        String failure = failure(candidate, response.sourceUnderstanding(), enabled);
        return failure == null ? FinalReviewBasisContract.validate(candidate, response, input, limit, finalBasis)
                : new CandidateReviewEngine.Validation(null, failure);
    }
}
