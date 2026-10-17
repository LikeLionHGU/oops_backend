package com.example.oops.analyzer;

import java.util.*;

/** Typed discourse links with bounded source IDs. Structural checks do not establish relation truth. */
final class SourceUnderstandingRelations {
    static final String VERSION="source-understanding-relations-v2";
    static final Set<String> RELATIONS=Set.of("MEANING_ANSWER","ANSWER","QUOTATION","OTHER","NONE","UNRESOLVED");
    static final Set<String> ABOUT=Set.of("EXPRESSION_MEANING","PERSON_OR_BODY","OTHER","UNRESOLVED");
    static final Set<String> OWNERS=Set.of("SELF","ADDRESSEE","OTHER","UNRESOLVED","NOT_APPLICABLE");
    private static final String INSTRUCTIONS="""
            각 항목은 assessment 전에 sourceUnderstanding={"acts":[...],"relations":[...]}을 반환한다.
              사고 과정·원문 재작성 없이 현재 발화 행위와 문장 간 관계를 원문 ID로만 기록한다.
              anchor와 해석을 결정하는 줄만 acts에 포함하고 전체 raw를 다시 나열하지 않는다.
            acts의 actType은 기존 여덟 유형이다: TERM_EXPLANATION(뜻을 풀이하는 답변), QUOTED_SPEECH(인용·재현),
              DIRECT_REQUEST(현재 상대에게 정보·행동을 요구), SELF_DESCRIPTION(자기 설명), SELF_OFFER(자신의 행동 제안),
              EVALUATION(평가), OTHER, UNRESOLVED. 단어 뜻을 묻는 질문은 DIRECT_REQUEST일 수 있으나 위험 판정은 아니다.
              ‘질문형인가’가 아니라 현재 발화의 기능을 분류한다. 자신의 행동 제안을 질문형이라는 이유로
              타인의 행동 요구로 바꾸지 않는다. 실제 복수 행위만 서로 다른 유형으로 기록한다.
              contextIds는 그 해석을 뒷받침하는 다른 허용 raw ID다. 같은 ID·유형은 반복하지 않는다.
            relations에는 acts에 선택한 각 segmentId·actType 쌍마다 정확히 한 객체를 반환한다:
              {"segmentId":"현재 줄","actType":"그 행위 유형","relation":"관계 유형","respondsToId":null,"about":"대화 대상 종류",
               "aboutIds":[],"actionOwner":"제안·요구된 행동 주체","ownerIds":[]}.
              한 줄에 뜻풀이와 실제 요구가 함께 있으면 둘의 관계·대상·주체를 한 행위로 뭉개지 않는다.
              relation: MEANING_ANSWER(앞선 의미 질문의 뜻풀이 답변), ANSWER(그 밖의 응답),
              QUOTATION(다른 줄의 발언을 인용·재현), OTHER(다른 명확한 연결), NONE(특정 응답 연결 없음), UNRESOLVED.
              연결이 있으면 respondsToId에 그 다른 줄의 허용 ID를 적고 해당 acts.contextIds에도 넣는다.
              NONE/UNRESOLVED는 respondsToId=null이다. 시간 인접만으로 응답·동일 화자 관계를 만들지 않는다.
              about: EXPRESSION_MEANING(표현의 뜻을 묻거나 설명), PERSON_OR_BODY(실제 사람·신체에 관한 내용),
              OTHER(그 밖의 대상), UNRESOLVED. aboutIds에는 이를 뒷받침하는 허용 원문 ID를 인용한다.
              의미 설명 속에 등장한 질문의 내용과 현재 실제 상대에게 묻는 내용을 구별한다.
              예를 들어 질문문을 뜻풀이 답변으로 말한 경우 그 문장 내용이 곧 현재 사생활 요구라는 뜻은 아니다.
              actionOwner는 지금 제안·요구한 행동을 할 사람을 SELF(현재 말하는 사람)/ADDRESSEE(현재 듣는 상대)/
              OTHER/UNRESOLVED/NOT_APPLICABLE로 구별한다. 설명·인용 속 행동 주체를 현재 요구 주체로 옮기지 않는다.
              단순 뜻풀이처럼 실제 제안·요구가 없으면 NOT_APPLICABLE이며 ownerIds=[]다.
              주체를 알 수 없으면 UNRESOLVED와 ownerIds=[]를 쓰고, 나머지는 주체를 뒷받침하는 허용 ID가 필요하다.
              화자 이름·같은 사람·동의·실제 실행을 추정하지 않는다. 누가 보는가와 누가 보여주는가를 혼동하지 않는다.
            관계와 행위 분류는 위험 라벨이 아니다. 의미 설명·인용·자기 제안도 별도 모욕·위협이 있으면 검토하며,
              DIRECT_REQUEST·PERSON_OR_BODY도 자동 경고가 아니다. 원문의 관계와 정상 해석을 대조해 판정한다.
              기존 reason/alternativeInterpretation에 필요한 연결을 간결히 설명하고 기존 대상·인용·대조·누락 정보 계약을 유지한다.
              관계 정보 부족 자체를 자동 경고·UNCERTAIN으로 치환하지 않는다. 판정에 필수인 누락인지 별도로 확인한다.
            """;
    private SourceUnderstandingRelations() {}
    static String prompt(String baseline) {
        return fromV1Prompt(SourceUnderstandingContract.prompt(baseline));
    }

