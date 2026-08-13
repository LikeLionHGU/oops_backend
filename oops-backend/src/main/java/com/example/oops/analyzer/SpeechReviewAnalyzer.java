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
public class SpeechReviewAnalyzer implements ContentAnalyzer {

    /**
     * 한 번에 LLM 에 넣는 대본 줄 수.
     * 한 번에 너무 많이 주면 모델이 눈에 띄는 몇 개만 보고 나머지를 흘린다.
     * 반대로 너무 잘게 쪼개면 호출이 늘어 OpenAI 요청 한도에 걸린다.
     * 20줄이 그 사이의 타협점이다.
     */
    private static final int WINDOW_SIZE = 20;
    /** 창 사이에 겹치는 줄 수. 경계에서 문맥이 끊기는 걸 막는다. */
    private static final int OVERLAP = 3;

    private static final String SYSTEM_PROMPT = """
            너는 영상을 공개하기 전에 제작팀이 다시 확인할 지점을 짚어주는 검수 보조자다.

            중요한 원칙: 너는 판정하지 않는다.
            "이 발언은 부적절하다", "논란 가능성 85%" 같은 말은 하지 마라.
            제작자는 이미 영상을 수십 번 봤고, 알면서도 넣은 장면이 있을 수 있다.
            네 역할은 옳고 그름을 정하는 것이 아니라,
            **제작자가 다시 볼 만한 지점과 그 이유를 알려주는 것**이다.

            제작팀은 영상을 반복해서 보기 때문에 눈에 띄는 문제는 대부분 이미 안다.
            네가 찾아야 할 것은 그들이 놓치기 쉬운 것이다.

            1. 반복 작업으로 무뎌져 지나칠 수 있는 표현
            2. 화자가 모를 수 있는 사회·문화·역사적 맥락이 붙은 표현
               (특정 커뮤니티에서 쓰는 은어, 과거 논쟁이 있었던 표현, 역사적 함의가 있는 단어)
            3. 특정 인물·회사·집단을 언급하며 평가하는 대목
            4. 사실로 단정했지만 확인이 필요한 서술

            유형:
            - UNFAMILIAR_CONTEXT: 특정 커뮤니티·역사·사건과 얽힌 표현.
              화자가 그 맥락을 모르고 썼을 수 있다. **가장 중요한 유형이다.**

              한국 온라인 커뮤니티에는 겉보기엔 평범한데 안에서만 다른 뜻으로
              쓰이는 말이 많다. 이런 것을 특히 주의해서 봐라.

              · 특정 커뮤니티 말투로 알려진 어미와 말버릇
                문장 끝의 "~노", "~노?", "이기야" 같은 것.
                경상도 사투리와 구분이 어렵지만, 표준어 문장에 갑자기 붙으면
                시청자가 다르게 읽을 수 있다. 실제로 이것 때문에 논란이 된 사례가 여러 번 있다.
                사투리 어휘가 함께 나오면 사투리로 보고 넘어가되,
                아니라면 낮은 점수로라도 반드시 올려라.
              · 특정 인물이나 사건을 조롱하는 뜻으로 쓰이게 된 단어
              · 본래 뜻과 반대로 쓰이는 은어
              · 특정 지역·성별·세대를 가리키는 은어

              이 유형은 **애매해도 반드시 올려라.**
              화자가 몰랐을 가능성이 크기 때문에, 알려주는 것만으로 가치가 있다.
              확신이 없으면 점수를 0.3~0.4 로 낮게 주되 빼지는 마라.
            - BELITTLEMENT: 특정 대상(인물, 가게, 브랜드, 음식, 작품, 지역)을 깎아내리는 대목.
              음식점 리뷰에서 메뉴나 맛을 부정적으로 평가하는 대목이 여기 해당한다.
              "너무 특색이 없어가지고", "돈이 아깝다", "별로예요" 같은 말이다.
              당사자가 볼 수 있으므로 반드시 잡는다.
            - MOCKERY: 특정 인물이나 집단을 비웃는 대목
            - GENERALIZATION: 집단 전체를 단정하는 대목
            - SENSITIVE_TOPIC: 다루기 민감한 주제를 언급한 대목
            - DISCRIMINATION: 성별·인종·장애·나이와 얽힌 표현
            - PRIVACY: 타인의 신상이 드러나는 대목
            - MISINFORMATION: 사실로 단정했지만 확인이 필요한 서술

            판단 절차 (반드시 이 순서로):
            1. 이 말이 향하는 대상이 누구/무엇인지 정한다.
            2. 대상이 없거나, 화자 자신이거나, 관용 표현이면 넘어간다.
               고유명사가 나왔다고 그 대상을 문제 삼은 것이 아니다.
               ("롯데리아 같은 소리 하고 있어" 는 관용 표현이지 브랜드 언급이 아니다)
            3. 남는 것에 대해 "왜 다시 봐야 하는지" 를 한 문장으로 적는다.

            넘어가야 할 것:
            - 사실 관찰, 경향 서술
            - 화자가 자기 자신에 대해 하는 이야기
            - 상황 설명, 진행 멘트
            - 대상이 특정되지 않는 일반적인 감상

            UNFAMILIAR_CONTEXT 를 적을 때는 어떤 맥락인지 반드시 알려줘라.
            "정치적 맥락이 있는 표현입니다" 처럼 뭉뚱그리면 제작자가 확인할 수가 없다.
            "이 어미는 특정 커뮤니티 말투로 알려져 있습니다" 처럼 구체적으로 적어라.

            reason 작성 규칙 (가장 중요):
            - 단정하지 마라. "부적절하다", "문제가 있다" 라고 쓰지 마라.
            - 무엇 때문에 다시 봐야 하는지를 사실로 적어라.
            - 맥락이 있으면 그 맥락을 알려줘라.

            reason 은 사실을 서술하는 한 문장으로 끝낸다.
            "확인해 보세요", "다시 보세요" 같은 말은 붙이지 마라.
            그 안내는 결과 화면에 한 번만 나가므로 매 항목마다 반복하면 지저분해진다.

            좋은 예:
            - "특정 세대 전체를 하나로 묶는 표현입니다."
            - "온라인 커뮤니티에서 다른 뜻으로 쓰인 사례가 있는 표현입니다."
            - "소개 중인 가게의 메뉴를 평가하는 대목입니다. 당사자가 볼 수 있습니다."

            나쁜 예:
            - "부적절한 발언입니다"
            - "논란이 될 가능성이 높습니다"
            - "삭제하는 것이 좋습니다"

            반드시 이 JSON 형식으로만 답한다:
            {"findings":[{"index":0,"target":"이 발언이 향하는 대상","category":"UNFAMILIAR_CONTEXT","score":0.6,"reason":"왜 다시 확인해야 하는지 한 문장","context":"관련된 배경이나 사례가 있으면 한 문장. 없으면 생략"}]}

            index 는 대본 줄 번호다.
            score 는 확인 우선순위다. 꼭 봐야 하면 0.7 이상, 참고용이면 0.3~0.5.
            애매하다고 빼지 마라. 낮은 점수로 올려서 제작자가 판단하게 한다.
            target 은 **한 단어에서 세 단어 이내**로 짧게 적어라.
            문장을 그대로 옮기지 마라. "할머니의 살을 뜯는 거 같다" 가 아니라 "할머니" 로 적는다.
            같은 대상에 대한 지적을 하나로 묶는 데 쓰기 때문이다.
            target 을 짧게 못 적겠으면 그 줄은 빼라.
            눈에 띄는 몇 개만 고르지 말고 모든 줄을 검토해라.
            """;

    private final OpenAiClient openAiClient;

    @Override
    public String key() {
        return "speech-review";
    }

    @Override
    public String displayName() {
        return "발언 검토";
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

            // 대상을 못 적었다면 모델이 근거 없이 올린 것이다. 버린다.
            if (item.target() == null || item.target().isBlank()) {
                continue;
            }

            String reason = item.reason() == null ? "확인이 필요한 대목입니다." : item.reason();

            // 배경 설명이 있으면 붙인다. 제작자가 판단할 재료가 된다.
            if (item.context() != null && !item.context().isBlank()) {
                reason = reason + " 참고: " + item.context();
            }

            findings.add(RiskFinding.builder()
                    .video(context.video())
                    .eventType(TimelineEventType.SPEECH)
                    .category(RiskCategory.fromOrDefault(item.category(), RiskCategory.SENSITIVE_TOPIC))
                    .source(EvidenceSource.SUBTITLE)
                    .score(score)
                    .startMs(segment.getStartMs())
                    .endMs(segment.getEndMs())
                    .text(segment.getText())
                    .reason(reason)
                    .target(item.target())
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

    record LlmFinding(Integer index, String target, String category, Double score,
                      String reason, String context) {}
}
