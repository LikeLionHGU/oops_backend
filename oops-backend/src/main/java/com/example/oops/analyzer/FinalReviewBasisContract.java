package com.example.oops.analyzer;

import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;

/** Opt-in text verification contract. Never changes raw, anchor, discovery windows or references. */
final class FinalReviewBasisContract {
    static final String VERSION="final-review-basis-text-v1";
    private static final Set<String> EXPRESSION_CATEGORIES=Set.of("GRAPHIC_METAPHOR","SEXUAL","PRIVACY","VIOLENCE");
    private static final String LEGACY_AXIS_RULE="""
              탐색 axis는 가설이다. 검증 결과가 GRAPHIC_METAPHOR이고 대상 공격 없이 구체적 신체 훼손 비유 자체를
              검토하는 경우에는 탐색 axis가 TARGET_TREATMENT였어도 target과 대상 관계 필드를 null로 반환할 수 있다.
            """;
    private static final String RULE="""
              탐색 axis는 읽기 창을 선택한 가설이며 최종 검토 근거를 강제하지 않는다.
              REVIEW_REQUIRED에는 assessment 밖의 reviewBasis 객체를 반드시 반환한다:
              {"axis":"TARGET_TREATMENT 또는 EXPRESSION_CONTENT","reason":"원문에 근거한 최종 검토 이유",
               "evidenceIds":["실제 근거 ID"]}. reason은 300자 이내이며 주체·행위·평가 대상의 관계를 보존한다.
              evidenceIds는 중복 없이 현재 assessment.evidence에 인용한 허용 원문 ID에서 선택하고 anchorId도 포함한다.
              TARGET_TREATMENT는 실제 대상 평가다. target과 TARGET 인용 및 기존 대상 관계 계약을 모두 충족한다.
              EXPRESSION_CONTENT는 대상 평가와 별개로 성립하는 표현·공개 내용 자체의 구체적 검토 이유다.
              이 축은 GRAPHIC_METAPHOR/SEXUAL/PRIVACY/VIOLENCE만 허용하고 대상 관련 필드는 모두 null이다.
              성적 단어·사생활 질문·자발적 제안 자체만으로 경고하지 않는다. 특정인을 성적으로 대상화한다는
              판단을 대상 근거 없이 통과시키려고 EXPRESSION_CONTENT로 바꾸지 않는다. 별도의 표현 자체 근거가 필요하다.
              비하·조롱·집단 일반화·차별·혐오 등 대상 평가를 표현 자체로 바꿔 대상 검사를 피하지 않는다.
              현재 raw와 segmentIds 밖의 문맥을 추가하거나 새 화자·동의·실제 실행을 추정하지 않는다.
              판단 축이 바뀌어도 원문·anchor·허용 인용 범위는 그대로다. 필수 근거가 부족하면 UNCERTAIN이다.
              PASS/UNCERTAIN은 reviewBasis=null이다. 기존 대조 해석·원문 인용·누락 정보 계약은 그대로 유지한다.
            """;
    private FinalReviewBasisContract() {}
    static String prompt(String baseline) {
        int offset=baseline.indexOf(LEGACY_AXIS_RULE);
        int bodyEnd=baseline.indexOf("# 대상 필드 형식 예시");
        if(offset<0 || bodyEnd<0 || offset>=bodyEnd || !baseline.startsWith(CandidateReviewPrompts.POLICY))
            throw new IllegalArgumentException("Known verification axis rule required");
        // Only the verification instruction is replaced. Frozen reference text must remain byte-identical.
        return baseline.substring(0,offset)+RULE+baseline.substring(offset+LEGACY_AXIS_RULE.length());
    }
    static Validation validate(Candidate candidate,Verification response,ReviewInput input,int limit,boolean enabled) {
        if(response==null) return new Validation(null,"MISSING_CANDIDATE");
        if(!candidate.candidateId().equals(response.candidateId())) return new Validation(null,"UNEXPECTED_CANDIDATE_RESPONSE");
        if(!enabled) return response.reviewBasis()==null
                ? CandidateReviewEngine.validate(candidate,response.assessment(),input,limit)
                : new Validation(null,"UNEXPECTED_REVIEW_BASIS");
        var d=response.assessment();var basis=response.reviewBasis();
        if(d==null || !"REVIEW_REQUIRED".equals(d.decision())) return basis==null
                ? CandidateReviewEngine.validate(candidate,d,input,limit)
                : new Validation(null,"REVIEW_BASIS_FOR_NON_REVIEW");
        if(basis==null) return new Validation(null,"REVIEW_BASIS_REQUIRED");
        if(!Set.of("TARGET_TREATMENT","EXPRESSION_CONTENT").contains(basis.axis()==null?"":basis.axis()))
            return new Validation(null,"REVIEW_BASIS_AXIS");
        if(basis.reason()==null || basis.reason().isBlank() || basis.reason().length()>300)
            return new Validation(null,"REVIEW_BASIS_REASON");
        var ids=basis.evidenceIds();
        if(ids==null || ids.isEmpty() || ids.size()>candidate.raw().size() || ids.stream().anyMatch(id -> id==null || id.isBlank())
                || new HashSet<>(ids).size()!=ids.size() || !ids.contains(candidate.proposal().anchorId()))
            return new Validation(null,"REVIEW_BASIS_EVIDENCE");
        Set<String> cited=new HashSet<>(),allowed=new HashSet<>();
        candidate.raw().forEach(s -> allowed.add(s.id()));
        if(d.evidence()!=null) d.evidence().stream().filter(Objects::nonNull).forEach(e -> cited.add(e.segmentId()));
        if(!allowed.containsAll(ids) || !cited.containsAll(ids)) return new Validation(null,"REVIEW_BASIS_EVIDENCE");
        if("EXPRESSION_CONTENT".equals(basis.axis())) {
            if(!EXPRESSION_CATEGORIES.contains(d.category()==null?"":d.category())) return new Validation(null,"REVIEW_BASIS_CATEGORY");
            if(d.target()!=null || d.targetType()!=null || d.targetRelation()!=null || d.targetReason()!=null || d.targetMention()!=null)
                return new Validation(null,"EXPRESSION_BASIS_TARGET_FIELDS");
            if(d.evidence()!=null && d.evidence().stream().anyMatch(e -> e!=null && "TARGET".equals(e.role())))
                return new Validation(null,"EXPRESSION_BASIS_TARGET_FIELDS");
        } else if(d.target()==null || d.target().isBlank()) return new Validation(null,"TARGET_REQUIRED");
        var p=candidate.proposal();
        // Effective axis is used only for final validation; never replace the original candidate or raw.
        var effective=new Candidate(candidate.candidateId(),new Proposal(p.anchorId(),basis.axis(),p.reason(),p.evidence(),p.sourceRelations()),
                candidate.raw(),candidate.contextExpanded(),candidate.contextLimited());
        return CandidateReviewEngine.validate(effective,d,input,limit);
    }
}
