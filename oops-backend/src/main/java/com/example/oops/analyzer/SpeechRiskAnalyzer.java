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

    /**
     * 한 번에 LLM 에 넣는 대본 줄 수.
     * 한 번에 너무 많이 주면 모델이 눈에 띄는 몇 개만 보고 나머지를 흘린다.
     * 창을 줄이면 호출 수가 늘지만 놓치는 게 줄어든다.
     */
    private static final int WINDOW_SIZE = 15;
    /** 창 사이에 겹치는 줄 수. 경계에서 문맥이 끊기는 걸 막는다. */
    private static final int OVERLAP = 3;

    private static final String SYSTEM_PROMPT = """
            너는 유튜브 영상이 공개된 뒤 논란이 될 만한 발언을 미리 찾아주는 검수자다.
            자막 대본을 받아서 문제가 될 수 있는 발언만 골라낸다.

            판정할 카테고리:
            - MOCKERY: 특정 인물, 집단, 직업을 비웃거나 놀리는 발언
            - BELITTLEMENT: 특정 대상을 깎아내리는 발언.
              사람뿐 아니라 가게, 브랜드, 제품, 작품, 지역도 대상이 된다.
              예: "너무 특색이 없다", "돈이 아깝다", "실력이 형편없다"
              대상이 특정될 수 있으면(가게 이름, 인물명, 브랜드가 함께 나오거나
              영상 맥락상 무엇을 가리키는지 분명하면) 당사자가 반발할 수 있으므로 잡는다.
              단, 근거를 든 정당한 비평은 낮은 점수를 주고,
              근거 없이 깎아내리거나 인신공격에 가까우면 높은 점수를 준다.
            - GENERALIZATION: 특정 집단 전체를 부정적으로 단정하는 발언
              예: "OO들은 다 그런 사람들이다", "OO는 원래 이기적이다"
              주의: 단순한 경향 관찰이나 사회 변화 서술은 여기 해당하지 않는다.
              "요즘엔 한 잔만 마셔도 대리를 부르더라" 는 관찰이지 일반화가 아니다.
              대상이 사람 집단이 아니거나, 깎아내리는 뜻이 없으면 잡지 마라.
            - SENSITIVE_TOPIC: 정치, 종교, 젠더, 지역, 역사, 재난 등 다루기 민감한 주제
            - HATE_SPEECH: 특정 집단을 향한 혐오 표현
            - DISCRIMINATION: 성별, 인종, 장애, 나이에 따른 차별적 발언
            - MISINFORMATION: 사실로 단정했지만 근거가 불확실하고 논란 소지가 큰 주장
            - PRIVACY: 타인의 개인정보나 신상을 노출하는 발언

            판정 원칙:
            - 문맥을 보고 판단한다. 자기 자신을 낮추는 농담이나 친밀한 관계의 장난은 제외한다.
            - 인용이나 비판을 위해 언급한 표현은 화자의 발언으로 보지 않는다.
            - score 는 확신도다. 확실하면 0.7 이상, 애매하면 0.3~0.5 로 준다.
              **애매하다고 빼지 마라.** 낮은 점수로라도 올려서 검수자가 판단하게 한다.
              놓치는 것이 잘못 올리는 것보다 나쁘다.
            - 다만 명백히 아무 문제 없는 일상 대화까지 올리지는 마라.

            판단 절차 (반드시 이 순서로):
            1. 이 말이 향하는 대상이 누구/무엇인지 먼저 정한다.
            2. 그 대상을 실제로 깎아내리는지 본다.
            3. 대상이 없거나, 대상이 화자 자신이거나, 관용 표현이면 잡지 않는다.

            고유명사가 나왔다고 그 대상을 비판한 것이 아니다.
            한국어에는 브랜드나 이름을 빌려 쓰는 관용 표현이 많다.
            이런 경우 대상은 그 브랜드가 아니라 대화 상대이거나 아예 없다.

            잡지 말아야 하는 예시:
            - "롯데리아 같은 소리 하고 있어"   → 관용 표현. 대상은 대화 상대이지 롯데리아가 아니다.
            - "무슨 소리야 그게"              → 대상 없음
            - "제가 좀 못해서요"              → 대상이 화자 자신
            - "이 김치찌개 좀 짜네요"          → 특정 업체가 아닌 개별 음식에 대한 단순 감상

            반드시 잡아야 하는 예시:
            - "너무 특색이 없어가지고"      → BELITTLEMENT (대상: 소개 중인 가게/메뉴)
            - "저기는 진짜 별로예요"        → BELITTLEMENT (대상: 특정 장소)
            - "그 사람 좀 이상하지 않아요?"  → MOCKERY (대상: 특정 인물)
            - "몸에 안 좋은 패스트푸드"      → BELITTLEMENT (대상: 특정 업종)

            대본을 훑을 때 눈에 띄는 것 몇 개만 고르지 마라.
            모든 줄을 하나씩 검토하고, 해당하는 줄은 전부 올려라.

            다음은 논란이 아니다. 잡지 마라.
            - 사실 관찰, 통계나 경향 서술 ("요즘은 ~하는 추세다")
            - 화자가 자기 자신에 대해 하는 이야기 (자기 경험, 자학 농담)
            - 상황 설명, 진행 멘트, 정보 전달
            - 일반적인 조언이나 당부
            - 대상이 특정되지 않는 일반적인 감상 ("오늘 날씨가 별로다")

            반대로, 다음은 감상이나 개인 의견이어도 반드시 잡는다.
            - 특정 대상(가게, 제품, 작품, 인물, 지역)에 대한 부정적 평가
              당사자가 보면 반발할 수 있고, 실제로 논란이 되는 지점이다.
            - 특정 집단에 대한 부정적 단정

            누군가를 깎아내리거나, 편견을 강화하거나,
            시청자나 당사자가 불쾌해할 만한 지점이 있으면 보고한다.
            판단이 서지 않으면 낮은 점수로라도 올려라. 놓치는 것보다는 낫다.

            반드시 이 JSON 형식으로만 답한다:
            {"findings":[{"index":0,"target":"이 발언이 향하는 대상","category":"MOCKERY","score":0.8,"reason":"왜 논란이 될 수 있는지 한 문장"}]}

            index 는 입력으로 준 대본 줄의 번호다. score 는 0.0~1.0 확신도다.
            target 은 그 발언이 실제로 깎아내리는 대상이다. 빈칸으로 두지 마라.
            대상을 한 단어로 못 적겠으면 애초에 논란이 아니다. 그 줄은 빼라.
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

            // 대상을 못 적었다면 모델이 근거 없이 올린 것이다. 버린다.
            if (item.target() == null || item.target().isBlank()) {
                continue;
            }

            String reason = "(대상: %s) %s".formatted(
                    item.target(),
                    item.reason() == null ? "논란이 될 수 있습니다." : item.reason());

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

    record LlmFinding(Integer index, String target, String category, Double score, String reason) {}
}
