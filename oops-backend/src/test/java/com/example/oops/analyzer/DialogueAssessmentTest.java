package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.IntStream;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Active dialogue path with simulated responses, not real-model accuracy evidence. */
class DialogueAssessmentTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final SpeechReviewAnalyzer analyzer = new SpeechReviewAnalyzer(client);
    private final Video video = Video.builder().filename("unit.mp4").build();
    private final String[] lines = {"이 마을 주민들은", "대신 이 음식을 먹는대", "그래서 수준 낮은 사람들이라는 거지"};
    private AnalysisContext context(String[] lines) {
        return new AnalysisContext(video, ContentGenre.GENERAL, IntStream.range(0, lines.length)
                .mapToObj(i -> new TranscriptSegment(video, i * 2000, i * 2000 + 1000, lines[i])).toList(), null);
    }
    private DialogueReview.Unit unit(AnalysisContext context) {
        return DialogueReview.plan(TextReviewBatchPlanner.plan(context.reviewInput(), TimelineEventType.SPEECH, 3).get(0)).units().get(0);
    }
    private List<LlmDecision> passes(String[] text) {
        return IntStream.range(0, text.length).mapToObj(i -> new LlmDecision("stt-index-" + i, "PASS", text[i],
                "단독 표현에서는 별도의 구체적인 검토 이유가 없으며 대화 흐름은 묶음에서 따로 평가합니다.",
                null, null, null, null, null, List.of())).toList();
    }
    private LlmDecision risk(String quote, String type, boolean targetEvidence) {
        var evidence = new ArrayList<>(List.of(new LlmEvidence("stt-index-2", quote, "PRIMARY"),
                new LlmEvidence("stt-index-1", lines[1], "CONTEXT")));
        if (targetEvidence) evidence.add(new LlmEvidence("stt-index-0", lines[0], "TARGET"));
        return new LlmDecision("stt-index-2", "REVIEW_REQUIRED", quote,
                "지역 주민이 특정 음식을 대신 먹는다는 설명을 주민의 수준이 낮다는 평가로 연결합니다.",
                "BELITTLEMENT", "마을 주민", 0.6, null, null, List.of(), evidence,
                type, "CONTEXTUAL", "앞서 마을 주민을 지칭한 발언이 뒤의 평가 대상과 연결됩니다.",
                "화자가 타인의 비하 발언을 비판적으로 인용하는 상황인지 확인이 필요합니다.");
    }
    private DialogueReview.LlmDecision unitRisk(AnalysisContext c) {
        return new DialogueReview.LlmDecision(unit(c).unitId(), "SAME_TARGET_CONNECTED", risk(lines[2], "RESIDENT_GROUP", true));
    }
    private Result run(AnalysisContext c) {
        analyzer.analyze(c);
        return analyzer.consumeReviewResult(c).orElseThrow();
    }
    @Test void groundedUnitCandidatePublishesDespiteStandalonePassWithoutChangingSegmentStates() {
        var c = context(lines);
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(passes(lines), List.of(unitRisk(c)))));
        var result = run(c);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).getTarget()).isEqualTo("마을 주민");
        assertThat(result.diagnostics().segments()).allSatisfy(s -> assertThat(s.state()).isEqualTo(ReviewDiagnostics.State.PASS));
        assertThat(result.diagnostics().dialogue().units().get(0).state()).isEqualTo(ReviewDiagnostics.State.REVIEW_REQUIRED);
        assertThat(result.conflictingSegmentIds()).isEmpty();
        verify(client).completeAsJson(argThat(s -> s.contains(DIALOGUE_CONTRACT)),
                argThat(s -> s.contains("requiredUnitIds")), eq(LlmResult.class));
    }
    @Test void missingUnitDecisionIsVisibleNotAnImplicitPassOrAnExtraRepairCall() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(passes(lines))));
        var result = run(context(lines));
        assertThat(result.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().dialogue().assessed()).isZero();
        assertThat(result.diagnostics().dialogue().units().get(0).state()).isEqualTo(ReviewDiagnostics.State.NOT_RETURNED);
        assertThat(result.notice()).contains("대화 묶음", "미판정 1");
        assertThat(result.notice().length()).isLessThanOrEqualTo(300);
        verify(client, times(2)).completeAsJson(anyString(), anyString(), eq(LlmResult.class));
    }
    @ParameterizedTest @ValueSource(strings = {"QUOTE", "TYPE", "TARGET", "RELATION", "UNKNOWN_UNIT"})
    void invalidUnitDecisionDoesNotBypassExistingEvidenceAndTargetGuards(String invalid) {
        var c = context(lines);
        var decision = risk(invalid.equals("QUOTE") ? "원문에 없는 인용" : lines[2],
                invalid.equals("TYPE") ? "VILLAGERS" : "RESIDENT_GROUP", !invalid.equals("TARGET"));
        var item = new DialogueReview.LlmDecision(invalid.equals("UNKNOWN_UNIT") ? "untrusted-unit" : unit(c).unitId(),
                invalid.equals("RELATION") ? "NO_CONNECTED_EVALUATION" : "SAME_TARGET_CONNECTED", decision);
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(passes(lines), List.of(item))));
        var result = run(c);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().dialogue().invalidAttempts()).isEqualTo(1);
        assertThat(result.diagnostics().dialogue().toString()).doesNotContain("원문에 없는 인용", "untrusted-unit", "VILLAGERS");
    }
    @Test void duplicatedUnitDecisionIsAConflictNotTheHigherScoredWinner() {
        var c = context(lines);
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(passes(lines), List.of(unitRisk(c), unitRisk(c)))));
        var result = run(c);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().dialogue().conflicts()).isEqualTo(1);
        assertThat(result.diagnostics().dialogue().units().get(0).state()).isEqualTo(ReviewDiagnostics.State.CONFLICT);
    }
    @ParameterizedTest @ValueSource(strings = {
            "이곳에는 매장이 없네요|집에서 대신 만들어요|제 입에는 담백하네요",
            "새우를 못 먹어요|대신 버섯을 넣었어요|찌개 같은 느낌이에요",
            "메뉴가 여러 개네요|주는 대로 먹어봤어요|제 취향에는 안 맞아요"})
    void connectedNormalReviewsDoNotBecomeWarningsWhenBothScopesReturnPass(String sample) {
        var text = sample.split("\\|");
        var c = context(text);
        var assessment = new LlmDecision("stt-index-2", "PASS", text[2],
                "선택 조건과 대체 음식의 설명 뒤 개인적인 맛 감상을 말하는 흐름이며 대상을 낮추는 연결 근거는 없습니다.",
                null, null, null, null, null, List.of(), List.of(
                        new LlmEvidence("stt-index-2", text[2], "PRIMARY"),
                        new LlmEvidence("stt-index-0", text[0], "CONTEXT")), null, null, null, null);
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(passes(text), List.of(new DialogueReview.LlmDecision(
                        unit(c).unitId(), "SAME_TARGET_CONNECTED", assessment)))));
        var result = run(c);
        assertThat(result.findings()).isEmpty();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(result.diagnostics().dialogue().assessed()).isEqualTo(1);
    }
    @Test void unavailableCallProducesSeparateCallFailedUnitState() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.empty());
        assertThat(run(context(lines)).diagnostics().dialogue().units().get(0).state()).isEqualTo(ReviewDiagnostics.State.CALL_FAILED);
    }
    @Test void plannerBoundsUnitCountAndKeepsWindowsSeparate() {
        var c = context(IntStream.range(0, 20).mapToObj(i -> "발언" + i).toArray(String[]::new));
        var batch = TextReviewBatchPlanner.plan(c.reviewInput(), TimelineEventType.SPEECH, 3).get(0);
        var plan = DialogueReview.plan(batch);
        assertThat(plan.units()).hasSizeLessThanOrEqualTo(6).allSatisfy(u -> {
            assertThat(u.endMs() - u.startMs()).isLessThanOrEqualTo(30_000);
            assertThat(u.segmentIds()).hasSizeLessThanOrEqualTo(32);
            assertThat(u.primarySegmentIds()).isNotEmpty();
        });
        assertThat(plan.units().stream().map(DialogueReview.Unit::unitId).distinct().count()).isEqualTo(plan.units().size());
    }
    @Test void newAndLegacyJsonResponsesAndDiagnosticsSerializeWithoutPaidCalls() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var c = context(lines);
        var parsed = mapper.readValue(mapper.writeValueAsString(new LlmResult(passes(lines), List.of(unitRisk(c)))), LlmResult.class);
        assertThat(parsed.unitEvaluations()).hasSize(1);
        assertThat(mapper.readValue("{\"evaluations\":[]}", LlmResult.class).unitEvaluations()).isNull();
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.of(parsed));
        assertThat(mapper.writeValueAsString(run(c).diagnostics())).contains("dialogue", "SAME_TARGET_CONNECTED", "REVIEW_REQUIRED");
    }
    @Test void invalidDuplicateUnitCannotProduceNegativeAssessedCount() {
        var c = context(lines);
        var bad = new DialogueReview.LlmDecision(unit(c).unitId(), "SAME_TARGET_CONNECTED", risk("없는 인용", "RESIDENT_GROUP", true));
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(passes(lines), List.of(bad, bad))));
        var result = run(c);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().dialogue().assessed()).isZero();
        assertThat(result.diagnostics().dialogue().conflicts()).isEqualTo(1);
    }
    @Test void uncertainUnitMustReportMissingInformationWithoutCreatingCard() {
        var c = context(lines);
        var uncertain = new LlmDecision("stt-index-2", "UNCERTAIN", lines[2],
                "발언이 주민을 직접 평가한 말인지 비판적 인용인지 확인할 실제 음성이 부족합니다.",
                null, null, null, null, null, List.of("실제 발화의 인용 여부와 화자 구분"),
                List.of(new LlmEvidence("stt-index-2", lines[2], "PRIMARY"),
                        new LlmEvidence("stt-index-0", lines[0], "CONTEXT")), null, null, null, null);
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(passes(lines), List.of(new DialogueReview.LlmDecision(
                        unit(c).unitId(), "INSUFFICIENT_CONTEXT", uncertain)))));
        var result = run(c);
        assertThat(result.findings()).isEmpty();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(result.diagnostics().dialogue().uncertain()).isEqualTo(1);
        assertThat(result.diagnostics().dialogue().units().get(0).missingInformation()).isNotEmpty();
    }
    @Test void cappedPlanReportsUnselectedDialogueAnchors() {
        var c = new AnalysisContext(video, ContentGenre.GENERAL, IntStream.range(0, 20).mapToObj(i ->
                new TranscriptSegment(video, i / 2 * 20_000 + i % 2 * 1000,
                        i / 2 * 20_000 + i % 2 * 1000 + 500, "다른 대화" + i)).toList(), null);
        var plan = DialogueReview.plan(TextReviewBatchPlanner.withContext(c.reviewInput(), c.reviewInput().segments()));
        assertThat(plan.units()).hasSize(6);
        assertThat(plan.unselectedAnchors()).isEqualTo(8);
    }
    @Test void unitPassStillRequiresAnExplanationRatherThanATerseSafetyVerdict() {
        var c = context(lines);
        var terse = new LlmDecision("stt-index-2", "PASS", lines[2], "정상입니다",
                null, null, null, null, null, List.of(), List.of(
                        new LlmEvidence("stt-index-2", lines[2], "PRIMARY"),
                        new LlmEvidence("stt-index-0", lines[0], "CONTEXT")), null, null, null, null);
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(passes(lines), List.of(new DialogueReview.LlmDecision(
                        unit(c).unitId(), "NO_CONNECTED_EVALUATION", terse)))));
        var result = run(c);
        assertThat(result.diagnostics().dialogue().assessed()).isZero();
        assertThat(result.diagnostics().dialogue().units().get(0).failure()).isEqualTo("VAGUE_REASON");
    }
}
