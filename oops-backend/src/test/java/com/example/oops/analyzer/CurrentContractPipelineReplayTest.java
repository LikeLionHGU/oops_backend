package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.AnalyzerStatus;
import com.example.oops.domain.CandidateType;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.TimelineEventType;
import com.example.oops.domain.TranscriptSegment;
import com.example.oops.domain.Video;
import com.example.oops.dto.TimelineEventDto;
import com.example.oops.fusion.FindingFusionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Current-contract saved-response replay; no provider, video, OCR, or database access. */
class CurrentContractPipelineReplayTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SAVED_DISCOVERY_RESPONSE = """
            {"reviewedSegmentIds":["stt-index-0","stt-index-1"],
             "candidates":[
               {"anchorId":"stt-index-0","axis":"TARGET_TREATMENT",
                "reason":"대상을 낮춰 묘사하는지 확인합니다.",
                "evidence":[{"segmentId":"stt-index-0","quote":"그 집 이용자들은 수준이 낮다"}]},
               {"anchorId":"stt-index-1","axis":"TARGET_TREATMENT",
                "reason":"개인 취향 설명인지 확인합니다.",
                "evidence":[{"segmentId":"stt-index-1","quote":"제 취향에 맞지 않는다는 뜻이었어요"}]}
             ],"uncertainSegments":[],"truncated":false,"visualCandidates":[]}
            """;
    private static final String SAVED_VERIFICATION_RESPONSE = """
            {"verifications":[
              {"candidateId":"candidate-1","assessment":{
                "segmentId":"stt-index-0","decision":"REVIEW_REQUIRED",
                "evidenceText":"그 집 이용자들은 수준이 낮다",
                "reason":"이용자를 낮추는 구체적인 표현을 확인합니다.","category":"MOCKERY",
                "target":"그 집","score":0.6,"missingInformation":[],
                "evidence":[
                  {"segmentId":"stt-index-0","quote":"그 집 이용자들은 수준이 낮다","role":"PRIMARY"},
                  {"segmentId":"stt-index-0","quote":"그 집","role":"TARGET"}],
                "targetType":"BUSINESS","targetRelation":"EXPLICIT",
                "targetReason":"원문에서 그 집을 평가 대상으로 직접 지칭합니다.",
                "alternativeInterpretation":"단순한 취향 표현일 수 있지만 이용자를 낮추는 표현이 포함됩니다."
              }},
              {"candidateId":"candidate-2","assessment":{
                "segmentId":"stt-index-1","decision":"PASS",
                "evidenceText":"제 취향에 맞지 않는다는 뜻이었어요",
                "reason":"개인 취향을 설명하며 구체적인 폄하 연결은 없습니다.",
                "missingInformation":[],
                "evidence":[{"segmentId":"stt-index-1","quote":"제 취향에 맞지 않는다는 뜻이었어요","role":"PRIMARY"}]
              }}
            ]}
            """;

    @Test void currentDiscoveryAndVerificationResponsesReachTheExpectedReportCard() throws Exception {
        var video = Video.builder().filename("current-contract-replay.mp4").build();
        var transcript = List.of(new TranscriptSegment(video, 0, 1500, "그 집 이용자들은 수준이 낮다"),
                new TranscriptSegment(video, 2000, 3500, "제 취향에 맞지 않는다는 뜻이었어요"));
        var context = new AnalysisContext(video, ContentGenre.GENERAL, transcript, List.of());
        var client = mock(OpenAiClient.class);

        // Fixed synthetic JSON responses exercise current DTO deserialization without making
        // a provider call or mutating any historical paid artifact.
        var savedDiscovery = JSON.readTree(SAVED_DISCOVERY_RESPONSE);
        var savedVerification = JSON.readTree(SAVED_VERIFICATION_RESPONSE);

        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(call -> {
            var request = JSON.readTree((String) call.getArgument(1));
            assertThat(request.path("promptRevision").asText()).isEqualTo(REVISION);
            assertThat(request.path("raw")).hasSize(2);
            assertThat(request.path("primaryIds")).hasSize(2);
            return Optional.of(JSON.treeToValue(savedDiscovery, Discovery.class));
        });
        when(client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(call -> {
            var request = JSON.readTree((String) call.getArgument(1));
            assertThat(request.path("promptRevision").asText()).isEqualTo(REVISION);
            assertThat(request.path("candidates")).hasSize(2);
            assertThat(request.path("candidates").get(0).path("candidateId").asText()).isEqualTo("candidate-1");
            assertThat(request.path("candidates").get(1).path("candidateId").asText()).isEqualTo("candidate-2");
            return Optional.of(JSON.treeToValue(savedVerification, VerificationResult.class));
        });

        var reviewed = CandidateReviewEngine.run(client, context,
                new RunServices(null, null, null, null, null),
                new RunOptions(24, 0, false, new VerificationFeatures(false, false, false), false));

        assertThat(reviewed.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(reviewed.findings()).hasSize(1);
        assertThat(reviewed.diagnostics().candidatePipeline().candidates())
                .extracting(CandidateReviewDiagnostics.Trace::state)
                .containsExactly("REVIEW_REQUIRED", "PASS");

        var fused = new FindingFusionService().fuse(reviewed.findings());
        var cards = fused.stream().map(finding -> TimelineEventDto.from(finding, null, null, null)).toList();

        assertThat(cards).hasSize(1);
        TimelineEventDto card = cards.get(0);
        assertThat(card.type()).isEqualTo(TimelineEventType.SPEECH);
        assertThat(card.candidateType()).isEqualTo(CandidateType.SPEECH_REVIEW);
        assertThat(card.text()).isEqualTo("그 집 이용자들은 수준이 낮다");
        assertThat(card.startMs()).isZero();
        assertThat(card.endMs()).isEqualTo(1500);
        assertThat(card.title()).contains("그 집");

        verify(client, times(1)).completeAsJson(anyString(), anyString(), eq(Discovery.class));
        verify(client, times(1)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
}
