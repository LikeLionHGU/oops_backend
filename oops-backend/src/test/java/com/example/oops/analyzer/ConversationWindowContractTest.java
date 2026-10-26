package com.example.oops.analyzer;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.stream.IntStream;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationWindowContractTest {
    @org.junit.jupiter.api.Tag("dataset-backed")
    @Test void storedMovieExcerptKeepsItsEndingInEveryAdvertisedAnchorWindow() throws Exception {
        var file = java.nio.file.Path.of("../datasets/controversy/experiments/podcast-five-live-20261025/request-childhood-movie-fight.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(file));
        var rows = ReviewGuidelineLibrary.JSON.readTree(java.nio.file.Files.readAllBytes(file)).get("segments");
        List<ReviewInput.Segment> raw = new ArrayList<>(); int index = 0;
        for (var s : rows) raw.add(new ReviewInput.Segment("stt-index-" + index++, TimelineEventType.SPEECH,
                s.path("startMs").asLong(), s.path("endMs").asLong(), s.path("text").asText(), null));
        var batch = TextReviewBatchPlanner.plan(new ReviewInput(raw), TimelineEventType.SPEECH, 3).get(0);
        var windows = CandidateContextWindow.conversationUnits(batch).stream()
                .filter(w -> w.anchorId().equals("stt-index-5")).toList();
        assertThat(windows).isNotEmpty().allSatisfy(w -> {
            assertThat(w.segmentIds()).containsExactlyElementsOf(raw.stream().map(ReviewInput.Segment::id).toList());
            assertThat(w.limited()).isFalse();
        });
    }
    @Test void expressionVerificationRetainsAdvertisedLongEndingWithoutNewRaw() throws Exception {
        var f = new CandidateReviewEngineTest();
        var lines = IntStream.range(0, 19).mapToObj(i -> new TranscriptSegment(f.video, i * 5500L,
                i * 5500L + 5500, i == 18 ? "뒤에서 앞의 무용담을 받아치는 반응" : "회고 발언 " + i)).toList();
        var p = new Proposal("stt-index-5", "EXPRESSION_CONTENT", "행동 소개 방식 검토",
                List.of(new Quote("stt-index-5", "회고 발언 5")));
        when(f.client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(call -> {
            var request = JsonMapper.builder().build().readTree((String) call.getArgument(1));
            assertThat(request.get("windows")).isEqualTo(request.get("targetWindows"));
            List<String> ids = new ArrayList<>(); request.get("primaryIds").forEach(n -> ids.add(n.asText()));
            return Optional.of(new Discovery(ids, List.of(p), List.of(), false));
        });
        f.verification(new Verification("candidate-1", f.assessment(p.anchorId(), "PASS", "그 집")));
        var result = run(f.client, new AnalysisContext(f.video, ContentGenre.GENERAL, lines, List.of()), 24);
        var trace = result.diagnostics().candidatePipeline().candidates().get(0);
        assertThat(trace.contextSegmentIds()).hasSize(19).contains("stt-index-0", "stt-index-18");
        assertThat(trace.contextLimited()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    @Test void extendedPermissionStillHonoursRawGapCountAndUnicodeBounds() {
        var helper = new CandidateContextWindowTest();
        for (String text : List.of("짧은 회고", "😀".repeat(600))) {
            var raw = IntStream.range(0, 80).mapToObj(i -> helper.speech(i, i * 3000L, text)).toList();
            var batch = new TextReviewBatchPlanner.Batch(raw.subList(0, 20), raw.subList(20, 36), false, false);
            var supplied = raw.subList(0, 36).stream().map(ReviewInput.Segment::id).toList();
            assertThat(CandidateContextWindow.conversationUnits(batch)).allSatisfy(w -> {
                assertThat(supplied).containsAll(w.segmentIds());
                assertThat(w.segmentIds()).contains(w.anchorId()).hasSizeLessThanOrEqualTo(48);
                assertThat(w.endMs() - w.startMs()).isLessThanOrEqualTo(120000);
                assertThat(raw.stream().filter(s -> w.segmentIds().contains(s.id()))
                        .mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum()).isLessThanOrEqualTo(6000);
            });
        }
        var a = helper.speech(0, 0, "앞 흐름"); var b = helper.speech(1, 60000, "다른 흐름");
        assertThat(CandidateContextWindow.conversationUnits(new TextReviewBatchPlanner.Batch(List.of(a,b),List.of(),false,false)))
                .allSatisfy(w -> assertThat(w.segmentIds()).hasSize(1));
    }
}
