package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CandidateUnionExecutionTest {
    private ReviewInput.Segment row(String id,long time,String text) {
        return new ReviewInput.Segment(id,TimelineEventType.SPEECH,time,time+1000,text,null);
    }
    private ReviewInput rules() {
        return new ReviewInput(List.of(row("a",0,"피부를 찢는 비유"),row("b",1000,"살을 뜯는 표현")));
    }
    private CandidateReviewEngine.Candidate ai(ReviewInput input,String id,String anchor) {
        var line=input.find(anchor).orElseThrow();
        return new CandidateReviewEngine.Candidate(id,new CandidateReviewEngine.Proposal(anchor,"EXPRESSION_CONTENT",
                "원래 AI의 가설",List.of(new CandidateReviewEngine.Quote(anchor,line.text()))),List.of(line),false,false);
    }
    private CandidateReviewEngine.VerificationResult response(CandidateReviewEngine.Candidate candidate,String verdict) {
        String anchor=candidate.proposal().anchorId();
        var decision=new TextReviewEngine.LlmDecision(anchor,verdict,null,"제공 원문을 대조한 판단입니다",
                "REVIEW_REQUIRED".equals(verdict)?"GRAPHIC_METAPHOR":null,null,
                "REVIEW_REQUIRED".equals(verdict)?.5:null,null,null,
                "UNCERTAIN".equals(verdict)?List.of("판단에 필수적인 상황 정보가 부족합니다"):List.of(),
                List.of(new TextReviewEngine.LlmEvidence(anchor,null,"PRIMARY")),null,null,null,
                "REVIEW_REQUIRED".equals(verdict)?"비유로 읽히지만 구체적 신체 이미지가 있습니다":null);
        return new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(candidate.candidateId(),decision)));
    }
    @Test void ruleFailureIsIsolatedAndNoRetryOrFakePassOccurs() {
        var input=rules(); var calls=new AtomicInteger();
        var result=CandidateUnionExecution.run(input,List.of(),null,24,0,p -> {
            calls.incrementAndGet(); if(p.candidate().proposal().anchorId().equals("a")) throw new IllegalStateException("private transcript");
            return response(p.candidate(),"PASS");
        },null);
        assertEquals(2,calls.get()); assertEquals(2,result.verifierAttempts());
        assertEquals(List.of(CandidateUnionExecution.State.VERIFICATION_FAILED,CandidateUnionExecution.State.PASS),
                result.outcomes().stream().map(CandidateUnionExecution.Outcome::state).toList());
        assertTrue(result.incompleteCandidates());
        assertFalse(ReviewGuidelineLibrary.JSON.writeValueAsString(result).contains("private transcript"));
    }
    @Test void aiKeepsOriginalWindowAndFailureDoesNotSuppressRule() {
        var input=new ReviewInput(List.of(row("a",0,"일반 발언"),row("b",1000,"피부를 찢는")));
        var original=ai(input,"ai-1","a");
        var result=CandidateUnionExecution.run(input,List.of(original),null,24,0,p -> response(p.candidate(),"REVIEW_REQUIRED"),c -> {
            assertSame(original,c); assertEquals(1,c.raw().size()); assertEquals("원래 AI의 가설",c.proposal().reason());
            throw new IllegalStateException("provider secret");
        });
        assertEquals(2,result.outcomes().size());
        assertEquals(CandidateUnionExecution.State.VERIFICATION_FAILED,result.outcomes().get(0).state());
        assertEquals(CandidateUnionExecution.State.REVIEW_REQUIRED,result.outcomes().get(1).state());
        assertNotNull(result.outcomes().get(1).observation());
    }
    @Test void budgetSkippedCandidatesNeverReachPorts() {
        var calls=new AtomicInteger();
        var result=CandidateUnionExecution.run(rules(),List.of(),null,1,0,p -> {calls.incrementAndGet();return response(p.candidate(),"PASS");},null);
        assertEquals(1,calls.get()); assertEquals(1,result.verifierAttempts());
        assertEquals(1,result.outcomes().stream().filter(o -> o.state()==CandidateUnionExecution.State.BUDGET_SKIPPED).count());
    }
    @Test void zeroBudgetAndEmptyCandidatesAreNotVideoPass() {
        var skipped=CandidateUnionExecution.run(rules(),List.of(),null,0,0,null,null);
        assertEquals(0,skipped.verifierAttempts()); assertTrue(skipped.incompleteCandidates());
        var empty=CandidateUnionExecution.run(new ReviewInput(List.of(row("a",0,"안녕하세요"))),List.of(),null,24,0,null,null);
        assertTrue(empty.outcomes().isEmpty()); assertEquals(0,empty.verifierAttempts());
    }
    @Test void invalidResponseIsRejectedButNextCandidateContinues() {
        var result=CandidateUnionExecution.run(rules(),List.of(),null,24,0,p -> p.candidate().proposal().anchorId().equals("a")
                ? new CandidateReviewEngine.VerificationResult(List.of()) : response(p.candidate(),"PASS"),null);
        assertEquals("BRIDGE_RESPONSE_CARDINALITY",result.outcomes().get(0).failureCode());
        assertEquals(CandidateUnionExecution.State.PASS,result.outcomes().get(1).state());
    }
    @Test void linkedPassStillFailsWithoutNormalContext() {
        var input=new ReviewInput(List.of(row("a",0,"메뉴가 의미가 없어"),row("b",1000,"주는대로 먹어야 돼")));
        var result=CandidateUnionExecution.run(input,List.of(),null,24,0,p -> response(p.candidate(),"PASS"),null);
        assertEquals(CandidateUnionExecution.State.VERIFICATION_FAILED,result.outcomes().get(0).state());
        assertEquals("LINKED_PASS_CONTRAST_REQUIRED",result.outcomes().get(0).failureCode());
    }
    @Test void uncertainIsKeptSeparateFromPassAndFailure() {
        var input=new ReviewInput(List.of(row("a",0,"피부를 찢는")));
        var result=CandidateUnionExecution.run(input,List.of(),null,24,0,p -> response(p.candidate(),"UNCERTAIN"),null);
        assertEquals(CandidateUnionExecution.State.UNCERTAIN,result.outcomes().get(0).state());
        assertTrue(result.incompleteCandidates()); assertNull(result.outcomes().get(0).failureCode());
    }
    @Test void preparationFailureDoesNotCallRulePortOrSuppressAi() {
        var input=new ReviewInput(List.of(row("a",0,"피부를 찢는"),row("b",1000,"일반 발언")));
        var library=org.mockito.Mockito.mock(ReviewGuidelineLibrary.class);
        org.mockito.Mockito.when(library.select(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalArgumentException("private"));
        var ruleCalls=new AtomicInteger();
        var result=CandidateUnionExecution.run(input,List.of(ai(input,"ai-1","b")),library,24,0,p -> {
            ruleCalls.incrementAndGet(); return null;
        },c -> response(c,"PASS"));
        assertEquals(0,ruleCalls.get()); assertEquals(1,result.verifierAttempts());
        assertEquals(CandidateUnionExecution.State.PREPARATION_FAILED,result.outcomes().get(0).state());
        assertEquals(CandidateUnionExecution.State.PASS,result.outcomes().get(1).state());
    }
    @Test void originalAiConflictFailsBeforeAnyPortCall() {
        var input=new ReviewInput(List.of(row("a",0,"원문"),row("b",1000,"추가"))); var original=ai(input,"same","a");
        var changed=new CandidateReviewEngine.Candidate("same",original.proposal(),input.segments(),true,false);
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->CandidateUnionExecution.run(input,List.of(original,changed),null,24,0,null,c -> {
            calls.incrementAndGet(); return response(c,"PASS");
        }));
        assertEquals(0,calls.get());
    }
    @Test void duplicateAiSourcesAreCalledOnlyOnce() {
        var input=new ReviewInput(List.of(row("a",0,"원문"))); var original=ai(input,"same","a"); var calls=new AtomicInteger();
        var result=CandidateUnionExecution.run(input,List.of(original,original),null,24,0,null,c -> {
            calls.incrementAndGet();return response(c,"PASS");
        });
        assertEquals(1,calls.get()); assertEquals(1,result.plan().duplicateInputs());
        assertFalse(result.incompleteCandidates());
    }
    @Test void interruptedThreadDoesNotStartMoreVerifications() {
        try {
            Thread.currentThread().interrupt();
            var result=CandidateUnionExecution.run(rules(),List.of(),null,24,0,p -> {fail("Must not call"); return null;},null);
            assertEquals(0,result.verifierAttempts());
            assertTrue(result.outcomes().stream().allMatch(o -> o.state()==CandidateUnionExecution.State.CANCELLED));
        } finally { Thread.interrupted(); }
    }
    @Test void invalidOriginalContextIsNotSent() {
        var input=new ReviewInput(List.of(row("a",0,"원문"),row("b",20000,"먼 대화")));
        var p=new CandidateReviewEngine.Proposal("a","TARGET_TREATMENT","가설",List.of(new CandidateReviewEngine.Quote("a","원문"),new CandidateReviewEngine.Quote("b","먼 대화")));
        var c=new CandidateReviewEngine.Candidate("ai",p,input.segments(),false,true);
        var result=CandidateUnionExecution.run(input,List.of(c),null,24,0,null,null);
        assertEquals(CandidateUnionExecution.State.CONTEXT_INVALID,result.outcomes().get(0).state());
        assertEquals(0,result.verifierAttempts());
    }
    @Test void sameAnchorDisagreementIsRecordedWithoutOrVerdict() {
        var input=new ReviewInput(List.of(row("a",0,"피부를 찢는"))); var original=ai(input,"ai-1","a");
        var result=CandidateUnionExecution.run(input,List.of(original),null,24,0,
                p -> response(p.candidate(),"REVIEW_REQUIRED"),c -> response(c,"PASS"));
        assertEquals(2,result.outcomes().size()); assertEquals(List.of("a"),result.conflictingAnchorIds());
        assertTrue(result.incompleteCandidates());
        assertEquals(Set.of(CandidateUnionExecution.State.PASS,CandidateUnionExecution.State.REVIEW_REQUIRED),
                result.outcomes().stream().map(CandidateUnionExecution.Outcome::state).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void expandedPlanningWindowCannotGiveAiNewEvidencePermission() {
        var input=new ReviewInput(List.of(row("a",0,"일반 발언"),row("b",1000,"추가 원문"))); var original=ai(input,"ai-1","a");
        var result=CandidateUnionExecution.run(input,List.of(original),null,24,0,null,c -> {
            var decision=new TextReviewEngine.LlmDecision("a","PASS",null,"추가 문맥을 인용한 응답",null,null,null,null,null,List.of(),
                    List.of(new TextReviewEngine.LlmEvidence("a",null,"PRIMARY"),new TextReviewEngine.LlmEvidence("b",null,"CONTEXT")),null,null,null,null);
            return new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(c.candidateId(),decision)));
        });
        assertEquals(CandidateUnionExecution.State.VERIFICATION_FAILED,result.outcomes().get(0).state());
        assertNull(result.outcomes().get(0).observation());
    }
    @Test void interruptionBetweenCandidatesPreservesCompletedResultAndStopsNextPort() {
        try {
            var result=CandidateUnionExecution.run(rules(),List.of(),null,24,0,p -> {
                Thread.currentThread().interrupt(); return response(p.candidate(),"PASS");
            },null);
            assertEquals(1,result.verifierAttempts());
            assertEquals(CandidateUnionExecution.State.PASS,result.outcomes().get(0).state());
            assertEquals(CandidateUnionExecution.State.CANCELLED,result.outcomes().get(1).state());
        } finally { Thread.interrupted(); }
    }
}
