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
public class ScreenTextRiskAnalyzer implements ContentAnalyzer {

    private static final int WINDOW_SIZE = 20;
    private static final int OVERLAP = 2;

    private static final String SYSTEM_PROMPT = """
            너는 유튜브 영상이 공개된 뒤 논란이 될 만한 부분을 미리 찾아주는 검수자다.
            영상 화면에 박혀 있던 텍스트(편집 자막, 자막 효과, 화면 문구)를 OCR 로 읽은 결과를 받는다.

            중요: 이 텍스트는 OCR 인식 결과라 글자가 자주 깨져 있다.
            예를 들어 "재선거"가 "재선커", "재신거", "쿄공운 재선거" 처럼 나올 수 있다.
            글자가 조금 깨져 있어도 원래 무슨 말이었는지 추론해서 판단해라.
            다만 도저히 의미를 알 수 없을 정도로 깨진 것은 무시한다.

            판정할 카테고리:
            - SENSITIVE_TOPIC: 선거, 정치, 정당, 특정 정치인, 종교, 젠더, 지역 갈등, 역사 분쟁,
              재난, 사건사고 등 시청자에 따라 강한 반응이 나올 수 있는 주제
            - MOCKERY: 특정 인물, 집단, 직업을 비웃거나 놀리는 표현
            - BELITTLEMENT: 특정 대상을 깎아내리는 표현.
              사람뿐 아니라 가게, 브랜드, 제품, 작품, 지역도 대상이 된다.
              근거를 든 비평은 낮은 점수, 근거 없는 비하는 높은 점수를 준다.
            - GENERALIZATION: 특정 집단 전체를 부정적으로 단정하는 표현
              단순한 경향 서술이나 사실 전달은 해당하지 않는다
            - HATE_SPEECH: 특정 집단을 향한 혐오 표현
            - DISCRIMINATION: 성별, 인종, 장애, 나이에 따른 차별적 표현
            - PROFANITY: 욕설, 비속어
            - SEXUAL: 선정적 표현
            - VIOLENCE: 폭력적 표현
            - MISINFORMATION: 사실로 단정했지만 근거가 불확실한 주장
            - PRIVACY: 타인의 이름, 연락처, 소속 등 신상 노출
            - ADVERTISING: 광고나 협찬을 숨기는 표현

            판정 원칙:
            - 편집 자막은 제작자가 의도적으로 넣은 것이므로, 발언보다 책임 소재가 명확하다.
            - 정치·선거 관련 표현은 그 자체로 중립적이어도, 영상 공개 시점에 따라 논란이 될 수 있으므로
              SENSITIVE_TOPIC 으로 표시한다. 다만 단순 정보 전달이면 점수를 낮게 준다.
            - 채널명, 구독, 좋아요, 알림설정, 재생시간 같은 UI 텍스트는 무시한다.
            - 사실 전달, 상황 설명, 진행 안내 자막은 논란이 아니다.
            - 단, 특정 대상(가게, 제품, 인물)에 대한 부정적 평가는
              감상 형태여도 당사자가 반발할 수 있으므로 잡는다.
            - 문제가 없으면 빈 배열을 반환한다. 억지로 찾아내지 않는다.

            반드시 이 JSON 형식으로만 답한다:
            {"findings":[{"index":0,"category":"SENSITIVE_TOPIC","score":0.7,"reason":"한 문장 설명","reading":"깨진 글자를 복원한 원래 문구"}]}

            index 는 입력으로 준 자막의 번호다. score 는 0.0~1.0 확신도다.
            reading 은 OCR 이 깨졌을 때 네가 추론한 원문이고, 깨지지 않았으면 생략해도 된다.
            reason 은 한국어로 쓴다.
            """;

    private final OpenAiClient openAiClient;

    @Override
    public String key() {
        return "screen-text-risk";
    }

    @Override
    public String displayName() {
        return "화면 자막 리스크 분석";
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

    record LlmFinding(Integer index, String category, Double score, String reason, String reading) {}
}
