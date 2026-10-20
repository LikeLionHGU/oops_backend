package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

class TargetMentionBoundaryTest {
    @Test void surfaceIdentityAllowsOnlyWholeReferentWithKnownParticle() {
        for(var pair:List.of(List.of("메뉴","메뉴가"),List.of("여기","여기는"),List.of("가게","가게에서는"))) {
            assertThat(TargetMentionBoundary.sameSurfaceReferent(pair.get(0),pair.get(1))).isTrue();
            assertThat(TargetMentionBoundary.sameSurfaceReferent(pair.get(1),pair.get(0))).isTrue();
        }
        for(var pair:List.of(List.of("여기","식당"),List.of("너","너무"),List.of("나라","우리나라"),
                List.of("메뉴","메뉴판"),List.of("한남","한남충"),List.of("가게","가게 이름"),List.of("메뉴","메뉴처럼")))
            assertThat(TargetMentionBoundary.sameSurfaceReferent(pair.get(0),pair.get(1))).isFalse();
        assertThat(TargetMentionBoundary.sameSurfaceReferent(null,"메뉴")).isFalse();
    }
    @Test void actualValidationAcceptsParticleButStillRejectsReferentInferenceWithoutContext() {
        var f=new CandidateReviewEngineTest();var c=f.context("여기는 메뉴가 의미가 없어");
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(f.assessment("stt-index-0","REVIEW_REQUIRED","여기는 메뉴가 의미가 없어"));
        tree.put("target","여기");tree.put("targetMention","여기는");tree.put("targetType","BUSINESS");
        tree.put("targetRelation","EXPLICIT");tree.put("targetReason","원문에서 여기는이라는 표현을 직접 사용합니다");
        var candidate=new CandidateReviewEngine.Candidate("c",f.proposal("stt-index-0","여기는 메뉴가 의미가 없어"),c.reviewInput().segments(),false,false);
        assertThat(CandidateReviewEngine.validate(candidate,JSON.treeToValue(tree,TextReviewEngine.LlmDecision.class),c.reviewInput(),0).failureCode()).isNull();
        tree.put("target","식당");
        assertThat(CandidateReviewEngine.validate(candidate,JSON.treeToValue(tree,TextReviewEngine.LlmDecision.class),c.reviewInput(),0).failureCode()).isEqualTo("TARGET_MENTION_RELATION");
        tree.put("targetRelation","CONTEXTUAL");
        assertThat(CandidateReviewEngine.validate(candidate,JSON.treeToValue(tree,TextReviewEngine.LlmDecision.class),c.reviewInput(),0).failureCode()).isEqualTo("TARGET_CONTEXT_EVIDENCE_REQUIRED");
    }
    @Test void rejectsWordFragmentsWithoutRejectingParticlesOrPunctuation() {
        for(String text:List.of("너무 좋아","너무는 별로야","그너는","너무 너머","abc너","너_이름"))
            assertThat(TargetMentionBoundary.matches(text,"너")).as(text).isFalse();
        for(String text:List.of("너","너는 그래","너에게는 말해","‘너’라고 해","너!","너무 좋아도 너는 아니야","😀너를"))
            assertThat(TargetMentionBoundary.matches(text,"너")).as(text).isTrue();
        for(String text:List.of("할머니의 말","할머니께서는","할머니에게만","할머니에게서도","할머니에는","그 집에서는"))
            assertThat(TargetMentionBoundary.matches(text,text.startsWith("그 집")?"그 집":"할머니")).as(text).isTrue();
        assertThat(TargetMentionBoundary.matches("우리나라의 말","나라")).isFalse();
        assertThat(TargetMentionBoundary.matches("가게이름의 말","가게")).isFalse();
    }
    @Test void literalMatchingDoesNotInterpretRegexOrClaimPersonSemantics() {
        assertThat(TargetMentionBoundary.matches("(A+B)의 이야기","A+B")).isTrue();
        assertThat(TargetMentionBoundary.matches("saljjak's story","saljjak")).isTrue();
        assertThat(TargetMentionBoundary.matches("살짝 보여드릴까요","살짝")).isTrue(); // Known semantic gap.
        assertThat(TargetMentionBoundary.matches("너",null)).isFalse();
        assertThat(TargetMentionBoundary.matches(null,"너")).isFalse();
        assertThat(TargetMentionBoundary.matches("너","")).isFalse();
    }
    @Test void actualTargetInspectionRejectsSubstringButPreservesInflectedReferent() {
        var f=new CandidateReviewEngineTest();var c=f.context("너무 좋다");
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(f.assessment("stt-index-0","REVIEW_REQUIRED","너무 좋다"));
        tree.put("target","너");tree.put("targetMention","너");tree.put("targetType","PERSON");
        tree.put("targetReason","인용에서 직접 상대를 지칭합니다");
        var decision=JSON.treeToValue(tree,TextReviewEngine.LlmDecision.class);
        var candidate=new CandidateReviewEngine.Candidate("c",f.proposal("stt-index-0","너무 좋다"),c.reviewInput().segments(),false,false);
        assertThat(CandidateReviewEngine.validate(candidate,decision,c.reviewInput(),0).failureCode()).isEqualTo("TARGET_EVIDENCE_REQUIRED");
        var inflected=f.context("너는 수준이 낮아");
        assertThat(CandidateReviewEngine.validate(new CandidateReviewEngine.Candidate("c",candidate.proposal(),inflected.reviewInput().segments(),false,false),
                decision,inflected.reviewInput(),0).failureCode()).isNull();
    }
}
