package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Stored DTO compatibility only; retired dialogue responses can never publish findings. */
class LegacyDialogueCompatibilityTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void storedDialogueDiagnosticsRemainReadable() {
        var json = """
                {"evaluatorId":"speech-review","status":"SUCCESS","totalSegments":2,
                 "truncated":false,"failureCounts":{},"segments":[],"contextSelections":[],
                 "dialogue":{"requested":1,"assessed":1,"invalidAttempts":0,"conflicts":0,
                  "uncertain":0,"unselectedAnchors":0,"truncated":false,"calls":1,"budgetSkipped":0,
                  "executionMode":"ISOLATED_DIALOGUE_V1","units":[{"unitId":"old-1",
                   "segmentIds":["stt-index-0","stt-index-1"],"startMs":0,"endMs":2000,
                   "state":"PASS","relation":"NO_CONNECTED_EVALUATION","anchorId":"stt-index-0",
                   "reason":"stored synthetic decision","missingInformation":[],"evidence":[],"truncated":false}]}}
                """;
        var stored = mapper.readValue(json, ReviewDiagnostics.class);
        assertThat(stored.dialogue().executionMode()).isEqualTo("ISOLATED_DIALOGUE_V1");
        assertThat(stored.dialogue().units()).singleElement()
                .satisfies(t -> assertThat(t.state()).isEqualTo(ReviewDiagnostics.State.PASS));
        assertThat(mapper.readValue(mapper.writeValueAsString(stored), ReviewDiagnostics.class)).isEqualTo(stored);
    }

    @Test void ocrKeepsSegmentRequestAndIgnoresUnexpectedLegacyUnitResponses() {
        var client = mock(OpenAiClient.class);
        var video = Video.builder().filename("synthetic.mp4").build();
        var caption = new ScreenText(video, 0, 1000, "촬영을 시작합니다", 0.9, null);
        caption.classify(ScreenTextRole.EDITORIAL, "synthetic caption");
        var context = new AnalysisContext(video, ContentGenre.GENERAL, List.of(), List.of(caption));
        var response = mapper.readValue("""
                {"evaluations":[{"segmentId":"ocr-index-0","decision":"PASS",
                 "evidenceText":"촬영을 시작합니다","reason":"촬영의 시작을 알리는 진행 안내입니다.","missingInformation":[]}],
                 "unitEvaluations":[{"unitId":"old-1","relation":"CONNECTED_EXPRESSION",
                  "assessment":{"segmentId":"ocr-index-0","decision":"REVIEW_REQUIRED",
                   "evidenceText":"촬영을 시작합니다","category":"VIOLENCE","score":0.9}}]}
                """, TextReviewEngine.LlmResult.class);
        assertThat(response.unitEvaluations()).hasSize(1); // readable, not executable
        when(client.completeAsJson(anyString(), anyString(), eq(TextReviewEngine.LlmResult.class)))
                .thenAnswer(call -> {
                    var request = mapper.readTree((String) call.getArgument(1));
                    assertThat(request.path("dialogueReviewUnits").isEmpty()).isTrue();
                    assertThat(request.path("requiredUnitIds").isEmpty()).isTrue();
                    assertThat(request.path("requiredSegmentIds").get(0).asText()).isEqualTo("ocr-index-0");
                    assertThat(request.path("reviewUnits").isEmpty()).isFalse();
                    return Optional.of(response);
                });
        var analyzer = new ScreenTextReviewAnalyzer(client);
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(result.diagnostics().dialogue()).isNull();
        verify(client, times(1)).completeAsJson(anyString(), anyString(), eq(TextReviewEngine.LlmResult.class));
    }
}
