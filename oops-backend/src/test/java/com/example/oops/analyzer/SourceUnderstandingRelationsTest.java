package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.SourceUnderstandingContract.Version.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored contract fixtures, not new human-approved speech-act gold or model accuracy evidence. */
class SourceUnderstandingRelationsTest {
    final CandidateReviewEngineTest f=new CandidateReviewEngineTest();
    final String before="stt-index-0",anchor="stt-index-1";
    final AnalysisContext context=f.context("그 말은 무슨 뜻인가요?","그 표현의 뜻을 설명합니다.");
    Candidate candidate() { return new Candidate("c",new Proposal(anchor,"TARGET_TREATMENT","원문 연결을 검토합니다.",
            List.of(new Quote(anchor,"그 표현의 뜻을 설명합니다."))),context.reviewInput().segments().stream()
            .filter(s -> s.type()==com.example.oops.domain.TimelineEventType.SPEECH).toList(),false,false); }
    SourceUnderstanding.RelationReading link(String relation,String target,String about,List<String> aboutIds,String owner,List<String> ownerIds) {
        return new SourceUnderstanding.RelationReading(anchor,"TERM_EXPLANATION",relation,target,about,aboutIds,owner,ownerIds);
    }
    SourceUnderstanding reading(SourceUnderstanding.RelationReading link) {
        return new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(anchor,"TERM_EXPLANATION",List.of(before))),List.of(link));
    }
    SourceUnderstanding reading() { return reading(link("MEANING_ANSWER",before,"EXPRESSION_MEANING",List.of(before,anchor),"NOT_APPLICABLE",List.of())); }
    String failure(SourceUnderstanding reading) { return SourceUnderstandingContract.failure(candidate(),reading,true,RELATIONS_V2); }

    @Test void legacyJsonAndValidationRemainV1AndV2CannotSilentlyFallback() {
        var v1=new SourceUnderstanding(reading().acts());
        assertThat(JSON.writeValueAsString(v1)).doesNotContain("relations");
        assertThat(JSON.readValue(JSON.writeValueAsString(v1),SourceUnderstanding.class)).isEqualTo(v1);
        assertThat(SourceUnderstandingContract.failure(candidate(),v1,true)).isNull();
        assertThat(failure(v1)).isEqualTo("SOURCE_RELATION_READINGS_REQUIRED");
        assertThat(SourceUnderstandingContract.failure(candidate(),reading(),true)).isEqualTo("UNEXPECTED_SOURCE_RELATION_READINGS");
        assertThat(SourceUnderstandingContract.failure(candidate(),reading(),false,RELATIONS_V2)).isEqualTo("UNEXPECTED_SOURCE_UNDERSTANDING");
        assertThat(JSON.readValue(JSON.writeValueAsString(reading()),SourceUnderstanding.class)).isEqualTo(reading());
    }

    @Test void requiresOneRelationForEachSelectedActAndRejectsDuplicatesOrOutsideIds() {
        assertThat(failure(reading())).isNull();
        assertThat(failure(new SourceUnderstanding(reading().acts(),List.of()))).isEqualTo("SOURCE_RELATION_READINGS_REQUIRED");
        assertThat(failure(new SourceUnderstanding(reading().acts(),Arrays.asList((SourceUnderstanding.RelationReading)null))))
                .isEqualTo("SOURCE_RELATION_READING_ID");
        var outside=new SourceUnderstanding.RelationReading("outside","OTHER","NONE",null,"OTHER",List.of(anchor),"NOT_APPLICABLE",List.of());
        assertThat(failure(reading(outside))).isEqualTo("SOURCE_RELATION_READING_ID");
        var acts=List.of(new SourceUnderstanding.ActReading(anchor,"TERM_EXPLANATION",List.of(before)),
                new SourceUnderstanding.ActReading(before,"DIRECT_REQUEST",List.of()));
        assertThat(failure(new SourceUnderstanding(acts,List.of(reading().relations().get(0),reading().relations().get(0)))))
                .isEqualTo("SOURCE_RELATION_READING_ID");
        // Different acts on one segment keep their own relation rather than silently collapsing meaning.
        var mixed=new SourceUnderstanding(List.of(reading().acts().get(0),new SourceUnderstanding.ActReading(anchor,"OTHER",List.of(before))),
                List.of(reading().relations().get(0),new SourceUnderstanding.RelationReading(anchor,"OTHER","NONE",null,"OTHER",List.of(anchor),"SELF",List.of(anchor))));
        assertThat(failure(mixed)).isNull();
    }

    @Test void actualLinksMustUseDifferentAllowedContextWhileNoneAndUnresolvedUseNull() {
        for(String target:Arrays.asList(null,anchor,"outside"))
            assertThat(failure(reading(link("MEANING_ANSWER",target,"EXPRESSION_MEANING",List.of(before),"NOT_APPLICABLE",List.of()))))
                    .isEqualTo("SOURCE_RELATION_READING_LINK");
        var missingContext=new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(anchor,"TERM_EXPLANATION",List.of())),reading().relations());
        assertThat(failure(missingContext)).isEqualTo("SOURCE_RELATION_READING_LINK");
        for(String relation:List.of("NONE","UNRESOLVED")) {
            assertThat(failure(reading(link(relation,null,"UNRESOLVED",List.of(),"UNRESOLVED",List.of())))).isNull();
            assertThat(failure(reading(link(relation,before,"UNRESOLVED",List.of(),"UNRESOLVED",List.of()))))
                    .isEqualTo("SOURCE_RELATION_READING_LINK");
        }
    }

    @Test void rejectsUnknownTypesAndUnsupportedTopicAndActorEvidence() {
        assertThat(failure(reading(link("MADE_UP",before,"OTHER",List.of(anchor),"SELF",List.of(anchor)))))
                .isEqualTo("SOURCE_RELATION_READING_TYPE");
        assertThat(failure(reading(link("NONE",null,"MADE_UP",List.of(anchor),"SELF",List.of(anchor)))))
                .isEqualTo("SOURCE_RELATION_READING_TYPE");
        assertThat(failure(reading(link("NONE",null,"OTHER",List.of(anchor),"MADE_UP",List.of(anchor)))))
                .isEqualTo("SOURCE_RELATION_READING_TYPE");
        for(var ids:Arrays.asList(List.<String>of(),List.of("outside"),List.of(anchor,anchor)))
            assertThat(failure(reading(link("NONE",null,"OTHER",ids,"NOT_APPLICABLE",List.of()))))
                    .isEqualTo("SOURCE_RELATION_READING_ABOUT");
        for(String owner:List.of("SELF","ADDRESSEE","OTHER")) {
            assertThat(failure(reading(link("NONE",null,"OTHER",List.of(anchor),owner,List.of())))).isEqualTo("SOURCE_RELATION_READING_OWNER");
            assertThat(failure(reading(link("NONE",null,"OTHER",List.of(anchor),owner,List.of("outside"))))).isEqualTo("SOURCE_RELATION_READING_OWNER");
        }
        assertThat(failure(reading(link("NONE",null,"OTHER",List.of(anchor),"NOT_APPLICABLE",List.of(anchor)))))
                .isEqualTo("SOURCE_RELATION_READING_OWNER");
    }

    @Test void actorAndTopicKindsDoNotTurnPassOrUncertainIntoWarnings() {
        for(String about:SourceUnderstandingRelations.ABOUT) for(String owner:SourceUnderstandingRelations.OWNERS) {
            var r=reading(link("NONE",null,about,List.of(anchor),owner,
                    Set.of("NOT_APPLICABLE","UNRESOLVED").contains(owner)?List.of():List.of(anchor)));
            for(String decision:List.of("PASS","UNCERTAIN")) {
                // Mock assessment only; the contract may not infer risk from topic or actor slots.
                var d=f.assessment(anchor,decision,"그 표현의 뜻을 설명합니다.");
                assertThat(SourceUnderstandingContract.validate(candidate(),new Verification("c",d,null,r),context.reviewInput(),0,false,true,RELATIONS_V2)
                        .failureCode()).as(about+"/"+owner+"/"+decision).isNull();
            }
        }
    }

    @Test void promptKeepsFrozenSuffixAndClarifiesFunctionRatherThanQuestionForm() {
        for(String base:List.of(verificationPrompt(0),verificationPrompt(8),SourceRelationPrompts.verification(0),FinalReviewBasisContract.prompt(verificationPrompt(0)))) {
            String suffix="\n고정 참고:\n"+verificationPrompt(0);String changed=SourceUnderstandingRelations.prompt(base+suffix);
            assertThat(changed).startsWith(CandidateReviewPrompts.POLICY).endsWith(base.substring(base.indexOf("# 대상 필드 형식 예시"))+suffix);
            assertThat(changed.substring(0,changed.indexOf("# 대상 필드 형식 예시")))
                    .contains("MEANING_ANSWER","EXPRESSION_MEANING","actionOwner","기존 대상·인용·대조·누락 정보 계약을 유지")
                    .doesNotContain("actType: TERM_EXPLANATION(말의 뜻풀이)");
        }
        assertThatThrownBy(() -> SourceUnderstandingRelations.prompt("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceUnderstandingRelations.prompt(SourceUnderstandingRelations.prompt(verificationPrompt(0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void copiesRelationEvidenceAndRejectsUnknownVersionWithoutSilentDowngrade() {
        var about=new ArrayList<>(List.of(anchor));var owner=new ArrayList<>(List.of(anchor));
        var relations=new ArrayList<>(List.of(link("NONE",null,"OTHER",about,"SELF",owner)));
        var value=new SourceUnderstanding(reading().acts(),relations);about.clear();owner.clear();relations.clear();
        assertThat(value.relations()).hasSize(1);assertThat(value.relations().get(0).ownerIds()).containsExactly(anchor);
        assertThatThrownBy(() -> value.relations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(SourceUnderstandingContract.Version.config("acts-v1")).isEqualTo(ACTS_V1);
        assertThat(SourceUnderstandingContract.Version.config("relations-v2")).isEqualTo(RELATIONS_V2);
        assertThatThrownBy(() -> SourceUnderstandingContract.Version.config("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void analyzerReachesV2AndKeepsValidReviewAndDiagnosticRelation() {
        var c=f.context("그 집 이용자는 수준이 낮아");
        f.discovery(List.of(before),f.proposal(before,"그 집"));
        var r=new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(before,"EVALUATION",List.of())),
                List.of(new SourceUnderstanding.RelationReading(before,"EVALUATION","NONE",null,"PERSON_OR_BODY",List.of(before),"NOT_APPLICABLE",List.of())));
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String)call.getArgument(0)).contains("MEANING_ANSWER");
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",f.assessment(before,"REVIEW_REQUIRED","그 집"),null,r))));
        });
        var analyzer=new SpeechReviewAnalyzer(f.client,true,true,24);analyzer.setSourceUnderstandingEnabled(true);analyzer.setSourceUnderstandingVersion("relations-v2");
        assertThat(analyzer.analyze(c)).hasSize(1);
        var diagnostics=analyzer.consumeReviewResult(c).orElseThrow().diagnostics().candidatePipeline();
        assertThat(diagnostics.revision()).contains(SourceUnderstandingRelations.VERSION);
        assertThat(diagnostics.evidenceCounts().get(0).sourceUnderstanding().reading()).isEqualTo(r);
        verify(f.client,times(1)).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
    }

    @Test void selectingV2DoesNotEnableTheFeatureOrChangeOffPayloads() {
        f.discovery(List.of(before),f.proposal(before,"그 집"));
        f.verification(new Verification("candidate-1",f.assessment(before,"PASS","그 집")));
        var analyzer=new SpeechReviewAnalyzer(f.client,true,true,24);analyzer.setSourceUnderstandingVersion("relations-v2");
        var c=f.context("그 집은 조용하다");assertThat(analyzer.analyze(c)).isEmpty();
        var diagnostics=analyzer.consumeReviewResult(c).orElseThrow().diagnostics().candidatePipeline();
        assertThat(diagnostics.revision()).isEqualTo(REVISION);
        assertThat(diagnostics.evidenceCounts()).allSatisfy(t -> assertThat(t.sourceUnderstanding()).isNull());
        var systems=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(systems.capture(),anyString(),eq(VerificationResult.class));
        assertThat(systems.getValue()).doesNotContain("MEANING_ANSWER","sourceUnderstanding");
    }

    @Test void missingV2RelationsRemainPartialWithoutFallbackAndWithoutNewRepairCall() {
        f.discovery(List.of(before,anchor),candidate().proposal());
        f.verification(new Verification("candidate-1",f.assessment(anchor,"PASS","그 표현의 뜻을 설명합니다."),null,new SourceUnderstanding(reading().acts())));
        var result=run(f.client,context,24,null,null,null,null,0,false,null,new VerificationFeatures(false,false,true,RELATIONS_V2));
        assertThat(result.status().name()).isEqualTo("PARTIAL");assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
        assertThat(result.diagnostics().candidatePipeline().evidenceCounts().get(0).sourceUnderstanding().contractFailure())
                .isEqualTo("SOURCE_RELATION_READINGS_REQUIRED");
    }

    @Test void repairRetainsV2ContractAndCannotDropRelations() {
        f.discovery(List.of(before,anchor),candidate().proposal());var good=f.assessment(anchor,"PASS","그 표현의 뜻을 설명합니다.");
        var inputs=new ArrayList<tools.jackson.databind.JsonNode>();
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String)call.getArgument(0)).contains("MEANING_ANSWER","actionOwner");inputs.add(JSON.readTree((String)call.getArgument(1)));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",inputs.size()==1
                    ?new CandidateReferenceRepairTest().badId(good):good,null,inputs.size()==1?reading():new SourceUnderstanding(reading().acts())))));
        });
        var result=run(f.client,context,24,null,null,null,null,0,false,null,new VerificationFeatures(false,false,true,RELATIONS_V2));
        assertThat(inputs).hasSize(2);assertThat(inputs.get(0).path("raw")).isEqualTo(inputs.get(1).path("raw"));
        assertThat(inputs.get(0).path("candidates")).isEqualTo(inputs.get(1).path("candidates"));
        assertThat(result.status().name()).isEqualTo("PARTIAL");
        assertThat(result.diagnostics().candidatePipeline().repairs()).singleElement().satisfies(t ->
                assertThat(t.finalFailureCode()).isEqualTo("SOURCE_RELATION_READINGS_REQUIRED"));
    }
}
