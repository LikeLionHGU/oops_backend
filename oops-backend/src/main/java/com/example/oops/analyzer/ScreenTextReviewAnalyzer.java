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
 * 화면에 박힌 자막(OCR)을 LLM 으로 판정한다.
 *
 * ScreenTextAnalyzer(룰)와 역할이 다르다.
 * 룰은 욕설·개인정보처럼 사전에 열거할 수 있는 것만 잡는다.
 * 하지만 "특정 시기에 민감한 정치 이슈" 같은 건 키워드로 나열할 수 없다.
 * 편집 자막에만 등장하고 발언에는 없는 민감 내용은 여기서만 잡힌다.
 *
 * OCR 결과는 글자가 자주 깨지므로, 프롬프트에서 그 점을 감안해 의도를 읽으라고 지시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenTextReviewAnalyzer implements ContentAnalyzer {

    private static final int WINDOW_SIZE = 20;
    private static final int OVERLAP = 2;

    private static final String SYSTEM_PROMPT = """
            너는 영상 공개 전에 제작팀이 다시 확인할 지점을 짚어주는 검수 보조자다.
            화면에 박혀 있던 편집 자막을 OCR 로 읽은 결과를 받는다.

            원칙: 판정하지 않는다. 확인할 지점과 이유만 알려준다.

            편집 자막은 편집자가 넣은 것이라 화자의 의도와 다를 수 있다.
            그래서 화자 본인이 최종본을 볼 때 놓치기 쉽다. 여기가 사각지대다.

            중요: 이 텍스트는 OCR 결과라 글자가 자주 깨져 있다.
            "재선거" 가 "재선커", "재신거" 처럼 나올 수 있다.
            조금 깨져 있어도 원래 무슨 말이었는지 추론해서 판단하고,
            도저히 알 수 없을 정도로 깨진 것은 무시해라.

            유형:
            - UNFAMILIAR_CONTEXT: 특정 커뮤니티·역사·사건과 얽힌 표현
            - BELITTLEMENT: 특정 대상을 깎아내리는 표현
            - MOCKERY: 특정 인물이나 집단을 비웃는 표현
            - GENERALIZATION: 집단 전체를 단정하는 표현
            - SENSITIVE_TOPIC: 다루기 민감한 주제
            - DISCRIMINATION: 성별·인종·장애·나이와 얽힌 표현
            - PRIVACY: 타인의 이름, 연락처, 소속이 드러남
            - PROFANITY: 욕설, 비속어

            판정 절차:
            1. 이 자막이 향하는 대상을 먼저 정한다.
            2. 대상이 없거나 관용 표현이면 넘어간다.
            3. 남는 것에 대해 왜 다시 봐야 하는지 적는다.

            넘어가야 할 것:
            - 채널명, 구독, 좋아요, 알림설정 같은 UI 텍스트
            - 사실 전달, 상황 설명, 진행 안내 자막

            reason 은 단정하지 말고, 무엇 때문에 다시 봐야 하는지를 사실로 적어라.
            "부적절합니다" 가 아니라 "이 표현은 ~한 맥락이 있습니다" 형태로 끝낸다.
            "확인해 보세요" 같은 안내는 붙이지 마라. 화면에 한 번만 나간다.

            반드시 이 JSON 형식으로만 답한다:
            {"findings":[{"index":0,"target":"이 자막이 향하는 대상","category":"UNFAMILIAR_CONTEXT","score":0.6,"reason":"왜 다시 확인해야 하는지 한 문장","reading":"깨진 글자를 복원한 원래 문구"}]}

            target 은 한 단어에서 세 단어 이내로 짧게 적는다.
            같은 대상에 대한 지적을 하나로 묶는 데 쓴다.

            index 는 자막 번호다. score 는 확인 우선순위다.
            애매하면 0.3~0.5 로 낮게 주되 빼지는 마라.
            reading 은 OCR 이 깨졌을 때만 적는다.
            """;

    private final OpenAiClient openAiClient;

    @Override
    public String key() {
        return "screen-text-review";
    }

    @Override
    public String displayName() {
        return "화면 자막 검토";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return context.hasScreenText() && openAiClient.isEnabled();
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<ScreenText> texts = context.screenTexts();
        List<RiskFinding> findings = new ArrayList<>();

        for (int start = 0; start < texts.size(); start += WINDOW_SIZE - OVERLAP) {
            int end = Math.min(start + WINDOW_SIZE, texts.size());
            findings.addAll(analyzeWindow(context, texts.subList(start, end)));
            if (end == texts.size()) break;
        }

        List<RiskFinding> deduped = dedupe(findings);
        log.info("[screen-text-risk] videoId={} 자막={}건 findings={} (중복제거 후 {})",
                context.video().getId(), texts.size(), findings.size(), deduped.size());
        return deduped;
    }

    private List<RiskFinding> analyzeWindow(AnalysisContext context, List<ScreenText> window) {
        StringBuilder prompt = new StringBuilder("다음은 영상 화면에서 읽은 자막이다.\n\n");
        for (int i = 0; i < window.size(); i++) {
            ScreenText t = window.get(i);
            prompt.append("[%d] (%s) %s%n".formatted(i, formatTime(t.getStartMs()), t.getText()));
        }

        LlmResult result = openAiClient
                .completeAsJson(SYSTEM_PROMPT, prompt.toString(), LlmResult.class)
                .orElse(null);

        if (result == null || result.findings() == null) {
            return List.of();
        }

        List<RiskFinding> findings = new ArrayList<>();
        for (LlmFinding item : result.findings()) {
            int index = item.index() == null ? -1 : item.index();
            if (index < 0 || index >= window.size()) continue;

            ScreenText target = window.get(index);
            double score = item.score() == null ? 0.5 : Math.max(0.0, Math.min(1.0, item.score()));

            // OCR 이 깨졌다면 LLM 이 복원한 문구를 함께 보여준다
            String caption = target.getText();
            if (item.reading() != null && !item.reading().isBlank()
                    && !item.reading().equals(caption)) {
                caption = "%s  (해석: %s)".formatted(caption, item.reading());
            }

            findings.add(RiskFinding.builder()
                    .video(context.video())
                    .eventType(TimelineEventType.CAPTION)
                    .category(RiskCategory.fromOrDefault(item.category(), RiskCategory.SENSITIVE_TOPIC))
                    .source(EvidenceSource.VISION)
                    .score(score)
                    .startMs(target.getStartMs())
                    .endMs(target.getEndMs())
                    .captionText(caption)
                    .frame(target.getFrame())
                    .reason(item.reason())
                    .target(item.target())
                    .build());
        }
        return findings;
    }

    /** 창이 겹치는 구간에서 같은 자막이 두 번 잡힐 수 있어 걸러준다. */
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

    private static String formatTime(long ms) {
        long totalSec = ms / 1000;
        return "%02d:%02d".formatted(totalSec / 60, totalSec % 60);
    }

    record LlmResult(List<LlmFinding> findings) {}

    record LlmFinding(Integer index, String target, String category, Double score,
                      String reason, String reading) {}
}
