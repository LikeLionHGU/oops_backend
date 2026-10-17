package com.example.oops.analyzer;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.SourceUnderstandingContract.Version.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored regressions for saved failure mechanisms; not API outputs or independent semantic gold. */
class SourceUnderstandingCompactTest {
    final SourceUnderstandingRelationsTest old=new SourceUnderstandingRelationsTest();
    final CandidateReviewEngineTest f=old.f;
    SourceUnderstanding reading() { return new SourceUnderstanding(null,old.reading().relations()); }
    SourceUnderstanding with(SourceUnderstanding.RelationReading link) { return new SourceUnderstanding(null,List.of(link)); }
    String failure(SourceUnderstanding r) { return SourceUnderstandingContract.failure(old.candidate(),r,true,RELATIONS_V3); }

    @Test void canonicalListHasNoDuplicateActArrayOrContextLinkAndDoesNotConvertLegacyPayloads() {
        var r=reading();String bytes=JSON.writeValueAsString(r);
        assertThat(bytes).contains("relations","respondsToId").doesNotContain("acts","contextIds");
        assertThat(JSON.readValue(bytes,SourceUnderstanding.class)).isEqualTo(r);
        assertThat(failure(r)).isNull();assertThat(r.acts()).isNull();
        assertThat(failure(old.reading())).isEqualTo("UNEXPECTED_SOURCE_UNDERSTANDING_ACTS");
        assertThat(SourceUnderstandingContract.failure(old.candidate(),r,true,RELATIONS_V2)).isEqualTo("SOURCE_UNDERSTANDING_ACTS");
        // Same failure mechanism as the stored v2 responses; no historical output is rewritten.
        var missingDuplicate=new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(old.anchor,"TERM_EXPLANATION",List.of())),r.relations());
        assertThat(SourceUnderstandingContract.failure(old.candidate(),missingDuplicate,true,RELATIONS_V2)).isEqualTo("SOURCE_RELATION_READING_LINK");
    }

    @Test void inlineQuotationDoesNotRequireInventingAnotherSourceLine() {
        var inline=new SourceUnderstanding.RelationReading(old.anchor,"QUOTED_SPEECH","NONE",null,
                "EXPRESSION_MEANING",List.of(old.anchor),"NOT_APPLICABLE",List.of());
        assertThat(failure(with(inline))).isNull();
        // A declared cross-line quotation still requires a real different source ID.
        var cross=new SourceUnderstanding.RelationReading(old.anchor,"QUOTED_SPEECH","QUOTATION",null,
                "EXPRESSION_MEANING",List.of(old.anchor),"NOT_APPLICABLE",List.of());
        assertThat(failure(with(cross))).isEqualTo("SOURCE_RELATION_READING_LINK");
    }

    @Test void missingSelfOutsideAndSpuriousLinksRemainRejected() {
        for(String id:Arrays.asList(null,old.anchor,"outside"))
            assertThat(failure(with(old.link("MEANING_ANSWER",id,"EXPRESSION_MEANING",List.of(old.anchor),"NOT_APPLICABLE",List.of()))))
                    .isEqualTo("SOURCE_RELATION_READING_LINK");
        for(String kind:List.of("NONE","UNRESOLVED"))
            assertThat(failure(with(old.link(kind,old.before,"OTHER",List.of(old.anchor),"NOT_APPLICABLE",List.of()))))
                    .isEqualTo("SOURCE_RELATION_READING_LINK");
        assertThat(failure(with(old.link("NONE",null,"OTHER",List.of("outside"),"SELF",List.of(old.anchor)))))
                .isEqualTo("SOURCE_RELATION_READING_ABOUT");
        assertThat(failure(with(old.link("NONE",null,"OTHER",List.of(old.anchor),"SELF",List.of()))))
                .isEqualTo("SOURCE_RELATION_READING_OWNER");
    }

    @Test void missingDuplicateUnknownAndAnchorlessActsAreNotSilentlyAccepted() {
        assertThat(failure(new SourceUnderstanding(null,null))).isEqualTo("SOURCE_RELATION_READINGS_REQUIRED");
        assertThat(failure(new SourceUnderstanding(null,List.of()))).isEqualTo("SOURCE_RELATION_READINGS_REQUIRED");
        assertThat(failure(new SourceUnderstanding(null,Arrays.asList((SourceUnderstanding.RelationReading)null))))
                .isEqualTo("SOURCE_UNDERSTANDING_ID");
        var link=reading().relations().get(0);
        assertThat(failure(new SourceUnderstanding(null,List.of(link,link)))).isEqualTo("SOURCE_UNDERSTANDING_DUPLICATE");
        var unknown=new SourceUnderstanding.RelationReading(old.anchor,"NEW_TYPE","NONE",null,"OTHER",List.of(old.anchor),"NOT_APPLICABLE",List.of());
        assertThat(failure(with(unknown))).isEqualTo("SOURCE_UNDERSTANDING_TYPE");
        var noAnchor=new SourceUnderstanding.RelationReading(old.before,"OTHER","NONE",null,"OTHER",List.of(old.before),"NOT_APPLICABLE",List.of());
        assertThat(failure(with(noAnchor))).isEqualTo("SOURCE_UNDERSTANDING_ANCHOR");
    }

    @Test void preservesFrozenReferencesAndMakesVersionAnExplicitOptIn() {
        for(String base:List.of(verificationPrompt(0),verificationPrompt(8),SourceRelationPrompts.verification(0),FinalReviewBasisContract.prompt(verificationPrompt(0)))) {
            String suffix="\n고정 참고:\n"+verificationPrompt(0),source=base+suffix;
            String compact=SourceUnderstandingContract.prompt(source,RELATIONS_V3),v2=SourceUnderstandingContract.prompt(source,RELATIONS_V2);
            assertThat(compact).endsWith(source.substring(source.indexOf("# 대상 필드 형식 예시")));
            assertThat(compact).contains("문장 내부 인용","기존 대상·인용·대조·누락 정보 계약을 유지");
            assertThat(compact.length()).isLessThan(v2.length()); // Character count only; not measured API tokens.
        }
        assertThat(SourceUnderstandingContract.Version.config("relations-v3")).isEqualTo(RELATIONS_V3);
        assertThat(SourceUnderstandingContract.version(RELATIONS_V3)).isEqualTo(SourceUnderstandingCompact.VERSION);
        assertThatThrownBy(() -> SourceUnderstandingCompact.prompt("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void relationVersusRationaleContradictionRemainsSemanticNotAutomaticallyFixed() {
        var c=f.context("이 말은 무슨 뜻인가요?","그 집은 조용하다");String id=old.anchor;
        var candidate=new Candidate("c",new Proposal(id,"TARGET_TREATMENT","원문을 확인합니다.",List.of(new Quote(id,"그 집"))),c.reviewInput().segments(),false,false);
        var r=new SourceUnderstanding(null,List.of(new SourceUnderstanding.RelationReading(id,"TERM_EXPLANATION","MEANING_ANSWER",old.before,
                "EXPRESSION_MEANING",List.of(old.before,id),"NOT_APPLICABLE",List.of())));
        var d=f.assessment(id,"REVIEW_REQUIRED","그 집");
        var assessmentJson=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(d);
        assessmentJson.put("reason","뜻풀이가 아니라 현재 상대를 비하하는 말입니다.");
        d=JSON.treeToValue(assessmentJson,TextReviewEngine.LlmDecision.class);
        var response=new Verification("c",d,null,r);
        // Exposes the known gap: source provenance cannot establish semantic agreement with free-text rationale.
        assertThat(SourceUnderstandingContract.validate(candidate,response,c.reviewInput(),0,false,true,RELATIONS_V3).failureCode()).isNull();
        assertThat(response.assessment().decision()).isEqualTo("REVIEW_REQUIRED");
        assertThat(response.sourceUnderstanding().relations().get(0).actType()).isEqualTo("TERM_EXPLANATION");
        for(String decision:List.of("PASS","UNCERTAIN")) {
            var value=new Verification("c",f.assessment(id,decision,"그 집"),null,r);
            assertThat(SourceUnderstandingContract.validate(candidate,value,c.reviewInput(),0,false,true,RELATIONS_V3).failureCode()).isNull();
        }
    }

    @Test void analyzerUsesCompactContractAndDiagnosticsWithoutExtraCalls() {
        var c=f.context("그 집 이용자는 수준이 낮아");String id=old.before;
        f.discovery(List.of(id),f.proposal(id,"그 집"));
        var r=new SourceUnderstanding(null,List.of(new SourceUnderstanding.RelationReading(id,"EVALUATION","NONE",null,"PERSON_OR_BODY",List.of(id),"NOT_APPLICABLE",List.of())));
        f.verification(new Verification("candidate-1",f.assessment(id,"REVIEW_REQUIRED","그 집"),null,r));
        var analyzer=new SpeechReviewAnalyzer(f.client,true,true,24);analyzer.setSourceUnderstandingEnabled(true);analyzer.setSourceUnderstandingVersion("relations-v3");
        assertThat(analyzer.analyze(c)).hasSize(1);
        var diagnostics=analyzer.consumeReviewResult(c).orElseThrow().diagnostics().candidatePipeline();
        assertThat(diagnostics.revision()).contains(SourceUnderstandingCompact.VERSION);
        assertThat(diagnostics.evidenceCounts().get(0).sourceUnderstanding().reading()).isEqualTo(r);
        var system=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(system.capture(),anyString(),eq(VerificationResult.class));
        assertThat(system.getValue()).contains("acts/contextIds는 쓰지 않는다");
    }

    @Test void versionSelectionAloneKeepsOffPayloadAndLegacyBehavior() {
        f.discovery(List.of(old.before),f.proposal(old.before,"그 집"));
        f.verification(new Verification("candidate-1",f.assessment(old.before,"PASS","그 집")));
        var c=f.context("그 집은 조용하다");var analyzer=new SpeechReviewAnalyzer(f.client,true,true,24);analyzer.setSourceUnderstandingVersion("relations-v3");
        assertThat(analyzer.analyze(c)).isEmpty();
        assertThat(analyzer.consumeReviewResult(c).orElseThrow().diagnostics().candidatePipeline().revision()).isEqualTo(REVISION);
        var system=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(system.capture(),anyString(),eq(VerificationResult.class));
        assertThat(system.getValue()).doesNotContain("sourceUnderstanding");
    }

    @Test void repairRetainsCompactContractAndDoesNotFallbackToV2() {
        f.discovery(List.of(old.before,old.anchor),old.candidate().proposal());
        var d=f.assessment(old.anchor,"PASS","그 표현의 뜻을 설명합니다.");var inputs=new ArrayList<tools.jackson.databind.JsonNode>();
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String)call.getArgument(0)).contains("acts/contextIds는 쓰지 않는다");inputs.add(JSON.readTree((String)call.getArgument(1)));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",inputs.size()==1
                    ?new CandidateReferenceRepairTest().badId(d):d,null,inputs.size()==1?reading():old.reading()))));
        });
        var result=run(f.client,old.context,24,null,null,null,null,0,false,null,new VerificationFeatures(false,false,true,RELATIONS_V3));
        assertThat(inputs).hasSize(2);assertThat(inputs.get(0).path("raw")).isEqualTo(inputs.get(1).path("raw"));
        assertThat(inputs.get(0).path("candidates")).isEqualTo(inputs.get(1).path("candidates"));
        assertThat(result.status().name()).isEqualTo("PARTIAL");
        assertThat(result.diagnostics().candidatePipeline().repairs()).singleElement().satisfies(t ->
                assertThat(t.finalFailureCode()).isEqualTo("UNEXPECTED_SOURCE_UNDERSTANDING_ACTS"));
    }

    @Test void missingCanonicalReadingStaysPartialWithoutAdditionalRepairOrSilentLegacyFallback() {
        f.discovery(List.of(old.before,old.anchor),old.candidate().proposal());
        f.verification(new Verification("candidate-1",f.assessment(old.anchor,"PASS","그 표현의 뜻을 설명합니다."),null,old.reading()));
        var result=run(f.client,old.context,24,null,null,null,null,0,false,null,new VerificationFeatures(false,false,true,RELATIONS_V3));
        assertThat(result.status().name()).isEqualTo("PARTIAL");assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
        assertThat(result.diagnostics().candidatePipeline().evidenceCounts().get(0).sourceUnderstanding().contractFailure())
                .isEqualTo("UNEXPECTED_SOURCE_UNDERSTANDING_ACTS");
        verify(f.client).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
    }

    @Test void compactReadingDoesNotBypassExistingPassContrastContract() {
        var c=old.candidate();
        var proposal=new Proposal(old.anchor,c.proposal().axis(),c.proposal().reason(),
                List.of(new Quote(old.anchor,"그 표현의 뜻을 설명합니다."),new Quote(old.before,"그 말은 무슨 뜻인가요?")));
        var linked=new Candidate(c.candidateId(),proposal,c.raw(),false,false);
        var response=new Verification("c",f.assessment(old.anchor,"PASS","그 표현의 뜻을 설명합니다."),null,reading());
        assertThat(SourceUnderstandingContract.validate(linked,response,old.context.reviewInput(),0,false,true,RELATIONS_V3).failureCode())
                .isEqualTo("LINKED_PASS_CONTRAST_REQUIRED");
    }
}
