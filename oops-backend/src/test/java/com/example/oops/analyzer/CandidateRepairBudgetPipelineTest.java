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

class CandidateRepairBudgetPipelineTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void boundedRepairsAreChosenAfterInitialVerificationsAndSpreadAcrossTimeWindows() throws Exception {
        var client = mock(OpenAiClient.class);
        var video = Video.builder().filename("repair-budget-test.mp4").build();
        var transcript = List.of(new TranscriptSegment(video, 0, 1000, "초반 발언 하나"),
                new TranscriptSegment(video, 1500, 2500, "초반 발언 둘"),
                new TranscriptSegment(video, 360_000, 361_000, "후반 발언 하나"),
                new TranscriptSegment(video, 361_500, 362_500, "후반 발언 둘"));
        var context = new AnalysisContext(video, ContentGenre.GENERAL, transcript, List.of());
        Map<String, String> textById = new HashMap<>();
        context.reviewInput().segments().forEach(segment -> textById.put(segment.id(), segment.text()));
        List<String> repairedAnchors = new ArrayList<>();

        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(call -> {
            var request = JSON.readTree((String) call.getArgument(1));
            var ids = new ArrayList<String>();
            request.path("primaryIds").forEach(node -> ids.add(node.asText()));
            var proposals = ids.stream().map(id -> new Proposal(id, "TARGET_TREATMENT",
                    "대상 취급 후보의 원문 근거를 검증합니다.", List.of(new Quote(id, textById.get(id))))).toList();
            return Optional.of(new Discovery(ids, proposals, List.of(), false));
        });
        when(client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(call -> {
            var request = JSON.readTree((String) call.getArgument(1));
            var results = new ArrayList<Verification>();
            if (request.has("repair")) {
                var candidate = request.path("candidates").get(0);
                String id = candidate.path("candidateId").asText();
                String anchor = candidate.path("anchorId").asText();
                repairedAnchors.add(anchor);
                String rawText = request.path("raw").findValue("text").asText();
                var pass = new TextReviewEngine.LlmDecision(anchor, "PASS", rawText,
                        "현재 원문에서 구체적인 문제 연결은 확인되지 않습니다.", null, null, null,
                        null, null, List.of(), List.of(new TextReviewEngine.LlmEvidence(anchor, rawText, "PRIMARY")),
                        null, null, null, null, null);
                results.add(new Verification(id, pass));
            } else {
                request.path("candidates").forEach(candidate -> {
                    String id = candidate.path("candidateId").asText();
                    String anchor = candidate.path("anchorId").asText();
                    String quote = textById.get(anchor);
                    var invalid = new TextReviewEngine.LlmDecision(anchor, "PASS", quote,
                            "허용되지 않은 ID 복구 예시입니다.", null, null, null, null, null, List.of(),
                            List.of(new TextReviewEngine.LlmEvidence("unknown-id", quote, "PRIMARY")),
                            null, null, null, null, null);
                    results.add(new Verification(id, invalid));
                });
            }
            return Optional.of(new VerificationResult(results));
        });

        var result = run(client, context, 4);
        var pipeline = result.diagnostics().candidatePipeline();

        assertThat(repairedAnchors).containsExactly("stt-index-0", "stt-index-2");
        assertThat(pipeline.repairCalls()).isEqualTo(2);
        assertThat(pipeline.verificationCalls()).isEqualTo(4);
        assertThat(pipeline.repairs()).filteredOn(trace -> "NOT_ATTEMPTED_BUDGET".equals(trace.state())).hasSize(2);
        assertThat(result.unassessedSegmentIds()).contains("stt-index-1", "stt-index-3");
        verify(client, times(4)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
}
