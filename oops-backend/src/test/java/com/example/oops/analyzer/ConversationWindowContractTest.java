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
        var result = CandidateRuns.run(f.client, new AnalysisContext(f.video, ContentGenre.GENERAL, lines, List.of()), 24);
        var trace = result.diagnostics().candidatePipeline().candidates().get(0);
        assertThat(trace.contextSegmentIds()).hasSize(19).contains("stt-index-0", "stt-index-18");
        assertThat(trace.contextLimited()).isFalse();
        assertThat(result.findings()).isEmpty();
    }
}
