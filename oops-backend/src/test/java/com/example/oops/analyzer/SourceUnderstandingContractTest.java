package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock/contract tests only. Valid act labels do not establish semantic truth or model accuracy. */
class SourceUnderstandingContractTest {
    final CandidateReviewEngineTest f = new CandidateReviewEngineTest();
    final String id = "stt-index-0", text = "그 집";
    final AnalysisContext context = f.context("그 집은 조용하다", "추가 설명입니다.");
    Candidate candidate() { return new Candidate("c", f.proposal(id,text), context.reviewInput().segments().stream()
            .filter(s -> s.type()==com.example.oops.domain.TimelineEventType.SPEECH).toList(), false, false); }
    SourceUnderstanding reading(String type) {
        return new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(id,type,List.of("stt-index-1"))));
    }
    Validation validate(SourceUnderstanding reading,String decision,boolean enabled) {
        return SourceUnderstandingContract.validate(candidate(),new Verification("c",f.assessment(id,decision,text),null,reading),
                context.reviewInput(),0,false,enabled);
    }

    @Test void oldJsonAndConstructorsRemainCompatibleAndUnrequestedReadingsAreRejected() {
        var old=new Verification("c",f.assessment(id,"PASS",text));
        assertThat(JSON.writeValueAsString(old)).doesNotContain("sourceUnderstanding");
        assertThat(JSON.readValue(JSON.writeValueAsString(old),Verification.class).sourceUnderstanding()).isNull();
        assertThat(validate(null,"PASS",false).failureCode()).isNull();
        assertThat(validate(reading("TERM_EXPLANATION"),"PASS",false).failureCode()).isEqualTo("UNEXPECTED_SOURCE_UNDERSTANDING");
        assertThat(validate(null,"PASS",true).failureCode()).isEqualTo("SOURCE_UNDERSTANDING_REQUIRED");
        var newer=new Verification("c",old.assessment(),null,reading("EVALUATION"));
        assertThat(JSON.readValue(JSON.writeValueAsString(newer),Verification.class)).isEqualTo(newer);
    }

    @Test void actTypesNeverAutomaticallyChoosePassReviewOrUncertain() {
        for(String type:SourceUnderstanding.TYPES) for(String decision:List.of("PASS","REVIEW_REQUIRED","UNCERTAIN")) {
            var result=validate(reading(type),decision,true);
            assertThat(result.failureCode()).as(type+"/"+decision).isNull();
            assertThat(result.observation()).isNotNull();
        }
    }

    @Test void requiresKnownIdsKnownTypesAnchorAndUniqueActPairs() {
        var c=candidate();
        assertThat(new SourceUnderstanding(null).failure(c)).isEqualTo("SOURCE_UNDERSTANDING_ACTS");
        assertThat(new SourceUnderstanding(List.of()).failure(c)).isEqualTo("SOURCE_UNDERSTANDING_ACTS");
        assertThat(new SourceUnderstanding(Arrays.asList((SourceUnderstanding.ActReading)null)).failure(c)).isEqualTo("SOURCE_UNDERSTANDING_ID");
        assertThat(new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading("outside","OTHER",List.of()))).failure(c))
                .isEqualTo("SOURCE_UNDERSTANDING_ID");
        assertThat(reading("INVENTED").failure(c)).isEqualTo("SOURCE_UNDERSTANDING_TYPE");
        assertThat(reading(null).failure(c)).isEqualTo("SOURCE_UNDERSTANDING_TYPE");
        var a=reading("OTHER").acts().get(0);
        assertThat(new SourceUnderstanding(List.of(a,a)).failure(c)).isEqualTo("SOURCE_UNDERSTANDING_DUPLICATE");
        assertThat(new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading("stt-index-1","OTHER",List.of(id)))).failure(c))
                .isEqualTo("SOURCE_UNDERSTANDING_ANCHOR");
    }

    @Test void rejectsUnknownDuplicateSelfOrMissingContextButAllowsMultipleActsOnOneLine() {
        List<List<String>> invalid=Arrays.asList(null,List.of("outside"),List.of(id),List.of("stt-index-1","stt-index-1"),Arrays.asList((String)null));
        for(var ids:invalid) assertThat(new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(id,"OTHER",ids))).failure(candidate()))
                .isEqualTo("SOURCE_UNDERSTANDING_CONTEXT");
        var mixed=new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(id,"TERM_EXPLANATION",List.of()),
                new SourceUnderstanding.ActReading(id,"DIRECT_REQUEST",List.of("stt-index-1"))));
        assertThat(mixed.failure(candidate())).isNull();
        var acts=new ArrayList<SourceUnderstanding.ActReading>();
        for(int i=0;i<candidate().raw().size()*SourceUnderstanding.TYPES.size()+1;i++)
            acts.add(new SourceUnderstanding.ActReading(id,"OTHER",List.of()));
        assertThat(new SourceUnderstanding(acts).failure(candidate())).isEqualTo("SOURCE_UNDERSTANDING_ACTS");
    }

    @Test void copiesNestedCollectionsAndCannotBypassExistingTargetContract() {
        var ids=new ArrayList<>(List.of("stt-index-1"));
        var acts=new ArrayList<>(List.of(new SourceUnderstanding.ActReading(id,"OTHER",ids)));
        var value=new SourceUnderstanding(acts);ids.clear();acts.clear();
        assertThat(value.acts()).hasSize(1);assertThat(value.acts().get(0).contextIds()).containsExactly("stt-index-1");
        assertThatThrownBy(() -> value.acts().clear()).isInstanceOf(UnsupportedOperationException.class);
        var b=new FinalReviewBasisContractTest();
        var response=new Verification("c",b.expression("SEXUAL"),null,
                new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(b.id,"SELF_OFFER",List.of()))));
        assertThat(SourceUnderstandingContract.validate(b.candidate("TARGET_TREATMENT"),response,b.context.reviewInput(),0,false,true).failureCode())
                .isEqualTo("TARGET_REQUIRED");
    }

    @Test void promptPreservesFrozenReferencesAndSupportsAllExistingPromptVariants() {
        for(String base:List.of(verificationPrompt(0),verificationPrompt(8),SourceRelationPrompts.verification(0),
                FinalReviewBasisContract.prompt(verificationPrompt(0)))) {
            String suffix="\n고정 참고:\n"+verificationPrompt(0);
            String output=SourceUnderstandingContract.prompt(base+suffix);
            assertThat(output).startsWith(CandidateReviewPrompts.POLICY).endsWith(base.substring(base.indexOf("# 대상 필드 형식 예시"))+suffix);
            String body=output.substring(0,output.indexOf("# 대상 필드 형식 예시"));
            assertThat(body).contains("TERM_EXPLANATION","SELF_OFFER","분류는 판정이 아니다","\"sourceUnderstanding\":{\"acts\"");
            assertThat(body).doesNotContain("별도 사고 과정·새 JSON 필드는 반환하지 않는다");
        }
        assertThatThrownBy(() -> SourceUnderstandingContract.prompt("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceUnderstandingContract.prompt(SourceUnderstandingContract.prompt(verificationPrompt(0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(verificationSystem(verificationPrompt(0),new VerificationFeatures(false,true,true)))
                .isEqualTo(SourceUnderstandingContract.prompt(verificationPrompt(0)));
    }

    TextReviewEngine.Result runEnabled() {
        return run(f.client,context,24,null,null,null,null,0,false,null,new VerificationFeatures(false,false,true));
    }
    void discover() { f.discovery(List.of(id,"stt-index-1"),f.proposal(id,text)); }

    @Test void recordsReadingInDiagnosticsWithoutAddingCallsOrRawText() {
        discover();f.verification(new Verification("candidate-1",f.assessment(id,"PASS",text),null,reading("EVALUATION")));
        var result=runEnabled();assertThat(result.status().name()).isEqualTo("SUCCESS");
        var trace=result.diagnostics().candidatePipeline().evidenceCounts().get(0).sourceUnderstanding();
        assertThat(trace.provided()).isTrue();assertThat(trace.contractFailure()).isNull();assertThat(trace.reading()).isEqualTo(reading("EVALUATION"));
        assertThat(JSON.writeValueAsString(trace)).doesNotContain("그 집","추가 설명");
        assertThat(result.diagnostics().candidatePipeline().revision()).contains(SourceUnderstandingContract.VERSION);
        verify(f.client,times(1)).completeAsJson(anyString(),anyString(),eq(Discovery.class));
        verify(f.client,times(1)).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
    }

    @Test void missingOrInvalidReadingStaysPartialWithoutFallbackRepairOrUnsafeTrace() {
        for(var value:Arrays.asList(null,reading("UNKNOWN"))) {
            reset(f.client);discover();f.verification(new Verification("candidate-1",f.assessment(id,"PASS",text),null,value));
            var result=runEnabled();assertThat(result.status().name()).isEqualTo("PARTIAL");assertThat(result.findings()).isEmpty();
            assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
            var trace=result.diagnostics().candidatePipeline().evidenceCounts().get(0).sourceUnderstanding();
            assertThat(trace.contractFailure()).startsWith("SOURCE_UNDERSTANDING_");assertThat(trace.reading()).isNull();
        }
    }

    @Test void repairKeepsSameContractAndDiagnosesDroppedReadingWithoutAnotherCall() {
        discover();var inputs=new ArrayList<tools.jackson.databind.JsonNode>();
        var good=f.assessment(id,"PASS",text);
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String)call.getArgument(0)).contains("sourceUnderstanding", "TERM_EXPLANATION");
            inputs.add(JSON.readTree((String)call.getArgument(1)));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",inputs.size()==1
                    ?new CandidateReferenceRepairTest().badId(good):good,null,inputs.size()==1?reading("EVALUATION"):null))));
        });
        var result=runEnabled();assertThat(result.status().name()).isEqualTo("PARTIAL");
        assertThat(inputs).hasSize(2);assertThat(inputs.get(0).path("raw")).isEqualTo(inputs.get(1).path("raw"));
        assertThat(inputs.get(0).path("candidates")).isEqualTo(inputs.get(1).path("candidates"));
        assertThat(result.diagnostics().candidatePipeline().repairs()).singleElement().satisfies(t ->
                assertThat(t.finalFailureCode()).isEqualTo("SOURCE_UNDERSTANDING_REQUIRED"));
        assertThat(result.diagnostics().candidatePipeline().evidenceCounts()).hasSize(2);
    }

    @Test void analyzerOptInActuallyReachesObservableContract() {
        discover();f.verification(new Verification("candidate-1",f.assessment(id,"REVIEW_REQUIRED",text),null,reading("EVALUATION")));
        var analyzer=new SpeechReviewAnalyzer(f.client,true,true,24);analyzer.setSourceUnderstandingEnabled(true);
        assertThat(analyzer.analyze(context)).hasSize(1);
        assertThat(analyzer.consumeReviewResult(context).orElseThrow().diagnostics().candidatePipeline().evidenceCounts().get(0)
                .sourceUnderstanding().provided()).isTrue();
    }

    @Test void repairCanRecoverWithValidUnderstandingAndRecordsBothAttempts() {
        discover();var good=f.assessment(id,"PASS",text);var inputs=new ArrayList<tools.jackson.databind.JsonNode>();
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            inputs.add(JSON.readTree((String)call.getArgument(1)));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",inputs.size()==1
                    ?new CandidateReferenceRepairTest().badId(good):good,null,reading("EVALUATION")))));
        });
        var result=runEnabled();assertThat(result.status().name()).isEqualTo("SUCCESS");assertThat(inputs).hasSize(2);
        assertThat(result.diagnostics().candidatePipeline().evidenceCounts()).hasSize(2).allSatisfy(t -> {
            assertThat(t.sourceUnderstanding().contractFailure()).isNull();
            assertThat(t.sourceUnderstanding().reading()).isEqualTo(reading("EVALUATION"));
        });
    }

    @Test void worksWithSourceRelationDiscoveryAndFinalBasisWithoutInventingTarget() {
        var b=new FinalReviewBasisContractTest();
        var roles=new SourceRelations(List.of(),List.of(),List.of(),List.of(b.id),List.of());
        b.f.discovery(List.of(b.id),new Proposal(b.id,"TARGET_TREATMENT",null,List.of(new Quote(b.id,b.text)),roles));
        when(b.f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String)call.getArgument(0)).contains("sourceUnderstanding", "assessment 밖의 reviewBasis", "원문 관계 전달 (실험)");
            assertThat(JSON.readTree((String)call.getArgument(1)).at("/candidates/0/sourceRelations")).isEqualTo(JSON.valueToTree(roles));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",b.expression("SEXUAL"),
                    b.basis("EXPRESSION_CONTENT"),new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(b.id,"OTHER",List.of())))))));
        });
        var result=run(b.f.client,b.f.context(b.text),24,null,null,null,null,0,true,null,new VerificationFeatures(true,true,true));
        assertThat(result.status().name()).isEqualTo("SUCCESS");assertThat(result.findings()).hasSize(1);
        assertThat(result.diagnostics().candidatePipeline().evidenceCounts().get(0).sourceUnderstanding().provided()).isTrue();
    }
}