    static String fromV1Prompt(String v1) {
        return fromV1Prompt(v1,INSTRUCTIONS,
                "\"sourceUnderstanding\":{\"acts\":[{\"segmentId\":\"anchorId\",\"actType\":\"실제 행위 유형\",\"contextIds\":[]}],\"relations\":[{\"segmentId\":\"anchorId\",\"actType\":\"같은 행위 유형\",\"relation\":\"실제 관계 유형\",\"respondsToId\":null,\"about\":\"실제 대화 대상 종류\",\"aboutIds\":[\"anchorId\"],\"actionOwner\":\"실제 행동 주체 유형\",\"ownerIds\":[]}]}");
    }
    static String fromV1Prompt(String v1,String instructions,String outputExample) {
        int boundary=v1.indexOf("# 대상 필드 형식 예시");
        if(boundary<0) throw new IllegalArgumentException("Known verification output boundary required");
        String body=v1.substring(0,boundary);
        String example="\"sourceUnderstanding\":{\"acts\":[{\"segmentId\":\"anchorId\",\"actType\":\"실제 행위 유형\",\"contextIds\":[]}]}";
        if(body.indexOf(SourceUnderstandingContract.OUTPUT)<0 || body.indexOf(example)<0
                || body.indexOf(example)!=body.lastIndexOf(example)) throw new IllegalArgumentException("Known act contract required");
        return body.replace(SourceUnderstandingContract.OUTPUT,instructions).replace(example,outputExample)
                +v1.substring(boundary);
    }
    static String failure(SourceUnderstanding reading,CandidateReviewEngine.Candidate candidate) {
        return failure(reading,candidate,true);
    }
    static String failure(SourceUnderstanding reading,CandidateReviewEngine.Candidate candidate,boolean requireContextCopy) {
        var links=reading.relations();
        Map<List<String>,Set<String>> contexts=new HashMap<>();
        reading.acts().forEach(a -> contexts.put(List.of(a.segmentId(),a.actType()),new HashSet<>(a.contextIds())));
        if(links==null || links.size()!=contexts.size()) return "SOURCE_RELATION_READINGS_REQUIRED";
        Set<String> allowed=new HashSet<>();candidate.raw().forEach(s -> allowed.add(s.id()));
        Set<List<String>> seen=new HashSet<>();
        for(var link:links) {
            if(link==null || link.segmentId()==null || link.actType()==null)
                return "SOURCE_RELATION_READING_ID";
            var key=List.of(link.segmentId(),link.actType());
            if(!contexts.containsKey(key) || !seen.add(key))
                return "SOURCE_RELATION_READING_ID";
            if(link.relation()==null || !RELATIONS.contains(link.relation()) || link.about()==null || !ABOUT.contains(link.about())
                    || link.actionOwner()==null || !OWNERS.contains(link.actionOwner())) return "SOURCE_RELATION_READING_TYPE";
            boolean unlinked=Set.of("NONE","UNRESOLVED").contains(link.relation());
            if(unlinked ? link.respondsToId()!=null : link.respondsToId()==null || !allowed.contains(link.respondsToId())
                    || link.segmentId().equals(link.respondsToId()) || requireContextCopy && !contexts.get(key).contains(link.respondsToId()))
                return "SOURCE_RELATION_READING_LINK";
            if(!ids(link.aboutIds(),allowed) || !"UNRESOLVED".equals(link.about()) && link.aboutIds().isEmpty())
                return "SOURCE_RELATION_READING_ABOUT";
            if(!ids(link.ownerIds(),allowed)) return "SOURCE_RELATION_READING_OWNER";
            boolean noOwner=Set.of("NOT_APPLICABLE","UNRESOLVED").contains(link.actionOwner());
            if(noOwner ? !link.ownerIds().isEmpty() : link.ownerIds().isEmpty()) return "SOURCE_RELATION_READING_OWNER";
        }
        return null;
    }
    private static boolean ids(List<String> ids,Set<String> allowed) {
        return ids!=null && ids.size()<=allowed.size() && ids.stream().allMatch(id -> id!=null && allowed.contains(id))
                && new HashSet<>(ids).size()==ids.size();
    }
}
