package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.dto.*;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock-only shared setup; never packaged with the server. */
class CandidateReviewFixture {
    final OpenAiClient client = mock(OpenAiClient.class);
    final Video video = Video.builder().filename("generic.mp4").build();
    AnalysisContext context(String... lines) {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) segments.add(new TranscriptSegment(video, i * 2000L, i * 2000L + 1500, lines[i]));
        return new AnalysisContext(video, ContentGenre.GENERAL, segments,
                List.of(new ScreenText(video, 0, 2000, "화면에만 있는 글자", .9, null)));
    }
    Proposal proposal(String anchor, String quote) {
        return new Proposal(anchor, "TARGET_TREATMENT", "그 집의 이용자를 음식 선택 때문에 열등한 대상으로 묘사할 가능성을 확인합니다.",
                List.of(new Quote(anchor, quote)));
    }
    void discovery(List<String> ids, Proposal... proposals) {
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class)))
                .thenReturn(Optional.of(new Discovery(ids, List.of(proposals), List.of(), false)));
    }
    LlmDecision assessment(String anchor, String decision, String quote) {
        boolean review = decision.equals("REVIEW_REQUIRED");
        return new LlmDecision(anchor, decision, quote, "음식 평가를 넘어 그 집의 선택과 이용자를 낮춰 묘사하는 연결을 원문에서 확인합니다.",
                review ? "MOCKERY" : null, review ? "그 집" : null, review ? .6 : null,
                null, null, decision.equals("UNCERTAIN") ? List.of("인용 주체를 식별하는 추가 문맥") : List.of(),
                review ? List.of(new LlmEvidence(anchor, quote, "PRIMARY"), new LlmEvidence(anchor, "그 집", "TARGET"))
                        : List.of(new LlmEvidence(anchor, quote, "PRIMARY")),
                review ? "BUSINESS" : null, review ? "EXPLICIT" : null,
                review ? "원문이 그 집을 평가 대상으로 지칭하며 이용 경험과 연결합니다." : null,
                review ? "단순한 음식 취향 설명으로도 읽히지만 이용자를 낮추는 별도 표현은 설명하지 못합니다." : null);
    }
    void verification(Verification... items) {
        when(client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(items))));
    }
    LlmDecision linkedPass(String anchor, String quote, String contextId, String contextQuote) {
        return new LlmDecision(anchor, "PASS", quote, "전체 연결은 선택지 설명이며 타인의 생활을 낮추는 평가가 없습니다.",
                null, null, null, null, null, List.of(),
                List.of(new LlmEvidence(anchor, quote, "PRIMARY"), new LlmEvidence(contextId, contextQuote, "CONTEXT")),
                null, null, null, "선택지를 낮춘다는 가설은 가능하지만 제공된 연결에는 낮춤의 실제 비교·평가가 없습니다.");
    }
}
