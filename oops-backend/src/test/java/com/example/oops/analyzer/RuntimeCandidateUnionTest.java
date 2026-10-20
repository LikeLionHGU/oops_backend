package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RuntimeCandidateUnionTest {
    private final Video video=Video.builder().filename("synthetic.mp4").build();
    private AnalysisContext context(String... texts) {
        List<TranscriptSegment> rows=new ArrayList<>();
        for(int i=0;i<texts.length;i++) rows.add(new TranscriptSegment(video,i*2000L,i*2000L+1500,texts[i]));
        return new AnalysisContext(video,ContentGenre.GENERAL,rows,List.of());
    }
    private final class Harness {
        final OpenAiClient client=mock(OpenAiClient.class);
        final List<String> requests=new ArrayList<>();
        final List<String> systems=new ArrayList<>();
        Harness(boolean proposeAi,Function<JsonNode,VerificationResult> responder) {
            when(client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenAnswer(call -> {
                String system=call.getArgument(0),request=call.getArgument(1); systems.add(system);requests.add(request);
                var tree=ReviewGuidelineLibrary.JSON.readTree(request); List<String> ids=new ArrayList<>();
                tree.path("primaryIds").forEach(n -> ids.add(n.asText()));
                List<Proposal> proposals=new ArrayList<>();
                if(proposeAi) for(var row:tree.path("raw")) if(ids.contains(row.path("id").asText()) && proposals.size()<12)
                    proposals.add(new Proposal(row.path("id").asText(),"EXPRESSION_CONTENT","독립적인 표현 검토 가설입니다",
                            List.of(new Quote(row.path("id").asText(),row.path("text").asText()))));
                return Optional.of(new Discovery(ids,proposals,List.of(),false));
            });
            when(client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
                String system=call.getArgument(0),request=call.getArgument(1); systems.add(system);requests.add(request);
                return Optional.ofNullable(responder.apply(ReviewGuidelineLibrary.JSON.readTree(request)));
            });
        }
        TextReviewEngine.Result run(AnalysisContext context,Boolean enabled,int budget,ReviewGuidelineLibrary library) {
            var analyzer=new SpeechReviewAnalyzer(client,true,true,budget);
            if(enabled!=null) analyzer.setRuleUnionEnabled(enabled);
            if(library!=null) analyzer.setGuidelineLibrary(library);
            analyzer.analyze(context);return analyzer.consumeReviewResult(context).orElseThrow();
        }
    }
    private VerificationResult answer(JsonNode root,boolean review) {
        List<Verification> out=new ArrayList<>();
        for(var item:root.path("candidates")) {
            String id=item.path("candidateId").asText(),anchor=item.path("anchorId").asText();
            boolean target=review && "TARGET_TREATMENT".equals(item.path("axis").asText());
            List<TextReviewEngine.LlmEvidence> evidence=new ArrayList<>();
            evidence.add(new TextReviewEngine.LlmEvidence(anchor,null,"PRIMARY"));
            if(target) evidence.add(new TextReviewEngine.LlmEvidence(anchor,null,"TARGET"));
            if(item.path("proposedEvidence").size()>1)
                evidence.add(new TextReviewEngine.LlmEvidence(item.path("proposedEvidence").get(1).path("segmentId").asText(),null,"CONTEXT"));
            var d=new TextReviewEngine.LlmDecision(anchor,review?"REVIEW_REQUIRED":"PASS",null,"원문 연결과 정상 해석을 대조한 판단입니다",
                    review?target?"STRONG_NEGATIVE_REVIEW":"GRAPHIC_METAPHOR":null,target?"메뉴":null,review?.5:null,null,null,
                    List.of(),evidence,target?"PRODUCT":null,target?"EXPLICIT":null,target?"메뉴라는 원문 대상에 대한 평가입니다":null,
                    "정상적인 설명 가능성도 원문과 대조했습니다",target?"메뉴":null);
            out.add(new Verification(id,d));
        }
        return new VerificationResult(out);
    }
    @Test void defaultOffAndExplicitOffHaveIdenticalRequestsAndDiagnostics() {
        var c=context("피부를 찢는 것 같다"); var a=new Harness(true,t -> answer(t,true));var b=new Harness(true,t -> answer(t,true));
        var left=a.run(c,null,24,null);var right=b.run(c,false,24,null);
        assertEquals(a.requests,b.requests);assertEquals(a.systems,b.systems);
        assertEquals(ReviewGuidelineLibrary.JSON.writeValueAsString(left.diagnostics()),ReviewGuidelineLibrary.JSON.writeValueAsString(right.diagnostics()));
        assertNull(left.diagnostics().candidateUnion());
        assertFalse(ReviewGuidelineLibrary.JSON.writeValueAsString(left.diagnostics()).contains("candidateUnion"));
        assertEquals(left.findings().size(),right.findings().size());
    }
    @Test void onRecoversRuleCandidateWhenDiscoveryReturnsNone() {
        var c=context("피부를 찢는 것 같다");var h=new Harness(false,t -> answer(t,true));
        var result=h.run(c,true,24,null);
        assertEquals(1,result.findings().size()); assertEquals("피부를 찢는 것 같다",result.findings().get(0).getText());
        assertEquals("PLANNED",result.diagnostics().candidateUnion().state());
        assertEquals("RULE",result.diagnostics().candidateUnion().entries().get(0).origin());
        var requests=h.requests.stream().map(ReviewGuidelineLibrary.JSON::readTree).filter(t -> t.has("candidates")).toList();
        assertEquals(1,requests.size());assertFalse(requests.get(0).path("candidates").get(0).has("hypothesisNotEvidence"));
    }
    @Test void menuRuleFlowsThroughExistingValidatorToFinding() {
        var h=new Harness(false,t -> answer(t,true));var result=h.run(context("메뉴가 의미가 없어","주는대로 먹어야 돼"),true,24,null);
        assertEquals(1,result.findings().size());assertEquals(0,result.diagnostics().candidatePipeline().verificationFailed());
        assertEquals(2,result.findings().get(0).validatedSupports().get(0).quotes().stream().map(q -> q.segmentId()).distinct().count());
    }
    @Test void onPreservesOriginalAiRequestAndHypothesis() {
        var c=context("일반적인 발언");var off=new Harness(true,t -> answer(t,false));var on=new Harness(true,t -> answer(t,false));
        off.run(c,false,24,null);on.run(c,true,24,null);
        assertEquals(off.requests,on.requests);assertEquals(off.systems,on.systems);
        assertTrue(on.requests.get(on.requests.size()-1).contains("hypothesisNotEvidence"));
    }
    @Test void budgetIsSharedAndLateRuleIsNotLostToEarlyAiCap() {
        String[] texts=new String[45];Arrays.fill(texts,"일반 대화 발언");texts[35]="피부를 찢는";
        var h=new Harness(true,t -> answer(t,false));var result=h.run(context(texts),true,2,null);
        var entries=result.diagnostics().candidateUnion().entries();
        assertEquals(2,entries.stream().filter(e -> e.state().equals("READY")).count());
        assertTrue(entries.stream().anyMatch(e -> e.origin().equals("RULE") && e.anchorId().equals("stt-index-35") && e.state().equals("READY")));
        assertTrue(result.diagnostics().candidatePipeline().budgetSkipped()>0);
        assertEquals(0,result.diagnostics().candidatePipeline().invalidProposals());
        assertTrue(result.diagnostics().candidatePipeline().proposed()>2);
        assertTrue(result.diagnostics().candidatePipeline().verificationCalls()<=2);
    }
    @Test void oneRuleExceptionDoesNotPreventOtherRuleOrInvokeRepair() {
        var h=new Harness(false,t -> {
            if(t.path("candidates").get(0).path("anchorId").asText().equals("stt-index-0")) throw new IllegalStateException("private data");
            return answer(t,true);
        });
        var result=h.run(context("피부를 찢는","살을 뜯는"),true,24,null);
        assertEquals(1,result.findings().size());assertEquals(1,result.diagnostics().candidatePipeline().verificationFailed());
        assertEquals(0,result.diagnostics().candidatePipeline().repairCalls());
        assertFalse(ReviewGuidelineLibrary.JSON.writeValueAsString(result.diagnostics()).contains("private data"));
    }
    @Test void rulePreparationFailurePreservesAiVerification() {
        var library=mock(ReviewGuidelineLibrary.class);
        var trace=new ReviewGuidelineLibrary.SelectionTrace("discovery","READY","MOCK",0,0,0,0,0,List.of(),List.of());
        when(library.select(any(),any(),anyString())).thenReturn(new ReviewGuidelineLibrary.Selection("",trace));
        when(library.select(any(),any(),anyString(),any())).thenThrow(new IllegalStateException("private"));
        var h=new Harness(true,t -> answer(t,false));var result=h.run(context("피부를 찢는"),true,24,library);
        assertTrue(result.diagnostics().candidateUnion().entries().stream().anyMatch(e -> e.state().equals("PREPARATION_FAILED")));
        assertEquals(1,result.diagnostics().candidatePipeline().verificationCalls());
        assertTrue(result.diagnostics().candidatePipeline().candidates().stream().anyMatch(e -> e.state().equals("PASS")));
    }
    @Test void planningFailureIsVisibleAndFallbackStillBounded() {
        var c=new AnalysisContext(video,ContentGenre.GENERAL,List.of(new TranscriptSegment(video,0,21001,"피부를 찢는")),List.of());
        var h=new Harness(true,t -> answer(t,false));var result=h.run(c,true,1,null);
        assertEquals("UNION_UNAVAILABLE",result.diagnostics().candidateUnion().state());
        assertTrue(result.notice().contains("규칙 합집합 준비 실패"));
        assertTrue(result.diagnostics().candidatePipeline().verificationCalls()<=1);
    }
    @Test void sameAnchorPassReviewDifferenceIsVisibleNotConsensus() {
        var h=new Harness(true,t -> answer(t,t.path("candidates").get(0).path("origin").asText().equals("RULE")));
        var result=h.run(context("피부를 찢는"),true,24,null);
        assertEquals(List.of("stt-index-0"),result.diagnostics().candidateUnion().conflictingAnchorIds());
        assertEquals(ReviewDiagnostics.State.CONFLICT,result.diagnostics().segments().get(0).state());
        assertEquals(AnalyzerStatus.PARTIAL,result.status());assertTrue(result.notice().contains("합의 판정이 아닙니다"));
    }
    @Test void unsupportedExperimentsAndLegacyModeFailBeforeCalls() {
        var client=mock(OpenAiClient.class);var c=context("피부를 찢는");
        var analyzer=new SpeechReviewAnalyzer(client,true,true,24);analyzer.setRuleUnionEnabled(true);analyzer.setFinalReviewBasisEnabled(true);
        assertThrows(IllegalArgumentException.class,()->analyzer.analyze(c));verifyNoInteractions(client);
        var legacy=new SpeechReviewAnalyzer(client,true,false,24);legacy.setRuleUnionEnabled(true);
        assertThrows(IllegalStateException.class,()->legacy.analyze(c));verifyNoInteractions(client);
    }
    @Test void discoveryFailureIsNotHiddenBySuccessfulRuleVerification() {
        var h=new Harness(false,t -> answer(t,true));
        when(h.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenReturn(Optional.empty());
        var result=h.run(context("피부를 찢는"),true,24,null);
        assertEquals(1,result.findings().size());assertEquals(AnalyzerStatus.FAILED,result.status());
        assertTrue(result.diagnostics().candidatePipeline().invalidProposals()>0);
    }
    @Test void normalInputDoesNotCreateAdditionalVerificationCalls() {
        var h=new Harness(false,t -> {fail("No verification expected");return null;});
        var result=h.run(context("맛이 별로고 가격이 아까워요"),true,24,null);
        assertTrue(result.findings().isEmpty());assertEquals(0,result.diagnostics().candidatePipeline().verificationCalls());
        assertTrue(result.diagnostics().candidateUnion().entries().isEmpty());
    }
    @Test void boundedUnionDiagnosticsKeepLateSelectedCandidates() {
        List<RuntimeCandidateUnion.Entry> entries=new ArrayList<>();
        for(int i=0;i<200;i++) entries.add(new RuntimeCandidateUnion.Entry("AI","c"+i,"a"+i,null,"c"+i,"BUDGET_SKIPPED"));
        entries.add(new RuntimeCandidateUnion.Entry("RULE","last","last","graphic-body-image","rule-last","READY"));
        var trace=RuntimeCandidateUnion.bounded(new RuntimeCandidateUnion.Trace("revision","PLANNED","hash",201,false,entries));
        assertEquals(200,trace.entries().size());assertTrue(trace.truncated());
        assertEquals("last",trace.entries().get(0).anchorId());
    }
    @Test void ruleContractFailureDoesNotEnterHypothesisRepair() {
        var h=new Harness(false,t -> {
            var item=t.path("candidates").get(0);String anchor=item.path("anchorId").asText();
            var d=new TextReviewEngine.LlmDecision(anchor,"REVIEW_REQUIRED",null,"메뉴를 낮추는 연결로 판단했습니다","BELITTLEMENT",null,.5,
                    null,null,List.of(),List.of(new TextReviewEngine.LlmEvidence(anchor,null,"PRIMARY")),null,null,null,"정상 안내도 대조했습니다");
            return new VerificationResult(List.of(new Verification(item.path("candidateId").asText(),d)));
        });
        var result=h.run(context("메뉴가 의미가 없어","주는대로 먹어야 돼"),true,24,null);
        assertTrue(result.findings().isEmpty());assertEquals(0,result.diagnostics().candidatePipeline().repairCalls());
        assertEquals(1,result.diagnostics().candidatePipeline().verificationCalls());
        assertTrue(result.diagnostics().candidatePipeline().candidates().stream().anyMatch(t -> "TARGET_REQUIRED".equals(t.failureCode())));
    }
    @Test void interruptedOnPathStartsNoModelCalls() {
        var h=new Harness(false,t -> answer(t,true));
        try {
            Thread.currentThread().interrupt();
            var result=h.run(context("피부를 찢는"),true,24,null);
            assertTrue(h.requests.isEmpty());assertTrue(result.findings().isEmpty());
        } finally { Thread.interrupted(); }
    }
    @Test void unionSelectionDoesNotReorderUnchangedAiVerificationBatch() {
        var c=context("첫 발언","다음 발언");var off=new Harness(false,t -> answer(t,false));var on=new Harness(false,t -> answer(t,false));
        var reverse=new Discovery(List.of("stt-index-0","stt-index-1"),List.of(
                new Proposal("stt-index-1","EXPRESSION_CONTENT","후반 후보 가설",List.of(new Quote("stt-index-1","다음 발언"))),
                new Proposal("stt-index-0","EXPRESSION_CONTENT","전반 후보 가설",List.of(new Quote("stt-index-0","첫 발언")))),List.of(),false);
        when(off.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenReturn(Optional.of(reverse));
        when(on.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenReturn(Optional.of(reverse));
        off.run(c,false,24,null);on.run(c,true,24,null);
        assertEquals(off.requests,on.requests);assertEquals(off.systems,on.systems);
    }
}
