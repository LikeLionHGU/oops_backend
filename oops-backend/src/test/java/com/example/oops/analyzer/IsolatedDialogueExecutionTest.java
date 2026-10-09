package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.stream.IntStream;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline isolation/call-budget contracts; not evidence of real-model accuracy. */
class IsolatedDialogueExecutionTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private AnalysisContext context(int count) {
        var video = Video.builder().filename("generic.mp4").build();
        return new AnalysisContext(video, ContentGenre.GENERAL, IntStream.range(0, count)
                .mapToObj(i -> new TranscriptSegment(video, i * 4000, i * 4000 + 1000, "일반 발언 " + i)).toList(), null);
    }
    private LlmDecision pass(JsonNode s) {
        return new LlmDecision(s.get("id").asText(), "PASS", s.get("text").asText(),
                "이 원문에서는 특정 대상을 낮추는 구체적 표현이 확인되지 않습니다.",
                null, null, null, null, null, List.of());
    }
    private LlmResult segmentResponse(JsonNode input) {
        List<LlmDecision> decisions = new ArrayList<>();
        input.get("primary").forEach(s -> decisions.add(pass(s)));
        return new LlmResult(decisions);
    }
    private LlmResult unitResponse(JsonNode input) {
        var u = input.get("dialogueReviewUnits").get(0);
        JsonNode anchor = null, other = null;
        for (var s : u.get("segments")) {
            if (anchor == null && s.get("anchorEligible").asBoolean()) anchor = s;
        }
        for (var s : u.get("segments")) {
            if (!s.get("id").asText().equals(anchor.get("id").asText())) { other = s; break; }
        }
        var p = pass(anchor);
        var decision = new LlmDecision(p.segmentId(), p.decision(), p.evidenceText(), p.reason(),
                null, null, null, null, null, List.of(), List.of(
                new LlmEvidence(p.segmentId(), p.evidenceText(), "PRIMARY"),
                new LlmEvidence(other.get("id").asText(), other.get("text").asText(), "CONTEXT")),
                null, null, null, null);
        // No segment evaluations: a successful isolated dialogue must stand on its own.
        return new LlmResult(null, List.of(new DialogueReview.LlmDecision(u.get("unitId").asText(), "NO_CONNECTED_EVALUATION", decision)));
    }
    private Result run(AnalysisContext c, boolean enabled) {
        var analyzer = new SpeechReviewAnalyzer(client, enabled);
        analyzer.analyze(c);
        return analyzer.consumeReviewResult(c).orElseThrow();
    }
    private boolean isolated(JsonNode input) { return input.has("reviewMode"); }

    @Test void eachActualRequestContainsOnlyItsUnitAndNoFullBatchOrOtherUnits() {
        var c = context(18);
        Set<String> requested = new HashSet<>();
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenAnswer(call -> {
            var input = mapper.readTree((String) call.getArgument(1));
            assertThat(input.get("promptRevision").asText()).isEqualTo(PROMPT_REVISION);
            assertThat((String) call.getArgument(0)).doesNotContain("눈에 띄는 문제는 대부분 이미 안다", "영상에 넣은 장면이 있을 수 있다");
            if (!isolated(input)) {
                assertThat(input.get("requiredUnitIds").size()).isZero();
                assertThat((String) call.getArgument(0)).doesNotContain(DIALOGUE_CONTRACT);
                return Optional.of(segmentResponse(input));
            }
            assertThat(input.has("primary")).isFalse();
            assertThat((String) call.getArgument(0)).contains("유효한 JSON 객체", "소재·장르·제작자의 의도와 관계없이 같은 기준을 적용한다");
            assertThat(input.has("context")).isFalse();
            assertThat(input.get("dialogueReviewUnits").size()).isEqualTo(1);
            assertThat(input.get("requiredUnitIds").size()).isEqualTo(1);
            assertThat(requested.add(input.get("requiredUnitIds").get(0).asText())).isTrue();
            return Optional.of(unitResponse(input));
        });
        var result = run(c, true);
        assertThat(result.diagnostics().dialogue().executionMode()).isEqualTo("ISOLATED_DIALOGUE_V1");
        assertThat(result.diagnostics().dialogue().calls()).isEqualTo(requested.size());
        assertThat(result.diagnostics().dialogue().assessed()).isEqualTo(requested.size());
        assertThat(result.diagnostics().dialogue().invalidAttempts()).isZero();
    }

    @Test void dialogueTransportFailureDoesNotDiscardValidSegmentDecisionsOrBecomePass() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenAnswer(call -> {
            var input = mapper.readTree((String) call.getArgument(1));
            return isolated(input) ? Optional.empty() : Optional.of(segmentResponse(input));
        });
        var result = run(context(3), true);
        assertThat(result.unassessedSegmentIds()).isEmpty();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(result.diagnostics().dialogue().units()).allSatisfy(u -> assertThat(u.state()).isEqualTo(ReviewDiagnostics.State.CALL_FAILED));
    }

    @Test void segmentTransportFailureDoesNotDiscardIndependentDialogueAssessment() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenAnswer(call -> {
            var input = mapper.readTree((String) call.getArgument(1));
            return isolated(input) ? Optional.of(unitResponse(input)) : Optional.empty();
        });
        var result = run(context(3), true);
        assertThat(result.unassessedSegmentIds()).hasSize(3);
        assertThat(result.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(result.diagnostics().dialogue().assessed()).isEqualTo(1);
        assertThat(result.diagnostics().dialogue().units().get(0).state()).isEqualTo(ReviewDiagnostics.State.PASS);
    }

    @Test void longInputHasBoundedDialogueCallsAndVisibleUnassessedBudgetSkips() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenAnswer(call -> {
            var input = mapper.readTree((String) call.getArgument(1));
            return Optional.of(isolated(input) ? unitResponse(input) : segmentResponse(input));
        });
        var result = run(context(400), true);
        var d = result.diagnostics().dialogue();
        assertThat(d.calls()).isEqualTo(24);
        assertThat(d.budgetSkipped()).isPositive();
        assertThat(d.requested()).isGreaterThan(d.assessed());
        assertThat(d.units()).filteredOn(u -> "UNIT_CALL_BUDGET_EXHAUSTED".equals(u.failure()))
                .isNotEmpty().allSatisfy(u -> assertThat(u.state()).isEqualTo(ReviewDiagnostics.State.NOT_RETURNED));
        assertThat(result.status()).isEqualTo(AnalyzerStatus.PARTIAL);
    }

    @Test void disabledDialogueDoesNotMakeDedicatedCalls() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenAnswer(call -> {
            var input = mapper.readTree((String) call.getArgument(1));
            assertThat(isolated(input)).isFalse();
            return Optional.of(segmentResponse(input));
        });
        assertThat(run(context(3), false).diagnostics().dialogue()).isNull();
        verify(client).completeAsJson(anyString(), anyString(), eq(LlmResult.class));
    }

    @Test void sharedEvidenceRulesExcludeSegmentCoverageAndKeepTargetAndQuoteRequirements() {
        assertThat(dialogueEvidenceContract()).contains("targetReason과 TARGET 인용을 모두 채운다", "글자·공백·문장부호", "RESIDENT_GROUP")
                .doesNotContain("모든 segmentId", "후보가 없어도 evaluations", "구간별 결정");
    }

    @Test void segmentCoverageDoesNotSuppressIndependentCandidatesJustToDeduplicateCards() {
        assertThat(CONTRACT).contains("독립적인 문제 표현을 PASS 처리하지 마라", "후보 병합은 서버가 수행한다")
                .doesNotContain("롯데리아", "피식", "영양");
        assertThat(dialogueEvidenceContract()).isEqualTo(EVIDENCE_CONTRACT);
    }
}
