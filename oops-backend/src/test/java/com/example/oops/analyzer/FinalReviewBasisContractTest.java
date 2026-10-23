package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contract/scope regression with synthetic outputs, not evidence of model accuracy. */
class FinalReviewBasisContractTest {
    final String id="stt-index-0",text="신체 공개 묘사 자체를 검토하는 합성 원문입니다.";
    final CandidateReviewEngineTest f=new CandidateReviewEngineTest();
    final AnalysisContext context=f.context(text,"추가 문맥입니다.");
    Proposal proposal(String axis) { return new Proposal(id,axis,"원문의 표현과 대상 연결을 확인합니다.",List.of(new Quote(id,text))); }
    Candidate candidate(String axis) { return new Candidate("c",proposal(axis),context.reviewInput().segments(),false,false); }
    ReviewBasis basis(String axis) { return new ReviewBasis(axis,"현재 인용한 표현 자체가 독립적인 검토 근거인지 확인합니다.",List.of(id)); }
    LlmDecision expression(String category) {
        return new LlmDecision(id,"REVIEW_REQUIRED",text,"구체적인 신체 공개 묘사를 검토하는 합성 응답입니다.",category,
                null,.5,null,null,List.of(),List.of(new LlmEvidence(id,text,"PRIMARY")),null,null,null,
                "자발적인 제안이라는 해석과 독립적인 표현 자체의 검토 사유를 구별하는 합성 응답입니다.");
    }
    Validation validateNew(Candidate c,LlmDecision d,ReviewBasis b) {
        return FinalReviewBasisContract.validate(c,new Verification(c.candidateId(),d,b),context.reviewInput(),0,true);
    }
    @Test void separatesFinalExpressionBasisWithoutChangingOriginalCandidateOrRaw() {
        var c=candidate("TARGET_TREATMENT");var d=expression("SEXUAL");
        assertThat(validate(c,d,context.reviewInput(),0).failureCode()).isEqualTo("TARGET_REQUIRED");
        assertThat(validateNew(c,d,basis("EXPRESSION_CONTENT")).failureCode()).isNull();
        assertThat(c.proposal().axis()).isEqualTo("TARGET_TREATMENT");
        assertThat(c.raw()).isEqualTo(context.reviewInput().segments());
    }
    @Test void finalTargetBasisStillRequiresTargetEvenForGraphicException() {
        for(String category:List.of("SEXUAL","GRAPHIC_METAPHOR"))
            assertThat(validateNew(candidate("EXPRESSION_CONTENT"),expression(category),basis("TARGET_TREATMENT")).failureCode())
                    .isEqualTo("TARGET_REQUIRED");
    }
    @Test void targetedAbuseCannotEscapeThroughExpressionBasis() {
        for(String category:List.of("BELITTLEMENT","MOCKERY","STRONG_NEGATIVE_REVIEW","GENERALIZATION","DISCRIMINATION","HATE_SPEECH",
                "SENSITIVE_TOPIC","PROFANITY","MISINFORMATION","UNFAMILIAR_CONTEXT"))
            assertThat(validateNew(candidate("TARGET_TREATMENT"),expression(category),basis("EXPRESSION_CONTENT")).failureCode())
                    .as(category).isEqualTo("REVIEW_BASIS_CATEGORY");
    }
    @Test void expressionRequiresIndependentBasisAndCannotCarryTargetFieldsOrRoles() {
        var d=f.assessment(id,"REVIEW_REQUIRED",text);
        // First reject the targeted category; a SEXUAL response with actual target fields is rejected too.
        assertThat(validateNew(candidate("TARGET_TREATMENT"),d,basis("EXPRESSION_CONTENT")).failureCode()).isEqualTo("REVIEW_BASIS_CATEGORY");
        var sexual=new LlmDecision(d.segmentId(),d.decision(),d.evidenceText(),d.reason(),"SEXUAL",d.target(),d.score(),null,null,
                d.missingInformation(),d.evidence(),d.targetType(),d.targetRelation(),d.targetReason(),d.alternativeInterpretation());
        assertThat(validateNew(candidate("TARGET_TREATMENT"),sexual,basis("EXPRESSION_CONTENT")).failureCode()).isEqualTo("EXPRESSION_BASIS_TARGET_FIELDS");
        var e=expression("SEXUAL");var roles=new ArrayList<>(e.evidence());roles.add(new LlmEvidence(id,text,"TARGET"));
        var orphan=new LlmDecision(id,e.decision(),text,e.reason(),e.category(),null,.5,null,null,List.of(),roles,null,null,null,e.alternativeInterpretation());
        assertThat(validateNew(candidate("TARGET_TREATMENT"),orphan,basis("EXPRESSION_CONTENT")).failureCode()).isEqualTo("EXPRESSION_BASIS_TARGET_FIELDS");
    }
    @Test void missingUnknownOrUncitedBasisCannotFallbackOrInventPermissions() {
        var c=candidate("TARGET_TREATMENT");var d=expression("SEXUAL");
        assertThat(validateNew(c,d,null).failureCode()).isEqualTo("REVIEW_BASIS_REQUIRED");
        assertThat(validateNew(c,d,new ReviewBasis("OTHER","근거",List.of(id))).failureCode()).isEqualTo("REVIEW_BASIS_AXIS");
        for(String reason:Arrays.asList(null,"","x".repeat(301)))
            assertThat(validateNew(c,d,new ReviewBasis("EXPRESSION_CONTENT",reason,List.of(id))).failureCode()).isEqualTo("REVIEW_BASIS_REASON");
        List<List<String>> bad=Arrays.asList(null,List.of(),List.of(id,id),List.of("outside"),List.of("stt-index-1"),List.of(id,"stt-index-1"),Arrays.asList(id,null));
        for(var ids:bad) assertThat(validateNew(c,d,new ReviewBasis("EXPRESSION_CONTENT","근거 확인",ids)).failureCode()).isEqualTo("REVIEW_BASIS_EVIDENCE");
    }
    @Test void passAndUncertainCannotSmuggleBasisAndExistingContrastStillApplies() {
        var c=candidate("TARGET_TREATMENT");
        for(String decision:List.of("PASS","UNCERTAIN")) {
            var d=f.assessment(id,decision,text);
            assertThat(validateNew(c,d,basis("EXPRESSION_CONTENT")).failureCode()).isEqualTo("REVIEW_BASIS_FOR_NON_REVIEW");
            assertThat(validateNew(c,d,null).failureCode()).isNull();
        }
        var p=new Proposal(id,"TARGET_TREATMENT","연결 검토",List.of(new Quote(id,text),new Quote("stt-index-1","추가 문맥입니다.")));
        var linked=new Candidate("c",p,c.raw(),false,false);
        assertThat(validateNew(linked,f.assessment(id,"PASS",text),null).failureCode()).isEqualTo("LINKED_PASS_CONTRAST_REQUIRED");
    }
    @Test void oldJsonAndConstructorsKeepLegacyContractAndUnrequestedBasisIsRejected() {
        var c=candidate("TARGET_TREATMENT");var d=expression("SEXUAL");
        var legacy=new Verification("c",d);
        assertThat(JSON.writeValueAsString(legacy)).doesNotContain("reviewBasis");
        assertThat(JSON.readValue(JSON.writeValueAsString(legacy),Verification.class).reviewBasis()).isNull();
        assertThat(FinalReviewBasisContract.validate(c,legacy,context.reviewInput(),0,false).failureCode()).isEqualTo("TARGET_REQUIRED");
        var newer=new Verification("c",d,basis("EXPRESSION_CONTENT"));
        assertThat(JSON.readValue(JSON.writeValueAsString(newer),Verification.class)).isEqualTo(newer);
        assertThat(FinalReviewBasisContract.validate(c,newer,context.reviewInput(),0,false).failureCode()).isEqualTo("UNEXPECTED_REVIEW_BASIS");
    }
    @Test void supportsBothPromptVariantsWithoutTouchingLegacyConstants() {
        for(String base:List.of(verificationPrompt(0),verificationPrompt(8),SourceRelationPrompts.verification(0))) {
            String changed=FinalReviewBasisContract.prompt(base);
            assertThat(changed).contains("assessment 밖의 reviewBasis","기존 대조 해석","별도의 표현 자체 근거");
            assertThat(changed).doesNotContain("탐색 axis가 TARGET_TREATMENT였어도");
        }
        assertThat(verificationPrompt(0)).doesNotContain("assessment 밖의 reviewBasis");
        assertThatThrownBy(() -> FinalReviewBasisContract.prompt("unknown")).isInstanceOf(IllegalArgumentException.class);
        String frozenReference="\n고정 참고 원문:\n"+verificationPrompt(0);
        assertThat(FinalReviewBasisContract.prompt(verificationPrompt(0)+frozenReference)).endsWith(frozenReference);
        assertThatThrownBy(() -> FinalReviewBasisContract.prompt(FinalReviewBasisContract.prompt(verificationPrompt(0))+frozenReference))
                .isInstanceOf(IllegalArgumentException.class);
    }
    TextReviewEngine.Result runEnabled(AnalysisContext c) { return run(f.client,c,24,null,null,null,null,0,false,null,true); }
    @Test void enabledPipelinePublishesIndependentExpressionAndRecordsBothAxes() {
        var c=f.context(text);f.discovery(List.of(id),proposal("TARGET_TREATMENT"));
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            var input=JSON.readTree((String)call.getArgument(1));
            assertThat(input.path("verificationContract").asText()).isEqualTo(FinalReviewBasisContract.VERSION);
            assertThat(input.at("/candidates/0/axis").asText()).isEqualTo("TARGET_TREATMENT");
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",expression("SEXUAL"),basis("EXPRESSION_CONTENT")))));
        });
        var result=runEnabled(c);assertThat(result.status().name()).isEqualTo("SUCCESS");assertThat(result.findings()).hasSize(1);
        var trace=result.diagnostics().candidatePipeline().evidenceCounts().get(0).reviewBasis();
        assertThat(trace.discoveryAxis()).isEqualTo("TARGET_TREATMENT");assertThat(trace.returnedAxis()).isEqualTo("EXPRESSION_CONTENT");
        assertThat(trace.contractAccepted()).isTrue();
    }
    @Test void missingBasisStaysUnassessedWithoutLegacyFallbackOrRepairLoop() {
        f.discovery(List.of(id),proposal("TARGET_TREATMENT"));f.verification(new Verification("candidate-1",expression("SEXUAL")));
        var result=runEnabled(f.context(text));
        assertThat(result.status().name()).isEqualTo("PARTIAL");assertThat(result.findings()).isEmpty();
        assertThat(result.unassessedSegmentIds()).contains(id);assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
        assertThat(result.diagnostics().candidatePipeline().candidates()).anySatisfy(t -> assertThat(t.failureCode()).isEqualTo("REVIEW_BASIS_REQUIRED"));
    }
    @Test void repairKeepsNewContractAndCannotDropBasis() {
        f.discovery(List.of(id),proposal("TARGET_TREATMENT"));var inputs=new ArrayList<String>();
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            inputs.add(call.getArgument(1));assertThat((String)call.getArgument(0)).contains("assessment 밖의 reviewBasis");
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",expression("SEXUAL"),
                    inputs.size()==1?basis("TARGET_TREATMENT"):null))));
        });
        var result=runEnabled(f.context(text));assertThat(inputs).hasSize(2);
        assertThat(JSON.readTree(inputs.get(0)).path("raw")).isEqualTo(JSON.readTree(inputs.get(1)).path("raw"));
        assertThat(JSON.readTree(inputs.get(1)).path("verificationContract").asText()).isEqualTo(FinalReviewBasisContract.VERSION);
        assertThat(result.findings()).isEmpty();assertThat(result.status().name()).isEqualTo("PARTIAL");
        assertThat(result.diagnostics().candidatePipeline().repairs()).singleElement().satisfies(t -> assertThat(t.finalFailureCode()).isEqualTo("REVIEW_BASIS_REQUIRED"));
    }
    @Test void repairCanSelectIndependentBasisWithoutInventingTargetOrChangingRaw() {
        f.discovery(List.of(id),proposal("TARGET_TREATMENT"));var inputs=new ArrayList<tools.jackson.databind.JsonNode>();
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            inputs.add(JSON.readTree((String)call.getArgument(1)));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",expression("SEXUAL"),
                    basis(inputs.size()==1?"TARGET_TREATMENT":"EXPRESSION_CONTENT")))));
        });
        var result=runEnabled(f.context(text));
        assertThat(result.status().name()).isEqualTo("SUCCESS");assertThat(result.findings()).hasSize(1);
        assertThat(inputs).hasSize(2);assertThat(inputs.get(0).path("raw")).isEqualTo(inputs.get(1).path("raw"));
        assertThat(inputs.get(0).path("candidates")).isEqualTo(inputs.get(1).path("candidates"));
        assertThat(result.diagnostics().candidatePipeline().evidenceCounts()).extracting(t -> t.reviewBasis().returnedAxis())
                .containsExactly("TARGET_TREATMENT","EXPRESSION_CONTENT");
    }
    @Test void finalTargetCanReplaceDiscoveryExpressionButStillNeedsExistingGrounding() {
        var ctx=f.context("그 집 이용자를 조롱하는 합성 원문입니다.");
        var c=new Candidate("c",proposal("EXPRESSION_CONTENT"),ctx.reviewInput().segments(),false,false);
        var d=f.assessment(id,"REVIEW_REQUIRED",ctx.reviewInput().find(id).orElseThrow().text());
        assertThat(FinalReviewBasisContract.validate(c,new Verification("c",d,basis("TARGET_TREATMENT")),ctx.reviewInput(),0,true).failureCode()).isNull();
        assertThat(FinalReviewBasisContract.validate(c,new Verification("wrong",d,basis("TARGET_TREATMENT")),ctx.reviewInput(),0,true).failureCode())
                .isEqualTo("UNEXPECTED_CANDIDATE_RESPONSE");
    }
    @Test void sourceRelationModeUsesSameNewFinalBasisWithoutChangingRoleInput() {
        var roles=new SourceRelations(List.of(),List.of(),List.of(),List.of(id),List.of());
        var p=new Proposal(id,"TARGET_TREATMENT",null,List.of(new Quote(id,text)),roles);
        f.discovery(List.of(id),p);
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String)call.getArgument(0)).contains("assessment 밖의 reviewBasis","원문 관계 전달 (실험)");
            var body=JSON.readTree((String)call.getArgument(1));
            assertThat(body.at("/candidates/0/sourceRelations")).isEqualTo(JSON.valueToTree(roles));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",expression("SEXUAL"),basis("EXPRESSION_CONTENT")))));
        });
        assertThat(run(f.client,f.context(text),24,null,null,null,null,0,true,null,true).status().name()).isEqualTo("SUCCESS");
    }
    @Test void analyzerOptInActuallyReachesNewEngineContract() {
        f.discovery(List.of(id),proposal("TARGET_TREATMENT"));
        f.verification(new Verification("candidate-1",expression("SEXUAL"),basis("EXPRESSION_CONTENT")));
        var analyzer=new SpeechReviewAnalyzer(f.client,true,true,24);analyzer.setFinalReviewBasisEnabled(true);
        var ctx=f.context(text);assertThat(analyzer.analyze(ctx)).hasSize(1);
        assertThat(analyzer.consumeReviewResult(ctx).orElseThrow().diagnostics().candidatePipeline().revision()).contains(FinalReviewBasisContract.VERSION);
    }
    @Test void optInDoesNotChangeRawWindowReferencesOrDiscoveryAxis() {
        var requests=new ArrayList<tools.jackson.databind.JsonNode>();
        for(boolean enabled:List.of(false,true)) {
            var x=new CandidateReviewEngineTest();x.discovery(List.of(id),proposal("TARGET_TREATMENT"));
            when(x.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
                requests.add(JSON.readTree((String)call.getArgument(1)));
                return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",x.assessment(id,"PASS",text)))));
            });
            assertThat(run(x.client,x.context(text),24,null,null,null,null,0,false,null,enabled).status().name()).isEqualTo("SUCCESS");
        }
        ((tools.jackson.databind.node.ObjectNode)requests.get(1)).remove("verificationContract");
        assertThat(requests.get(1)).isEqualTo(requests.get(0));
    }
    @Test void springFlagIsOffByDefaultAndLegacyAnalyzerRequestsRemainUnchanged() throws Exception {
        var component=Arrays.stream(com.example.oops.config.SpeechReviewProperties.class.getRecordComponents())
                .filter(c -> c.getName().equals("finalReviewBasisEnabled")).findFirst().orElseThrow();
        assertThat(component.getAccessor()).isNotNull();
        var constructor=com.example.oops.config.SpeechReviewProperties.class.getDeclaredConstructors()[0];
        var parameter=Arrays.stream(constructor.getParameters())
                .filter(p -> p.getName().equals("finalReviewBasisEnabled")).findFirst().orElseThrow();
        assertThat(parameter.getAnnotation(org.springframework.boot.context.properties.bind.DefaultValue.class).value())
                .containsExactly("false");
        try(var in=getClass().getResourceAsStream("/application.yml")) {
            assertThat(new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))
                    .contains("final-review-basis-enabled: ${FINAL_REVIEW_BASIS_ENABLED:false}");
        }
        f.discovery(List.of(id),proposal("TARGET_TREATMENT"));f.verification(new Verification("candidate-1",f.assessment(id,"PASS",text)));
        var analyzer=new SpeechReviewAnalyzer(f.client,true,true,24);assertThat(analyzer.analyze(f.context(text))).isEmpty();
        var input=ArgumentCaptor.forClass(String.class);verify(f.client).completeAsJson(anyString(),input.capture(),eq(VerificationResult.class));
        assertThat(input.getValue()).doesNotContain("verificationContract");
    }
}
