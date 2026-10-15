package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contract/wiring probes only. Mock PASS is not a measurement of model accuracy. */
class SourceRelationsTest {
    private static final List<String> IDS=List.of("stt-index-0","stt-index-1","stt-index-2");
    private final CandidateReviewEngineTest f=new CandidateReviewEngineTest();
    private final AnalysisContext context=f.context("이 동네에는 가게가 없어요", "그래서 집에서 만들어요", "제가 원한 맛은 아니네요");
    private SourceRelations roles() {
        return new SourceRelations(List.of(IDS.get(0)),List.of(),List.of(IDS.get(1)),List.of(IDS.get(2)),List.of(IDS.get(0)));
    }
    private Proposal proposal(SourceRelations relations) {
        return new Proposal(IDS.get(2),"TARGET_TREATMENT",null,
                IDS.stream().map(id->new Quote(id,null)).toList(),relations);
    }
    private TextReviewEngine.LlmDecision pass() {
        return f.linkedPass(IDS.get(2),context.transcript().get(2).getText(),IDS.get(0),context.transcript().get(0).getText());
    }
    private TextReviewEngine.Result runRelation() { return run(f.client,context,24,null,null,null,null,0,true); }

    @Test void relationRolesAndRawReachVerifierWithoutHypothesisOrForcedWarning() {
        f.discovery(IDS,proposal(roles())); f.verification(new Verification("candidate-1",pass()));
        var result=runRelation();
        var systems=ArgumentCaptor.forClass(String.class); var inputs=ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(systems.capture(),inputs.capture(),eq(VerificationResult.class));
        var body=JSON.readTree(inputs.getValue()); var c=body.get("candidates").get(0);
        assertThat(c.has("hypothesisNotEvidence")).isFalse();
        assertThat(c.get("sourceRelations")).isEqualTo(JSON.valueToTree(roles()));
        assertThat(c.get("proposedEvidence").get(0).get("quote").asText()).isEqualTo(context.transcript().get(0).getText());
        assertThat(body.get("raw").size()).isEqualTo(3);
        assertThat(body.get("promptRevision").asText()).isEqualTo(RELATION_REVISION);
        assertThat(systems.getValue()).contains("공격 대상으로 확정하지 않", "PASS/UNCERTAIN도 가능");
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().revision()).isEqualTo(RELATION_REVISION);
        assertThat(result.diagnostics().candidatePipeline().candidates().get(0).state()).isEqualTo("PASS");
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
    }
    @Test void verifierRepairPreservesRoleIdsRawAndReferences() {
        f.discovery(IDS,proposal(roles()));
        var bad=new CandidateReferenceRepairTest().badId(pass());
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenReturn(
                Optional.of(new VerificationResult(List.of(new Verification("candidate-1",bad)))),
                Optional.of(new VerificationResult(List.of(new Verification("candidate-1",pass())))));
        var helper=new ContextExampleSelectionTest();
        var library=helper.library(helper.archive(2),1,3000,"");
        var result=run(f.client,context,24,null,null,library,null,0,true);
        var requests=ArgumentCaptor.forClass(String.class); var systems=ArgumentCaptor.forClass(String.class);
        verify(f.client,times(2)).completeAsJson(systems.capture(),requests.capture(),eq(VerificationResult.class));
        var a=JSON.readTree(requests.getAllValues().get(0)); var b=JSON.readTree(requests.getAllValues().get(1));
        assertThat(a.get("raw")).isEqualTo(b.get("raw")); assertThat(a.get("candidates")).isEqualTo(b.get("candidates"));
        assertThat(b.get("promptRevision").asText()).isEqualTo(RELATION_REVISION);
        assertThat(systems.getAllValues().get(0)).contains("contextReference=");
        assertThat(systems.getAllValues().get(1)).isEqualTo(systems.getAllValues().get(0)+"\n"+ID_REPAIR_PROMPT);
        assertThat(b.has("repair")).isTrue(); assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isEqualTo(1);
    }
    @Test void legacyModeRejectsNewRolesAndRelationModeRejectsMissingRolesOrConclusion() {
        for(boolean enabled:List.of(false,true)) {
            var fixture=new CandidateReviewEngineTest();
            fixture.discovery(IDS,enabled?new Proposal(IDS.get(2),"TARGET_TREATMENT","결론",List.of(new Quote(IDS.get(2),null)))
                    :proposal(roles()));
            var result=run(fixture.client,context,24,null,null,null,null,0,enabled);
            verify(fixture.client,never()).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
            assertThat(result.diagnostics().candidatePipeline().candidates().get(0).failureCode()).isEqualTo("PROPOSAL_RELATION_MODE");
            assertThat(result.unassessedSegmentIds()).isNotEmpty();
        }
        var p=proposal(roles());
        f.discovery(IDS,new Proposal(p.anchorId(),p.axis(),"음식 비난",p.evidence(),p.sourceRelations()));
        assertThat(runRelation().diagnostics().candidatePipeline().invalidProposals()).isEqualTo(1);
        var malformed=new CandidateReviewEngineTest();
        malformed.discovery(IDS,new Proposal("untrusted-anchor","untrusted-axis","혼입 결론",p.evidence(),roles()));
        var trace=run(malformed.client,context,24,null,null,null,null,0,true).diagnostics().candidatePipeline().candidates().get(0);
        assertThat(trace.anchorId()).isNull(); assertThat(trace.axis()).isNull();
        assertThat(JSON.writeValueAsString(trace)).doesNotContain("untrusted-anchor","untrusted-axis","혼입 결론");
        var repairedLegacy=new CandidateReviewEngineTest();
        repairedLegacy.discovery(IDS,new Proposal(IDS.get(2),"TARGET_TREATMENT","기존 탐색 가설",List.of()));
        when(repairedLegacy.client.completeAsJson(anyString(),anyString(),eq(ProposalRepairResult.class)))
                .thenReturn(Optional.of(new ProposalRepairResult(proposal(roles()))));
        var legacyResult=run(repairedLegacy.client,context,24,null,null,null,null,0);
        assertThat(legacyResult.diagnostics().candidatePipeline().repairs().get(0).finalFailureCode()).isEqualTo("PROPOSAL_REPAIR_SCOPE_CHANGED");
        verify(repairedLegacy.client,never()).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
    }
    @Test void undeclaredRoleEvidenceMissingArraysAndDuplicateRolesFailClosed() {
        List<SourceRelations> bad=List.of(
                new SourceRelations(null,List.of(),List.of(),List.of(IDS.get(2)),List.of()),
                new SourceRelations(List.of("invented-id"),List.of(),List.of(),List.of(IDS.get(2)),List.of()),
                new SourceRelations(List.of(IDS.get(0),IDS.get(0)),List.of(),List.of(),List.of(IDS.get(2)),List.of()),
                new SourceRelations(List.of(),List.of(),List.of(),List.of(IDS.get(0)),List.of()));
        for(var roles:bad) {
            var fixture=new CandidateReviewEngineTest(); fixture.discovery(IDS,proposal(roles));
            var result=run(fixture.client,context,24,null,null,null,null,0,true);
            assertThat(result.diagnostics().candidatePipeline().invalidProposals()).isEqualTo(1);
            assertThat(result.diagnostics().candidatePipeline().discoveryRepairCalls()).isZero();
            assertThat(result.findings()).isEmpty();
            verify(fixture.client,never()).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
        }
    }
    @Test void roleOnlyEvidenceIsMaterializedWithoutRewritingRolesOrForcingReview() {
        var p=proposal(roles());
        f.discovery(IDS,new Proposal(p.anchorId(),p.axis(),null,List.of(new Quote(IDS.get(2),null)),roles()));
        f.verification(new Verification("candidate-1",pass()));
        var result=runRelation();
        var requests=ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(anyString(),requests.capture(),eq(VerificationResult.class));
        var candidate=JSON.readTree(requests.getValue()).get("candidates").get(0);
        assertThat(candidate.get("sourceRelations")).isEqualTo(JSON.valueToTree(roles()));
        assertThat(candidate.get("proposedEvidence").size()).isEqualTo(3);
        assertThat(candidate.get("proposedEvidence").toString()).contains(context.transcript().get(0).getText());
        assertThat(result.diagnostics().candidatePipeline().invalidProposals()).isZero();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
        assertThat(result.findings()).isEmpty();
    }
    @Test void situationAnchorIsNotSilentlyRelabeledAsEvaluation() {
        var relations=roles();
        f.discovery(IDS,new Proposal(IDS.get(0),"TARGET_TREATMENT",null,
                IDS.stream().map(id->new Quote(id,null)).toList(),relations));
        f.verification(new Verification("candidate-1",f.linkedPass(IDS.get(0),context.transcript().get(0).getText(),
                IDS.get(2),context.transcript().get(2).getText())));
        var result=runRelation();
        var requests=ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(anyString(),requests.capture(),eq(VerificationResult.class));
        assertThat(JSON.readTree(requests.getValue()).get("candidates").get(0).get("sourceRelations"))
                .isEqualTo(JSON.valueToTree(relations));
        assertThat(result.diagnostics().candidatePipeline().invalidProposals()).isZero();
        assertThat(result.findings()).isEmpty();
    }
    @Test void unionNeverTrimsOverBudgetOrHidesUnknownEvidence() {
        var fixture=new CandidateReviewEngineTest();
        var c=fixture.context("줄0","줄1","줄2","줄3","줄4","줄5","줄6","줄7","줄8");
        var ids=java.util.stream.IntStream.range(0,9).mapToObj(i->"stt-index-"+i).toList();
        var relations=new SourceRelations(ids.subList(0,8),List.of(),List.of(),List.of(ids.get(8)),List.of());
        fixture.discovery(ids,new Proposal(ids.get(8),"TARGET_TREATMENT",null,List.of(new Quote(ids.get(8),null)),relations));
        var result=run(fixture.client,c,24,null,null,null,null,0,true);
        assertThat(result.diagnostics().candidatePipeline().candidates().get(0).failureCode()).isEqualTo("PROPOSAL_EVIDENCE_OVER_BUDGET");
        verify(fixture.client,never()).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
        f.discovery(IDS,new Proposal(IDS.get(2),"TARGET_TREATMENT",null,List.of(new Quote("invented",null)),roles()));
        assertThat(runRelation().diagnostics().candidatePipeline().candidates().get(0).failureCode()).isEqualTo("PROPOSAL_UNKNOWN_EVIDENCE_ID");
    }
    @Test void missingResponseAndInvalidEnvelopeAreUnassessedNotPass() {
        for(boolean absent:List.of(true,false)) {
            var fixture=new CandidateReviewEngineTest();
            when(fixture.client.completeAsJson(anyString(),anyString(),eq(Discovery.class)))
                    .thenReturn(absent?Optional.empty():Optional.of(new Discovery(IDS,null,List.of(),false)));
            var result=run(fixture.client,context,24,null,null,null,null,0,true);
            assertThat(result.unassessedSegmentIds()).containsAll(IDS);
            var trace=result.diagnostics().candidatePipeline().candidates().get(0);
            assertThat(trace.state()).isEqualTo("INVALID_DISCOVERY");
            assertThat(trace.failureCode()).isEqualTo(absent?"DISCOVERY_NO_USABLE_RESPONSE":"DISCOVERY_ENVELOPE_INVALID");
            assertThat(trace.anchorId()).isNull();
            assertThat(result.diagnostics().candidatePipeline().proposed()).isZero();
            verify(fixture.client,never()).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
        }
    }
    @Test void materializedRoleEvidenceMustStillFitOneAdvertisedWindow() {
        var fixture=new CandidateReviewEngineTest();
        var c=fixture.context(java.util.stream.IntStream.range(0,21).mapToObj(i->"대화 "+i).toArray(String[]::new));
        var relations=new SourceRelations(List.of("stt-index-0"),List.of(),List.of(),
                List.of("stt-index-10"),List.of("stt-index-20"));
        when(fixture.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenAnswer(invocation->{
            var request=JSON.readTree((String)invocation.getArgument(1));
            List<String> ids=new ArrayList<>(); request.get("primaryIds").forEach(n->ids.add(n.asText()));
            return Optional.of(new Discovery(ids,ids.contains("stt-index-10")?List.of(new Proposal("stt-index-10",
                    "EXPRESSION_CONTENT",null,List.of(new Quote("stt-index-10",null)),relations)):List.of(),List.of(),false));
        });
        var result=run(fixture.client,c,24,null,null,null,null,0,true);
        assertThat(result.diagnostics().candidatePipeline().candidates()).singleElement()
                .extracting(CandidateReviewDiagnostics.Trace::failureCode).isEqualTo("PROPOSAL_WINDOW_MISMATCH");
        assertThat(result.diagnostics().candidatePipeline().candidates().get(0).windowMatch().detail())
                .isEqualTo("EVIDENCE_SPANS_DIFFERENT_WINDOWS");
        verify(fixture.client,never()).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
    }
    @Test void sourceRoleListsAreImmutableAndLegacyProposalJsonShapeIsUnchanged() {
        var mutable=new ArrayList<>(List.of("s")); var roles=new SourceRelations(mutable,List.of(),List.of(),List.of("s"),List.of());
        mutable.clear(); assertThat(roles.situationIds()).containsExactly("s");
        assertThatThrownBy(()->roles.situationIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        var old=new Proposal("s","TARGET_TREATMENT","가설",List.of(new Quote("s","원문")));
        var tree=JSON.readTree(JSON.writeValueAsString(old)); assertThat(tree.has("sourceRelations")).isFalse();
        assertThat(JSON.readValue(JSON.writeValueAsString(old),Proposal.class)).isEqualTo(old);
        assertThat(JSON.readValue(JSON.writeValueAsString(proposal(roles())),Proposal.class)).isEqualTo(proposal(roles()));
    }
    @Test void springFlagIsOffByDefaultAndExplicitSetterSelectsExperimentalRevision() {
        var legacy=new CandidateReviewEngineTest();
        legacy.discovery(List.of(IDS.get(0)),legacy.proposal(IDS.get(0),"그 집"));
        legacy.verification(new Verification("candidate-1",legacy.assessment(IDS.get(0),"PASS","그 집")));
        var defaultAnalyzer=new SpeechReviewAnalyzer(legacy.client,true,true,24);
        var legacyContext=legacy.context("그 집은 조용하다"); defaultAnalyzer.analyze(legacyContext);
        assertThat(defaultAnalyzer.consumeReviewResult(legacyContext).orElseThrow().diagnostics().candidatePipeline().revision()).isEqualTo(REVISION);
        f.discovery(IDS,proposal(roles())); f.verification(new Verification("candidate-1",pass()));
        var analyzer=new SpeechReviewAnalyzer(f.client,true,true,24); analyzer.setSourceRelationsEnabled(true);
        analyzer.analyze(context);
        assertThat(analyzer.consumeReviewResult(context).orElseThrow().diagnostics().candidatePipeline().revision()).isEqualTo(RELATION_REVISION);
    }
    @Test void visualCandidateKeepsLegacyShapeEvenWhenTextRelationsAreEnabled() {
        var visual=new Proposal(IDS.get(2),"TARGET_TREATMENT","실제 장면이 필요한 연결",List.of(new Quote(IDS.get(2),null)));
        when(f.client.completeAsJson(anyString(),anyString(),eq(Discovery.class)))
                .thenReturn(Optional.of(new Discovery(IDS,List.of(),List.of(),false,List.of(visual))));
        var result=runRelation();
        assertThat(result.diagnostics().candidatePipeline().visualCandidates()).hasSize(1);
        assertThat(result.diagnostics().candidatePipeline().invalidProposals()).isZero();
        verify(f.client,never()).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
    }
    @Test void allNineBoundaryInputsKeepRoleCuesThroughActualRequestAssembly() throws Exception {
        var suite=TextBoundaryTrial.readSuite(java.nio.file.Path.of("src/test/resources/evals/text-boundaries-37.json"));
        for(var item:suite.cases()) {
            var fixture=new CandidateReviewEngineTest(); var inputContext=fixture.context(item.speech().toArray(String[]::new));
            boolean b=item.id().equals("P-B-development-excerpt"), c=item.id().equals("P-C-development-excerpt");
            int anchor=b?17:c?4:0;
            List<Integer> evidence=b?List.of(7,8,9,14,17,18,19,20):c?List.of(0,2,4,5):List.of(0);
            var relations=b?new SourceRelations(List.of("stt-index-7"),List.of("stt-index-8"),List.of("stt-index-9"),
                    List.of("stt-index-17","stt-index-18","stt-index-19","stt-index-20"),List.of("stt-index-7","stt-index-14"))
                    :new SourceRelations(List.of(),List.of(),List.of(),List.of("stt-index-"+anchor),List.of());
            var p=new Proposal("stt-index-"+anchor,"TARGET_TREATMENT",null,
                    evidence.stream().map(i->new Quote("stt-index-"+i,null)).toList(),relations);
            when(fixture.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenAnswer(invocation->{
                var request=JSON.readTree((String)invocation.getArgument(1)); List<String> ids=new ArrayList<>();
                request.get("primaryIds").forEach(id->ids.add(id.asText()));
                return Optional.of(new Discovery(ids,ids.contains("stt-index-0")?List.of(p):List.of(),List.of(),false));
            });
            var decision=evidence.size()>1?fixture.linkedPass(p.anchorId(),item.speech().get(anchor),
                    "stt-index-"+evidence.get(0),item.speech().get(evidence.get(0)))
                    :fixture.assessment(p.anchorId(),"PASS",item.speech().get(anchor));
            // Deliberately mocked PASS even for development positives: this tests wire preservation, not labels.
            fixture.verification(new Verification("candidate-1",decision));
            var result=run(fixture.client,inputContext,24,null,null,null,null,0,true);
            var requests=ArgumentCaptor.forClass(String.class);
            verify(fixture.client).completeAsJson(anyString(),requests.capture(),eq(VerificationResult.class));
            var body=JSON.readTree(requests.getValue());
            assertThat(body.get("candidates").get(0).get("sourceRelations")).isEqualTo(JSON.valueToTree(relations));
            assertThat(body.get("candidates").get(0).has("hypothesisNotEvidence")).isFalse();
            assertThat(result.diagnostics().candidatePipeline().invalidProposals()).isZero();
            assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
            assertThat(result.findings()).isEmpty();
            if(b) assertThat(body.get("raw").toString()).contains("여기가 롯데리아가 없다 그랬거든?");
        }
    }
}
