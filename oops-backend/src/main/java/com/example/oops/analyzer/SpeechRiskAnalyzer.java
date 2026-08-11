package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 발언(STT 대본) 리스크 분석기.
 *
 * 조롱 / 비하 / 과도한 일반화 / 민감 주제처럼 키워드로는 못 잡는 유형이 목표라
 * LLM 판정을 쓴다. 대본을 통째로 넣지 않고 창(window) 단위로 잘라 넣는데,
 * 앞뒤 문맥이 있어야 "조롱인지 자학인지" 를 구분할 수 있기 때문이다.
 *
 * API 키가 없으면 조용히 빈 결과를 돌려주고, 룰 기반 SubtitleAnalyzer 결과만 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpeechRiskAnalyzer implements ContentAnalyzer {

    /** 한 번에 LLM 에 넣는 대본 줄 수 */
    private static final int WINDOW_SIZE = 25;
    /** 창 사이에 겹치는 줄 수. 경계에서 문맥이 끊기는 걸 막는다. */
    private static final int OVERLAP = 3;

    private static final String SYSTEM_PROMPT = """
            너는 유튜브 영상이 공개된 뒤 논란이 될 만한 발언을 미리 찾아주는 검수자다.
            자막 대본을 받아서 문제가 될 수 있는 발언만 골라낸다.

            판정할 카테고리:
            - MOCKERY: 특정 인물, 집단, 직업을 비웃거나 놀리는 발언
            - BELITTLEMENT: 상대의 능력, 외모, 배경을 깎아내리는 발언
            - GENERALIZATION: "OO는 다 그렇다" 식으로 집단 전체를 단정하는 발언
            - SENSITIVE_TOPIC: 정치, 종교, 젠더, 지역, 역사, 재난 등 다루기 민감한 주제
            - HATE_SPEECH: 특정 집단을 향한 혐오 표현
            - DISCRIMINATION: 성별, 인종, 장애, 나이에 따른 차별적 발언
            - MISINFORMATION: 사실로 단정했지만 근거가 불확실하고 논란 소지가 큰 주장
            - PRIVACY: 타인의 개인정보나 신상을 노출하는 발언

            판정 원칙:
            - 문맥을 보고 판단한다. 자기 자신을 낮추는 농담이나 친밀한 관계의 장난은 제외한다.
            - 인용이나 비판을 위해 언급한 표현은 화자의 발언으로 보지 않는다.
            - 애매하면 낮은 점수를 준다. 확실한 것만 0.7 이상을 준다.
            - 문제가 없으면 빈 배열을 반환한다. 억지로 찾아내지 않는다.

            반드시 이 JSON 형식으로만 답한다:
            {"findings":[{"index":0,"category":"MOCKERY","score":0.8,"reason":"왜 논란이 될 수 있는지 한 문장"}]}

            index 는 입력으로 준 대본 줄의 번호다. score 는 0.0~1.0 확신도다.
            reason 은 한국어로 쓴다.
            """;

    private final OpenAiClient openAiClient;

    @Override
    public String key() {
        return "speech-risk";
    }

    @Override
    public String displayName() {
        return "발언 리스크 분석";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return context.hasTranscript() && openAiClient.isEnabled();
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<TranscriptSegment> transcript = context.transcript();
        List<RiskFinding> findings = new ArrayList<>();

        for (int start = 0; start < transcript.size(); start += WINDOW_SIZE - OVERLAP) {
            int end = Math.min(start + WINDOW_SIZE, transcript.size());
            List<TranscriptSegment> window = transcript.subList(start, end);

            findings.addAll(analyzeWindow(context, window, start));

            if (end == transcript.size()) break;
        }

        // 창이 겹치는 구간에서 같은 발언이 두 번 잡힐 수 있어 여기서 한 번 걸러준다
        List<RiskFinding> deduped = dedupe(findings);
        log.info("[speech-risk] videoId={} 창={}개 findings={} (중복제거 후 {})",
                context.video().getId(),
                (transcript.size() / Math.max(1, WINDOW_SIZE - OVERLAP)) + 1,
                findings.size(), deduped.size());
        return deduped;
    }

    private List<RiskFinding> analyzeWindow(AnalysisContext context,
                                            List<TranscriptSegment> window,
                                            int offset) {
        String userPrompt = buildPrompt(window);

        LlmResult result = openAiClient
                .completeAsJson(SYSTEM_PROMPT, userPrompt, LlmResult.class)
                .orElse(null);

        if (result == null || result.findings() == null) {
            return List.of();
        }

        List<RiskFinding> findings = new ArrayList<>();
        for (LlmFinding item : result.findings()) {
            int localIndex = item.index() == null ? -1 : item.index();
            if (localIndex < 0 || localIndex >= window.size()) {
                continue; // LLM 이 엉뚱한 번호를 준 경우 버린다
            }

            TranscriptSegment segment = window.get(localIndex);
            double score = item.score() == null ? 0.5 : Math.max(0.0, Math.min(1.0, item.score()));

            findings.add(RiskFinding.builder()
                    .video(context.video())
                    .eventType(TimelineEventType.SPEECH)
                    .category(RiskCategory.fromOrDefault(item.category(), RiskCategory.SENSITIVE_TOPIC))
                    .source(EvidenceSource.SUBTITLE)
                    .score(score)
                    .startMs(segment.getStartMs())
                    .endMs(segment.getEndMs())
                    .text(segment.getText())
                    .reason(item.reason())
                    .build());
        }
        return findings;
    }

    private String buildPrompt(List<TranscriptSegment> window) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < window.size(); i++) {
            TranscriptSegment s = window.get(i);
            lines.append("[%d] (%s) %s%n".formatted(i, formatTime(s.getStartMs()), s.getText()));
        }

        return "다음은 영상 대본이다. 논란이 될 수 있는 발언을 찾아라.\n\n" + lines;
    }

    /** 같은 (시작시각, 카테고리) 는 한 건으로 본다. 점수가 높은 쪽을 남긴다. */
    private List<RiskFinding> dedupe(List<RiskFinding> findings) {
        Map<String, RiskFinding> best = new HashMap<>();
        for (RiskFinding f : findings) {
            String key = f.getStartMs() + "|" + f.getCategory();
            RiskFinding existing = best.get(key);
            if (existing == null || f.getScore() > existing.getScore()) {
                best.put(key, f);
            }
        }
        return new ArrayList<>(best.values());
    }

    static String formatTime(long ms) {
        long totalSec = ms / 1000;
        return "%02d:%02d".formatted(totalSec / 60, totalSec % 60);
    }

    // ----- LLM 응답 매핑 -----
    record LlmResult(List<LlmFinding> findings) {}

    record LlmFinding(Integer index, String category, Double score, String reason) {}
}
