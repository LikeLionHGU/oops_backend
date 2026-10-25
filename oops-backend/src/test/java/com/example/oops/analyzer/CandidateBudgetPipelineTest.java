package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CandidateBudgetPipelineTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void crowdedEarlyCandidatesDoNotConsumeTheEntireVerificationBudgetBeforeTheTail() throws Exception {
        var client = mock(OpenAiClient.class);
        var video = Video.builder().filename("budget-test.mp4").build();
        var transcript = List.of(new TranscriptSegment(video, 0, 1000, "초반 후보 하나"),
                new TranscriptSegment(video, 1500, 2500, "초반 후보 둘"),
                new TranscriptSegment(video, 3000, 4000, "초반 후보 셋"),
                new TranscriptSegment(video, 4500, 5500, "초반 후보 넷"),
                new TranscriptSegment(video, 360_000, 361_000, "영상 후반 후보"));
        var context = new AnalysisContext(video, ContentGenre.GENERAL, transcript, List.of());
        Map<String, String> textById = new HashMap<>();
        context.reviewInput().segments().forEach(segment -> textById.put(segment.id(), segment.text()));

        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(call -> {
            var request = JSON.readTree((String) call.getArgument(1));
            var ids = new ArrayList<String>();
            request.path("primaryIds").forEach(node -> ids.add(node.asText()));
            var proposals = ids.stream().map(id -> new Proposal(id, "TARGET_TREATMENT",
                    "대상 평가 여부를 확인할 후보입니다.", List.of(new Quote(id, textById.get(id))))).toList();
            return Optional.of(new Discovery(ids, proposals, List.of(), false));
        });
        when(client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(call -> {
            var request = JSON.readTree((String) call.getArgument(1));
            List<Verification> results = new ArrayList<>();
            request.path("candidates").forEach(item -> {
                String id = item.path("candidateId").asText();
                String anchor = item.path("anchorId").asText();
                String quote = textById.get(anchor);
                var assessment = new TextReviewEngine.LlmDecision(anchor, "PASS", quote,
                        "제공된 한 줄만으로 구체적인 검토 이유는 확인되지 않습니다.", null, null, null,
                        null, null, List.of(), List.of(new TextReviewEngine.LlmEvidence(anchor, quote, "PRIMARY")),
                        null, null, null, null, null);
                results.add(new Verification(id, assessment));
            });
            return Optional.of(new VerificationResult(results));
        });

        var result = run(client, context, 2);
        var pipeline = result.diagnostics().candidatePipeline();

        assertThat(pipeline.budgetSkipped()).isEqualTo(3);
        assertThat(pipeline.candidates()).anySatisfy(trace -> {
            assertThat(trace.anchorId()).isEqualTo("stt-index-4");
            assertThat(trace.state()).isEqualTo("PASS");
        });
        assertThat(pipeline.verificationCalls()).isEqualTo(2);
        assertThat(result.unassessedSegmentIds()).doesNotContain("stt-index-4").contains("stt-index-1");
        verify(client, times(2)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
}
