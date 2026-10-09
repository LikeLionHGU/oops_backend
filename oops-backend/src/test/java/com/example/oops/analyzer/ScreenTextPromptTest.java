package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline prompt and request isolation checks; not real-model accuracy measurements. */
class ScreenTextPromptTest {
    private String definitions() throws Exception {
        var field = ScreenTextReviewAnalyzer.class.getDeclaredField("SYSTEM_PROMPT");
        field.setAccessible(true);
        String system = (String) field.get(null);
        assertThat(system).startsWith(ReviewJudgmentPolicy.PROMPT);
        return system.substring(ReviewJudgmentPolicy.PROMPT.length());
    }

    private ReviewInput.Segment caption(String id, long time, String text) {
        return new ReviewInput.Segment(id, TimelineEventType.CAPTION, time, time + 1000,
                text, 0.9, ScreenTextRole.EDITORIAL);
    }

    @Test void allThirteenAllowedTypesHaveInclusionExclusionAndBoundaryDefinitions() throws Exception {
        String prompt = definitions();
        var field = ScreenTextReviewAnalyzer.class.getDeclaredField("ALLOWED_CATEGORIES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var allowed = (Set<RiskCategory>) field.get(null);
        var matcher = Pattern.compile("(?m)^### ([A-Z_]+) —").matcher(prompt);
        var names = new ArrayList<String>();
        while (matcher.find()) names.add(matcher.group(1));
        assertThat(names).hasSize(13).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(allowed.stream().map(Enum::name).toList());
        for (String section : prompt.split("(?m)^### ")) {
            if (section.startsWith("# 화면 텍스트")) continue;
            assertThat(section).contains("포함:", "제외:", "경계:");
        }
    }

    @Test void sourceAttributionAndOcrUncertaintyAreExplicit() throws Exception {
        assertThat(definitions()).contains(
                "STT와 독립적으로 판단한다",
                "STT로 OCR 글자를 복원하거나",
                "작성자·인용 주체를 확인할 수 없으면 귀속하지 않은 채",
                "배경 글자를 편집자의 평가 문구로 해석하지 않는다",
                "OCR 오류가 있다는 이유만으로 자동으로 UNCERTAIN을 선택하지 않는다",
                "추정 복원 문구를 실제 원문 인용이나 검토 근거로 사용하지 않는다")
                .doesNotContain("segmentId", "unitId", "JSON", "피식", "영양", "롯데리아");
    }

    @Test void speechDoesNotConsumeOcrContextBudgetAndOcrStillRetainsOtherScreenContext() {
        var segments = new ArrayList<ReviewInput.Segment>();
        segments.add(caption("ocr-main", 10_000, "검토 문구"));
        segments.add(caption("ocr-context", 11_000, "앞뒤 설명"));
        for (int i = 0; i < 30; i++) {
            segments.add(new ReviewInput.Segment("stt-" + i, TimelineEventType.SPEECH,
                    10_000, 11_000, "발언 문맥 " + i, null));
        }
        var input = new ReviewInput(segments);
        var filtered = TextReviewEngine.requestInput(input, TimelineEventType.CAPTION);
        var batch = TextReviewBatchPlanner.withContext(filtered, List.of(filtered.find("ocr-main").orElseThrow()));
        assertThat(batch.context()).extracting(ReviewInput.Segment::id).containsExactly("ocr-context");
        assertThat(batch.contextLimited()).isFalse();
        assertThat(TextReviewEngine.requestInput(input, TimelineEventType.SPEECH)).isSameAs(input);
    }

    @Test void initialAndRepairRequestsNeverContainSpeechAndRejectSpeechEvidence() {
        var input = new ReviewInput(List.of(caption("ocr-main", 10_000, "원문 문구"),
                new ReviewInput.Segment("stt-secret", TimelineEventType.SPEECH,
                        10_000, 11_000, "음성 전사 전용 내용", null)));
        var context = new AnalysisContext(Video.builder().filename("synthetic.mp4").build(),
                ContentGenre.GENERAL, List.of(), List.of(), input);
        var client = mock(OpenAiClient.class);
        var users = new ArrayList<String>();
        var response = new TextReviewEngine.LlmDecision("ocr-main", "PASS", "원문 문구", "문구 확인",
                null, null, null, null, null, List.of(),
                List.of(new TextReviewEngine.LlmEvidence("ocr-main", "원문 문구", "PRIMARY"),
                        new TextReviewEngine.LlmEvidence("stt-secret", "음성 전사 전용 내용", "CONTEXT")),
                null, null, null, null);
        when(client.completeAsJson(anyString(), anyString(), eq(TextReviewEngine.LlmResult.class)))
                .thenAnswer(call -> {
                    users.add(call.getArgument(1));
                    return Optional.of(new TextReviewEngine.LlmResult(List.of(response)));
                });
        var analyzer = new ScreenTextReviewAnalyzer(client);
        assertThat(analyzer.analyze(context)).isEmpty();
        assertThat(users.size()).isGreaterThanOrEqualTo(2);
        assertThat(users).allSatisfy(user -> assertThat(user).contains("ocr-main")
                .doesNotContain("stt-secret", "음성 전사 전용 내용", "SPEECH"));
        assertThat(analyzer.consumeReviewResult(context).orElseThrow().unassessedSegmentIds())
                .contains("ocr-main");
    }

    @Test void videoWithoutScreenTextMakesNoOcrRequest() {
        var client = mock(OpenAiClient.class);
        var video = Video.builder().filename("speech-only.mp4").build();
        var context = new AnalysisContext(video, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(video, 0, 1000, "발언만 있는 영상")), List.of());
        var analyzer = new ScreenTextReviewAnalyzer(client);
        assertThat(analyzer.analyze(context)).isEmpty();
        verifyNoInteractions(client);
    }
}
