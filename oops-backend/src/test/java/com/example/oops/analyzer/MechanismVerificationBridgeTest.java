package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MechanismVerificationBridgeTest {
    private ReviewInput input() {
        return new ReviewInput(List.of(row("before",0,"안내에 동의한 상황입니다"),row("a",1000,"메뉴가 의미가 없어"),
                row("b",2000,"주는대로 먹어야 돼"),row("after",3000,"선택 메뉴가 하나라는 안내입니다")));
    }
    private ReviewInput.Segment row(String id,long start,String text) {
        return new ReviewInput.Segment(id,TimelineEventType.SPEECH,start,start+1000,text,null);
    }
    private MechanismVerificationBridge.Prepared prepare(ReviewInput input) {
        return MechanismVerificationBridge.prepareRules(input,CandidateUnionShadow.plan(input,List.of(),24),null,0).get(0);
    }
    private TextReviewEngine.LlmDecision decision(String anchor,String verdict,String category,List<TextReviewEngine.LlmEvidence> evidence,String contrast) {
        return new TextReviewEngine.LlmDecision(anchor,verdict,null,"원문 연결을 확인한 판단입니다",category,null,
                "REVIEW_REQUIRED".equals(verdict)?.5:null,null,null,List.of(),evidence,null,null,null,contrast);
    }
    private TextReviewEngine.LlmEvidence evidence(String id,String role) { return new TextReviewEngine.LlmEvidence(id,null,role); }
    private CandidateReviewEngine.VerificationResult response(MechanismVerificationBridge.Prepared p,TextReviewEngine.LlmDecision d) {
        return new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(p.candidate().candidateId(),d)));
    }
    @Test void noHypothesisIsInventedAndSourceQuotesRemainExact() {
        var input=input(); var p=prepare(input); var root=ReviewGuidelineLibrary.JSON.readTree(p.user());
        assertFalse(root.path("candidates").get(0).has("hypothesisNotEvidence"));
        assertFalse(root.path("candidates").get(0).has("reason"));
        assertNull(p.candidate().proposal().reason());
        assertTrue(p.system().startsWith(CandidateReviewPrompts.POLICY));
        assertTrue(p.system().contains("표면 표현 조합"));
        assertEquals(4,root.path("raw").size());
        assertEquals("메뉴가 의미가 없어",p.candidate().proposal().evidence().get(0).quote());
        assertEquals(p,prepare(input));
    }
    @Test void linkedPassCanUseNormalContextOutsideRuleSignals() {
        var input=input(); var p=prepare(input);
        var d=decision("a","PASS",null,List.of(evidence("a","PRIMARY"),evidence("after","CONTEXT")),"단일 메뉴 안내로 설명되며 별도 폄하는 없습니다");
        var validation=MechanismVerificationBridge.validate(p,response(p,d),input);
        assertNull(validation.failureCode()); assertNotNull(validation.observation());
    }
    @Test void linkedPassStillRequiresContextAndContrast() {
        var input=input(); var p=prepare(input);
        var d=decision("a","PASS",null,List.of(evidence("a","PRIMARY")),null);
        assertEquals("LINKED_PASS_CONTRAST_REQUIRED",MechanismVerificationBridge.validate(p,response(p,d),input).failureCode());
        d=decision("a","PASS",null,List.of(evidence("a","PRIMARY")),"정상 안내입니다");
        assertEquals("LINKED_PASS_CONTEXT_REQUIRED",MechanismVerificationBridge.validate(p,response(p,d),input).failureCode());
    }
    @Test void reviewCannotBypassTargetContract() {
        var input=input(); var p=prepare(input);
        var d=decision("a","REVIEW_REQUIRED","BELITTLEMENT",List.of(evidence("a","PRIMARY")),"정상 안내 가능성을 대조했습니다");
        assertEquals("TARGET_REQUIRED",MechanismVerificationBridge.validate(p,response(p,d),input).failureCode());
    }
    @Test void graphicReviewUsesExistingPublicationValidator() {
        var input=new ReviewInput(List.of(row("a",0,"피부를 찢는 것 같다"))); var p=prepare(input);
        var d=decision("a","REVIEW_REQUIRED","GRAPHIC_METAPHOR",List.of(evidence("a","PRIMARY")),"비유이지만 구체적인 훼손 표현입니다");
        assertNull(MechanismVerificationBridge.validate(p,response(p,d),input).failureCode());
    }
    @Test void outsideEvidenceDoesNotBecomeAllowed() {
        var input=input(); var p=prepare(input);
        var d=decision("a","PASS",null,List.of(evidence("a","PRIMARY"),evidence("outside","CONTEXT")),"정상 해석을 대조했습니다");
        assertNotNull(MechanismVerificationBridge.validate(p,response(p,d),input).failureCode());
    }
    @Test void responseCountAndCandidateIdMustMatch() {
        var input=input(); var p=prepare(input);
        assertEquals("BRIDGE_RESPONSE_CARDINALITY",MechanismVerificationBridge.validate(p,null,input).failureCode());
        var d=decision("a","PASS",null,List.of(evidence("a","PRIMARY")),"정상 해석");
        var wrong=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification("wrong",d)));
        assertEquals("BRIDGE_CANDIDATE_ID",MechanismVerificationBridge.validate(p,wrong,input).failureCode());
        var duplicate=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(p.candidate().candidateId(),d),
                new CandidateReviewEngine.Verification(p.candidate().candidateId(),d)));
        assertEquals("BRIDGE_RESPONSE_CARDINALITY",MechanismVerificationBridge.validate(p,duplicate,input).failureCode());
    }
    @Test void changedSnapshotAndForgedWindowAreRejected() {
        var input=input(); var p=prepare(input);
        assertEquals("BRIDGE_SNAPSHOT_MISMATCH",MechanismVerificationBridge.validate(p,null,new ReviewInput(List.of(row("a",0,"다른 원문")))).failureCode());
        var plan=CandidateUnionShadow.plan(input,List.of(),24); var entry=plan.entries().get(0);
        var forged=new CandidateUnionShadow.Entry(entry.candidate(),entry.state(),List.of(row("a",1000,"위조 원문")),entry.contextLimited());
        var bad=new CandidateUnionShadow.Plan(plan.revision(),plan.inputSha256(),0,List.of(forged));
        assertThrows(IllegalArgumentException.class,()->MechanismVerificationBridge.prepareRules(input,bad,null,0));
    }
    @Test void budgetSkippedDoesNotProduceARequest() {
        var input=input();
        assertTrue(MechanismVerificationBridge.prepareRules(input,CandidateUnionShadow.plan(input,List.of(),0),null,0).isEmpty());
    }
    @Test void aiAdapterPreservesOriginalHypothesisAndWindow() {
        var input=input();
        var proposal=new CandidateReviewEngine.Proposal("a","TARGET_TREATMENT","원래 AI 가설",List.of(new CandidateReviewEngine.Quote("a","메뉴가 의미가 없어")));
        var original=new CandidateReviewEngine.Candidate("ai-1",proposal,List.of(input.find("a").orElseThrow()),false,true);
        var source=MechanismVerificationBridge.retainAi(input,original);
        assertSame(original,source.original()); assertEquals("원래 AI 가설",source.original().proposal().reason());
        assertEquals(1,source.original().raw().size());
        assertEquals(CandidateUnionShadow.snapshotHash(input),source.unionCandidate().inputSha256());
    }
    @Test void aiAdapterDoesNotAcceptInventedQuotes() {
        var input=input();
        var proposal=new CandidateReviewEngine.Proposal("a","TARGET_TREATMENT","가설",List.of(new CandidateReviewEngine.Quote("a","원문에 없는 말")));
        var original=new CandidateReviewEngine.Candidate("ai-1",proposal,input.segments(),false,false);
        assertThrows(IllegalArgumentException.class,()->MechanismVerificationBridge.retainAi(input,original));
    }
    @Test void referenceSelectionIsFrozenAndNotRepeatedDuringValidation() {
        var input=input(); var library=org.mockito.Mockito.mock(ReviewGuidelineLibrary.class);
        org.mockito.Mockito.when(library.select(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReviewGuidelineLibrary.Selection("\nREFERENCE_SNAPSHOT",null));
        var p=MechanismVerificationBridge.prepareRules(input,CandidateUnionShadow.plan(input,List.of(),24),library,0).get(0);
        assertTrue(p.system().endsWith("REFERENCE_SNAPSHOT"));
        assertEquals(ReviewGuidelineLibrary.retrievalHash("\nREFERENCE_SNAPSHOT"),p.referenceSha256());
        MechanismVerificationBridge.validate(p,null,input);
        org.mockito.Mockito.verify(library,org.mockito.Mockito.times(1)).select(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
    }
    @Test void evidenceLimitAndUnrequestedOutputFieldsAreNotRelaxed() {
        var input=input();
        var p=MechanismVerificationBridge.prepareRules(input,CandidateUnionShadow.plan(input,List.of(),24),null,1).get(0);
        var d=decision("a","PASS",null,List.of(evidence("a","PRIMARY"),evidence("after","CONTEXT")),"정상 안내와 연결됩니다");
        assertEquals("EVIDENCE_BUDGET_EXCEEDED",MechanismVerificationBridge.validate(p,response(p,d),input).failureCode());
        var normal=prepare(input);
        var extra=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(normal.candidate().candidateId(),d,
                new CandidateReviewEngine.ReviewBasis("TARGET_TREATMENT","추가 필드",List.of("a")))));
        assertNotNull(MechanismVerificationBridge.validate(normal,extra,input).failureCode());
    }
}
