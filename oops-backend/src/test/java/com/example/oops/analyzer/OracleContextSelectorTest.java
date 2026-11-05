package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/** A4-T: gold context plus gold evidence, budget-checked whole, gold roles never rendered. Shape only. */
class OracleContextSelectorTest {
    private static final int LARGE = 1_000_000;
    private final ReviewInput transcript = new ReviewInput(IntStream.range(0, 8)
            .mapToObj(i -> new ReviewInput.Segment("stt-index-" + i, TimelineEventType.SPEECH, i * 60_000L, i * 60_000L + 1500,
                    "발언 내용 " + i, null)).toList());

    private ReviewInput.Segment seg(int i) {
        return transcript.segments().get(i);
    }

    private static List<String> ids(int... indexes) {
        return Arrays.stream(indexes).mapToObj(i -> "stt-index-" + i).toList();
    }

    private OracleContextSelector selector(int budget, OracleContextSelector.OracleLabel label) {
        return new OracleContextSelector(Map.of("stt-index-5", label), "gold-v0", new ContextTokenBudget(budget));
    }

    @Test void unionsGoldContextAndEvidenceMinusTargetInTranscriptOrder() {
        var label = new OracleContextSelector.OracleLabel(ids(6, 1), ids(5, 3, 1));
        var selection = selector(LARGE, label).select(seg(5), transcript);
        assertThat(selection.contextSegmentIds()).containsExactlyElementsOf(ids(1, 3, 6));
        assertThat(selection.status()).isEqualTo(ContextSelection.Status.SELECTED);
        assertThatCode(() -> selection.toBatch(seg(5), transcript)).doesNotThrowAnyException();
    }

    @Test void contextIndependentCaseGetsNoContext() {
        var selection = selector(LARGE, new OracleContextSelector.OracleLabel(List.of(), ids(5))).select(seg(5), transcript);
        assertThat(selection.contextSegmentIds()).isEmpty();
        assertThat(selection.status()).isEqualTo(ContextSelection.Status.SELECTED);
    }

    @Test void overBudgetKeepsTheWholeGoldSetAndIsMarkedInfeasible() {
        var label = new OracleContextSelector.OracleLabel(ids(1, 2), ids(3));
        var rendering = new ExperimentalBatchRendering(transcript, Set.of("stt-index-5"));
        int full = new ContextTokenBudget(LARGE).measure(rendering, List.of(seg(1), seg(2), seg(3), seg(5))).contextTokens();
        var atBudget = selector(full, label).select(seg(5), transcript);
        assertThat(atBudget.status()).isEqualTo(ContextSelection.Status.SELECTED);
        var over = selector(full - 1, label).select(seg(5), transcript);
        assertThat(over.status()).isEqualTo(ContextSelection.Status.BUDGET_INFEASIBLE);
        assertThat(over.contextSegmentIds()).containsExactlyElementsOf(ids(1, 2, 3));
    }

    @Test void renderedOracleInputCarriesNoGoldRoleHint() {
        var label = new OracleContextSelector.OracleLabel(ids(1), ids(3));
        var selection = selector(LARGE, label).select(seg(5), transcript);
        var rendering = new ExperimentalBatchRendering(transcript, Set.of("stt-index-5"));
        var raw = new ArrayList<>(List.of(seg(5)));
        selection.contextSegmentIds().forEach(id -> raw.add(transcript.find(id).orElseThrow()));
        var rows = rendering.render(raw);
        assertThat(rows).allSatisfy(r -> assertThat(r.keySet()).isSubsetOf("id", "startMs", "endMs", "text", "batchRole", "omittedBefore"));
        assertThat(rows).extracting(r -> r.get("batchRole")).containsOnly(ExperimentalBatchRendering.JUDGED, ExperimentalBatchRendering.CONTEXT_ONLY);
        assertThat(JsonMapper.builder().build().writeValueAsString(rows).toLowerCase()).doesNotContain("gold", "oracle", "evidence");
    }

    @Test void rejectsMissingLabelsAndIdsOutsideTheTranscript() {
        var unlabeled = new OracleContextSelector(Map.of(), "gold-v0", new ContextTokenBudget(LARGE));
        assertThatThrownBy(() -> unlabeled.select(seg(5), transcript)).isInstanceOf(IllegalArgumentException.class);
        var foreign = selector(LARGE, new OracleContextSelector.OracleLabel(List.of("missing"), List.of()));
        assertThatThrownBy(() -> foreign.select(seg(5), transcript)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void otherSelectorsDefaultToSelectedAndIdRecordsLabelVersion() {
        assertThat(new ContextSelection(List.of(), "test").status()).isEqualTo(ContextSelection.Status.SELECTED);
        assertThat(selector(128, new OracleContextSelector.OracleLabel(List.of(), List.of())).id())
                .isEqualTo("A4-T:oracle-context-evidence-v1:labels=gold-v0:" + ContextTokenBudget.COUNTER_ID + ":B=128");
    }
}
