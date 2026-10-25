package com.example.oops.analyzer;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PassContextScopeTest {
    final CandidateReviewEngineTest f=new CandidateReviewEngineTest();
    final AnalysisContext context=f.context("その表現の意味を説明します", "その言葉は引用です", "説明の続きです");
    final Proposal proposal=new Proposal("stt-index-1","TARGET_TREATMENT","引用を現在の要求として検討する仮説",
            List.of(new Quote("stt-index-1","その言葉は引用です"),new Quote("stt-index-2","説明の続きです")));
    Candidate candidate() { return new Candidate("candidate-1",proposal,context.reviewInput().segments(),false,false); }
    TextReviewEngine.LlmDecision pass(String contextId) {
        return f.linkedPass("stt-index-1","その言葉は引用です",contextId,"サーバーが原文を補います");
    }
    @Test void passCanUsePermittedNormalContextNotSelectedByDiscovery() {
        var result=validate(candidate(),pass("stt-index-0"),context.reviewInput(),0);
        assertThat(result.failureCode()).isNull();assertThat(result.observation().decision().name()).isEqualTo("PASS");
        assertThat(result.observation().evidence()).anySatisfy(e -> {
            assertThat(e.segmentId()).isEqualTo("stt-index-0");assertThat(e.quote()).isEqualTo("その表現の意味を説明します");
        });
    }
    @Test void proposedContextStillWorksAndAnchorAloneStillFails() {
        assertThat(validate(candidate(),pass("stt-index-2"),context.reviewInput(),0).failureCode()).isNull();
        assertThat(validate(candidate(),pass("stt-index-1"),context.reviewInput(),0).failureCode()).isEqualTo("LINKED_PASS_CONTEXT_REQUIRED");
    }
    @Test void fullTranscriptOrAnotherCandidatesWindowCannotExpandPermissions() {
        var limited=new Candidate("candidate-1",proposal,context.reviewInput().segments().subList(1,3),false,false);
        assertThat(validate(limited,pass("stt-index-0"),context.reviewInput(),0).failureCode()).isEqualTo("LINKED_PASS_CONTEXT_REQUIRED");
        assertThat(validate(candidate(),pass("unknown"),context.reviewInput(),0).failureCode()).isEqualTo("LINKED_PASS_CONTEXT_REQUIRED");
    }
    @Test void existingContrastRequirementAndNonReviewFieldsRemainEnforced() {
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(pass("stt-index-0"));tree.putNull("alternativeInterpretation");
        assertThat(validate(candidate(),JSON.treeToValue(tree,TextReviewEngine.LlmDecision.class),context.reviewInput(),0).failureCode())
                .isEqualTo("LINKED_PASS_CONTRAST_REQUIRED");
        tree.put("alternativeInterpretation","許可された原文に現在の要求はありません");tree.put("target","誰か");
        assertThat(validate(candidate(),JSON.treeToValue(tree,TextReviewEngine.LlmDecision.class),context.reviewInput(),0).failureCode())
                .isEqualTo("NON_REVIEW_FIELDS");
    }
    @Test void linkedPassContractAppliesAcrossAxesButNotToSingleLineExpressions() {
        var linkedExpression = new Candidate("candidate-expression", new Proposal("stt-index-1", "EXPRESSION_CONTENT",
                "표현과 설명의 연결을 확인", proposal.evidence()), context.reviewInput().segments(), false, false);
        assertThat(validate(linkedExpression, pass("stt-index-0"), context.reviewInput(), 0).failureCode()).isNull();
        assertThat(validate(linkedExpression, pass("stt-index-1"), context.reviewInput(), 0).failureCode())
                .isEqualTo("LINKED_PASS_CONTEXT_REQUIRED");

        var singleExpression = new Candidate("candidate-expression-single", new Proposal("stt-index-1", "EXPRESSION_CONTENT",
                "단일 표현 자체를 확인", List.of(new Quote("stt-index-1", "その言葉は引用です"))),
                context.reviewInput().segments(), false, false);
        var singlePass = f.assessment("stt-index-1", "PASS", "その言葉は引用です");
        assertThat(validate(singleExpression, singlePass, context.reviewInput(), 0).failureCode()).isNull();
    }
    @Test void productionUsesNewScopeWithoutRepairOrExtraDiscovery() {
        f.discovery(List.of("stt-index-0","stt-index-1","stt-index-2"),proposal);
        f.verification(new Verification("candidate-1",pass("stt-index-0")));
        var result=run(f.client,context,24);
        assertThat(result.status().name()).isEqualTo("SUCCESS");assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
        var input=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(contains("proposedEvidence에 선택되지 않은 허용 원문"),input.capture(),eq(VerificationResult.class));
        var request=JSON.readTree(input.getValue());
        var rawIds=new ArrayList<String>();request.path("raw").forEach(n -> rawIds.add(n.path("id").asText()));
        var proposedIds=new ArrayList<String>();request.at("/candidates/0/proposedEvidence").forEach(n -> proposedIds.add(n.path("segmentId").asText()));
        assertThat(rawIds).contains("stt-index-0");assertThat(proposedIds).doesNotContain("stt-index-0");
    }
    @Test void repairCanUseNonProposedContextWithoutChangingRawOrProposal() {
        f.discovery(List.of("stt-index-0","stt-index-1","stt-index-2"),proposal);
        var requests=new ArrayList<tools.jackson.databind.JsonNode>();
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
            requests.add(JSON.readTree((String)call.getArgument(1)));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",requests.size()==1
                    ?f.assessment("stt-index-1","PASS","その言葉は引用です"):pass("stt-index-0")))));
        });
        var result=run(f.client,context,24);
        assertThat(result.status().name()).isEqualTo("SUCCESS");assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isOne();
        assertThat(requests).hasSize(2);assertThat(requests.get(0).path("raw")).isEqualTo(requests.get(1).path("raw"));
        assertThat(requests.get(0).path("candidates")).isEqualTo(requests.get(1).path("candidates"));
        assertThat(CONTRAST_REPAIR_PROMPT).contains("proposedEvidence에 포함될 필요는 없다");
    }
}
