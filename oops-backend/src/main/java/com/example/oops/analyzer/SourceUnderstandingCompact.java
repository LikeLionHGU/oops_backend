package com.example.oops.analyzer;

import java.util.List;

/** Single canonical act/relation list. Provenance validation is not semantic consistency validation. */
final class SourceUnderstandingCompact {
    static final String VERSION="source-understanding-relations-v3";
    private static final String EXAMPLE="\"sourceUnderstanding\":{\"relations\":[{\"segmentId\":\"anchorId\",\"actType\":\"실제 행위 유형\",\"relation\":\"관계 유형\",\"respondsToId\":null,\"about\":\"실제 대화 대상 종류\",\"aboutIds\":[\"anchorId\"],\"actionOwner\":\"실제 행동 주체 유형\",\"ownerIds\":[]}]}";
    private static final String OUTPUT="""
            assessment 전에 sourceUnderstanding={"relations":[...]}을 반환한다. acts/contextIds는 쓰지 않는다.
              anchor와 해석에 필요한 줄만 선택하고 전체 raw·사고 과정·원문 재작성은 반환하지 않는다.
              각 항목: {"segmentId":"허용 원문 ID","actType":"행위 유형","relation":"관계 유형","respondsToId":null,
              "about":"대화 대상 종류","aboutIds":[],"actionOwner":"행동 주체 종류","ownerIds":[]}.
              같은 segmentId·actType은 한 번만 쓴다. 한 줄에 실제 복수 행위가 있으면 유형별로 구별한다.
            actType: TERM_EXPLANATION(뜻풀이 답변), QUOTED_SPEECH(인용·재현), DIRECT_REQUEST(현재 상대에게 정보·행동 요구),
              SELF_DESCRIPTION(자기 설명), SELF_OFFER(자신의 행동 제안), EVALUATION(평가), OTHER, UNRESOLVED.
              뜻을 묻는 질문과 뜻풀이 답변, 문장 속 요청과 현재 실제 요구를 구별한다.
              질문형이라는 이유로 자신의 제안을 상대에게 하는 요구로 바꾸지 않는다.
            relation은 다른 제공 줄과의 연결이다: MEANING_ANSWER(의미 질문에 답함), ANSWER(그 밖의 응답),
              QUOTATION(다른 제공 줄을 인용·재현), OTHER(그 밖의 명확한 연결), NONE, UNRESOLVED.
              연결이 있으면 respondsToId에 다른 허용 ID를 한 번만 쓴다. 자기 ID·없는 ID는 금지다.
              문장 내부 인용처럼 다른 제공 줄을 가리키지 않으면 QUOTED_SPEECH 행위와 NONE·null을 쓴다.
              NONE/UNRESOLVED는 respondsToId=null이다. 시간 인접만으로 응답·화자 동일성을 만들지 않는다.
            about: EXPRESSION_MEANING(표현의 뜻), PERSON_OR_BODY(실제 사람·신체), OTHER, UNRESOLVED.
              aboutIds에는 대상 해석의 허용 원문 근거를 쓴다. UNRESOLVED 외에는 근거가 필요하다.
            actionOwner는 지금 제안·요구한 행동의 주체다: SELF(현재 발화자), ADDRESSEE(현재 상대), OTHER,
              UNRESOLVED, NOT_APPLICABLE. 설명·인용 속 주체를 현재 요구 주체로 옮기지 않는다.
              UNRESOLVED/NOT_APPLICABLE은 ownerIds=[], 나머지는 허용 원문 근거가 필요하다.
              이름·동일인·억양·동의·실행은 추정하지 않는다. 보는 사람과 보여주는 사람을 구별한다.
            분류는 위험 판정이 아니다. 뜻풀이·인용·자기 제안에도 별도 모욕·위협 근거가 있을 수 있다.
              DIRECT_REQUEST·PERSON_OR_BODY도 자동 경고가 아니다. 원문에서 정상 해석과 구체적 검토 이유를 대조한다.
              reason/alternativeInterpretation은 위에서 기록한 관계를 다시 현재 요구로 바꾸지 않는다.
              별도 검토 이유가 있으면 그 근거를 명시한다. 관계에 맞추려고 원문·판정을 억지로 고치지 않는다.
              기존 대상·인용·대조·누락 정보 계약을 유지한다. 관계 불명 자체는 자동 경고·UNCERTAIN 근거가 아니다.
            """;
    private SourceUnderstandingCompact() {}
    static String prompt(String baseline) {
        return fromV1Prompt(SourceUnderstandingContract.prompt(baseline));
    }
    static String fromV1Prompt(String v1) {
        return SourceUnderstandingRelations.fromV1Prompt(v1,OUTPUT,EXAMPLE);
    }
    static String fromV2Prompt(String v2) {
        return SourceUnderstandingRelations.fromV2Prompt(v2,OUTPUT,EXAMPLE);
    }
    static String failure(SourceUnderstanding reading,CandidateReviewEngine.Candidate candidate) {
        if(reading.acts()!=null) return "UNEXPECTED_SOURCE_UNDERSTANDING_ACTS";
        var links=reading.relations();
        if(links==null || links.isEmpty() || links.size()>candidate.raw().size()*SourceUnderstanding.TYPES.size())
            return "SOURCE_RELATION_READINGS_REQUIRED";
        // Local key projection only, never filling IDs, modifying model output or converting legacy payloads.
        var keys=links.stream().map(l -> l==null?null:new SourceUnderstanding.ActReading(l.segmentId(),l.actType(),List.of())).toList();
        var projected=new SourceUnderstanding(keys,links);
        String keyFailure=projected.failure(candidate);
        return keyFailure!=null?keyFailure:SourceUnderstandingRelations.failure(projected,candidate,false);
    }
}
