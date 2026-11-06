package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/** Pre-registered budget-feasible set from gold context tokens only; never from run results. */
class BudgetFeasibleCasesTest {
    private static final int LARGE = 1_000_000;

    private static ReviewInput transcript(int n) {
        return new ReviewInput(IntStream.range(0, n).mapToObj(i -> new ReviewInput.Segment("stt-index-" + i, TimelineEventType.SPEECH,
                i * 60_000L, i * 60_000L + 1500, "발언 내용 " + i, null)).toList());
    }

    private static OracleContextSelector.OracleLabel label(List<String> context, List<String> evidence) {
        return new OracleContextSelector.OracleLabel(context, evidence);
    }

    private final Map<Long, ReviewInput> transcripts = Map.of(1L, transcript(6), 2L, transcript(3));
    private final Map<Long, Map<String, OracleContextSelector.OracleLabel>> labels = Map.of(
            1L, Map.of("stt-index-5", label(List.of("stt-index-1", "stt-index-2"), List.of("stt-index-3")),
                    "stt-index-0", label(List.of(), List.of("stt-index-0"))),
            2L, Map.of("stt-index-2", label(List.of("stt-index-0"), List.of())));

    private int goldTokens(long video, String target) {
        var t = transcripts.get(video);
        return new OracleContextSelector(labels.get(video), "gold-v0", new ContextTokenBudget(LARGE))
                .measure(t.find(target).orElseThrow(), t).contextTokens();
    }

    @Test void marksCasesFeasibleWhenGoldContextFitsTheBudgetAndMatchesTheOracleSelector() {
        int budget = goldTokens(2L, "stt-index-2");
        assertThat(goldTokens(1L, "stt-index-5")).isGreaterThan(budget);
        var registry = BudgetFeasibleCases.compute(transcripts, labels, "gold-v0", new ContextTokenBudget(budget));
        assertThat(registry.entries()).extracting(BudgetFeasibleCases.Entry::videoId, BudgetFeasibleCases.Entry::targetId,
                BudgetFeasibleCases.Entry::feasible).containsExactly(
                tuple(1L, "stt-index-0", true), tuple(1L, "stt-index-5", false), tuple(2L, "stt-index-2", true));
        assertThat(registry.entries().get(0).goldContextTokens()).isZero();
        assertThat(registry.entries().get(1).goldContextTokens()).isEqualTo(goldTokens(1L, "stt-index-5"));
        assertThat(registry.feasibleCases()).containsExactlyInAnyOrder(
                new ExperimentAggregator.CaseRef(1L, "stt-index-0"), new ExperimentAggregator.CaseRef(2L, "stt-index-2"));
        for (var e : registry.entries()) {
            var t = transcripts.get(e.videoId());
            var status = new OracleContextSelector(labels.get(e.videoId()), "gold-v0", new ContextTokenBudget(budget))
                    .select(t.find(e.targetId()).orElseThrow(), t).status();
            assertThat(status == ContextSelection.Status.SELECTED).isEqualTo(e.feasible());
        }
        assertThat(registry.budget()).isEqualTo(budget);
        assertThat(registry.counterId()).isEqualTo(ContextTokenBudget.COUNTER_ID);
        assertThat(registry.labelVersion()).isEqualTo("gold-v0");
    }

    @Test void writesOnceAndLoadsWithItsDigest(@TempDir Path dir) throws Exception {
        var registry = BudgetFeasibleCases.compute(transcripts, labels, "gold-v0", new ContextTokenBudget(LARGE));
        Path path = dir.resolve("feasible.json");
        String sha = BudgetFeasibleCases.write(registry, path);
        assertThat(sha).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        assertThat(BudgetFeasibleCases.load(path)).isEqualTo(registry);
        assertThatThrownBy(() -> BudgetFeasibleCases.write(registry, path)).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsLabelsWithoutATranscriptOrTargetsOutsideIt() {
        var orphan = Map.of(3L, Map.of("stt-index-0", label(List.of(), List.of())));
        assertThatThrownBy(() -> BudgetFeasibleCases.compute(transcripts, orphan, "gold-v0", new ContextTokenBudget(LARGE)))
                .isInstanceOf(IllegalArgumentException.class);
        var outside = Map.of(2L, Map.of("stt-index-9", label(List.of(), List.of())));
        assertThatThrownBy(() -> BudgetFeasibleCases.compute(transcripts, outside, "gold-v0", new ContextTokenBudget(LARGE)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
