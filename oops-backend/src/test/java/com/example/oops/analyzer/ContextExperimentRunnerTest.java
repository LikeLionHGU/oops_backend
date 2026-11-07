package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Experiment A runner and write-once result store. Mocked model; shape only, not accuracy. */
class ContextExperimentRunnerTest {
    private final CandidateReviewEngineTest f = new CandidateReviewEngineTest();
    private final RunOptions options = new RunOptions(24, 8, false, new VerificationFeatures(false, false, false), false);

    private void discoveryReturnsNoCandidateForEachPrimary() {
        when(f.client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(invocation -> {
            var tree = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<String> primary = new ArrayList<>();
            tree.get("primaryIds").forEach(n -> primary.add(n.asText()));
            return Optional.of(new Discovery(primary, List.of(), List.of(), false));
        });
    }

    private static ContextSelector everythingElse() {
        return (target, transcript) -> new ContextSelection(transcript.segments().stream().map(ReviewInput.Segment::id)
                .filter(id -> !id.equals(target.id())).toList(), "TEST:all-other");
    }

    private ExperimentResultStore store(Path dir) {
        return ExperimentResultStore.create(dir.resolve("run-1"), "run-1", Map.of("model", "mock"));
    }

    @Test void writesOneRecordPerTargetWithSelectionStateAndFullRequests(@TempDir Path dir) {
        discoveryReturnsNoCandidateForEachPrimary();
        var context = f.context("그 집은 별로다", "다른 말", "그 집 사장 얘기");
        try (var store = store(dir)) {
            new ContextExperimentRunner(f.client, null, options, store)
                    .run(context, "A1-T", everythingElse(), List.of("stt-index-0", "stt-index-2"), 0);
        }
        var records = ExperimentResultStore.read(dir.resolve("run-1"));
        assertThat(records).extracting(ContextExperimentRunner.TargetRecord::targetId).containsExactly("stt-index-0", "stt-index-2");
        var first = records.get(0);
        assertThat(first.runId()).isEqualTo("run-1");
        assertThat(first.conditionId()).isEqualTo("A1-T");
        assertThat(first.repeat()).isZero();
        assertThat(first.selectorId()).isEqualTo("TEST:all-other");
        assertThat(first.selectionStatus()).isEqualTo(ContextSelection.Status.SELECTED);
        assertThat(first.contextSegmentIds()).containsExactly("stt-index-1", "stt-index-2");
        assertThat(first.targetState()).isEqualTo(ReviewDiagnostics.State.NO_CANDIDATE.name());
        assertThat(first.pipelineRevision()).contains(ExperimentalBatchRendering.VERSION);
        assertThat(first.repairUsage()).isNotNull();
        assertThat(first.requests()).extracting(e -> e.stage()).contains("CANDIDATE_DISCOVERY");
        assertThat(first.requests().get(0).input()).contains("\"batchRole\":\"CONTEXT_ONLY\"");
        assertThat(first.droppedRequests()).isZero();
        verify(f.client, times(2)).completeAsJson(anyString(), anyString(), eq(Discovery.class));
    }

    @Test void budgetInfeasibleSelectionsAreRecordedWithoutCallingTheModel(@TempDir Path dir) {
        ContextSelector infeasible = (target, transcript) ->
                new ContextSelection(List.of("stt-index-1"), "TEST:oracle", ContextSelection.Status.BUDGET_INFEASIBLE);
        try (var store = store(dir)) {
            new ContextExperimentRunner(f.client, null, options, store)
                    .run(f.context("그 집은 별로다", "다른 말"), "A4-T", infeasible, List.of("stt-index-0"), 1);
        }
        var record = ExperimentResultStore.read(dir.resolve("run-1")).get(0);
        assertThat(record.selectionStatus()).isEqualTo(ContextSelection.Status.BUDGET_INFEASIBLE);
        assertThat(record.targetState()).isEqualTo("BUDGET_INFEASIBLE");
        assertThat(record.contextSegmentIds()).containsExactly("stt-index-1");
        assertThat(record.requests()).isEmpty();
        assertThat(record.repeat()).isEqualTo(1);
        verifyNoInteractions(f.client);
    }

    @Test void rejectsUnknownOrDuplicateTargetsBeforeAnyCall(@TempDir Path dir) {
        try (var store = store(dir)) {
            var runner = new ContextExperimentRunner(f.client, null, options, store);
            var context = f.context("그 집은 별로다", "다른 말");
            assertThatThrownBy(() -> runner.run(context, "A1-T", everythingElse(), List.of("stt-index-9"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runner.run(context, "A1-T", everythingElse(), List.of("stt-index-0", "stt-index-0"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(f.client);
    }

    private static AnalysisContext videoContext(long id, String... lines) {
        var video = com.example.oops.domain.Video.builder().filename("v" + id).build();
        org.springframework.test.util.ReflectionTestUtils.setField(video, "id", id);
        List<com.example.oops.domain.TranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < lines.length; i++)
            segments.add(new com.example.oops.domain.TranscriptSegment(video, i * 2000L, i * 2000L + 1500, lines[i]));
        return new AnalysisContext(video, com.example.oops.domain.ContentGenre.GENERAL, segments, List.of());
    }

    @Test void sameTargetIdInAnotherVideoIsADifferentRecord(@TempDir Path dir) {
        discoveryReturnsNoCandidateForEachPrimary();
        var first = videoContext(1, "그 집은 별로다", "다른 말");
        var second = videoContext(2, "전혀 다른 영상", "다른 말");
        try (var store = store(dir)) {
            var runner = new ContextExperimentRunner(f.client, null, options, store);
            runner.run(first, "T0", everythingElse(), List.of("stt-index-0"), 0);
            runner.run(second, "T0", everythingElse(), List.of("stt-index-0"), 0);
            assertThatThrownBy(() -> runner.run(second, "T0", everythingElse(), List.of("stt-index-0"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ExperimentResultStore.read(dir.resolve("run-1")))
                .extracting(ContextExperimentRunner.TargetRecord::videoId).containsExactly(1L, 2L);
    }

    @Test void recordsDiscoveryReferenceIds(@TempDir Path dir) {
        discoveryReturnsNoCandidateForEachPrimary();
        var lib = new ContextExampleSelectionTest().library(new ContextExampleSelectionTest().archive(4), 3, 3000, "");
        try (var store = store(dir)) {
            new ContextExperimentRunner(f.client, null, options, store).run(f.context("그 집은 별로다", "다른 말"), "VECTOR",
                    everythingElse(), List.of("stt-index-0"), 0, lib, raw -> List.of("source-1"));
        }
        var record = ExperimentResultStore.read(dir.resolve("run-1")).get(0);
        assertThat(record.discoveryReferenceIds()).containsExactly("source-1");
    }

    @Test void storeIsWriteOnceAndRejectsDuplicateRecords(@TempDir Path dir) throws Exception {
        discoveryReturnsNoCandidateForEachPrimary();
        var context = f.context("그 집은 별로다", "다른 말");
        try (var store = store(dir)) {
            var runner = new ContextExperimentRunner(f.client, null, options, store);
            runner.run(context, "A1-T", everythingElse(), List.of("stt-index-0"), 0);
            assertThatThrownBy(() -> runner.run(context, "A1-T", everythingElse(), List.of("stt-index-0"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
            runner.run(context, "A1-T", everythingElse(), List.of("stt-index-0"), 1);
        }
        assertThat(ExperimentResultStore.read(dir.resolve("run-1"))).hasSize(2);
        assertThatThrownBy(() -> store(dir)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExperimentResultStore.create(dir.resolve("bad"), "../escape", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var manifest = JsonMapper.builder().build().readTree(Files.readString(dir.resolve("run-1/manifest.json")));
        assertThat(manifest.get("runId").asText()).isEqualTo("run-1");
        assertThat(manifest.get("metadata").get("model").asText()).isEqualTo("mock");
        assertThat(manifest.get("createdAt").asText()).isNotBlank();
    }
}
