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
 * 이른바 "노란 딱지" 를 미리 잡아낸다.
 *
 * 크리에이터에게는 논란보다 이쪽이 더 직접적인 손해다.
 * 영상을 올린 뒤에야 알게 되고, 그때는 이미 초기 조회수가 다 지나간 뒤다.
 *
 * 유튜브 광고주 친화적인 콘텐츠 가이드라인의 14개 주제를 기준으로 판정한다.
 * https://support.google.com/youtube/answer/6162278
 *
 * 한계를 분명히 해둔다.
 * 우리가 볼 수 있는 것은 발언(STT)과 화면에 박힌 글자(OCR)뿐이다.
 * 유혈, 노출, 충격적인 장면 같은 시각 요소는 판단할 수 없다.
 * 따라서 "안전하다" 는 보장은 못 하고, "이 부분이 걸릴 수 있다" 만 알려준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonetizationRiskAnalyzer implements ContentAnalyzer {

    private static final int WINDOW_SIZE = 30;
    private static final int OVERLAP = 3;

    /** 유튜브는 영상 초반과 제목·썸네일을 특히 엄격하게 본다. */
    private static final long EARLY_PART_MS = 15_000;

    private static final String SYSTEM_PROMPT = """
            너는 유튜브 채널의 수익화 담당자다.
            영상의 발언과 화면 자막을 받아서, 광고가 제한될 만한 부분을 미리 찾아낸다.

            유튜브 광고주 친화적인 콘텐츠 가이드라인의 14개 주제를 기준으로 본다.

            1. 부적절한 언어 — 욕설, 비속어
            2. 폭력 — 유혈, 부상, 폭력 조장
            3. 성인용 콘텐츠 — 성적 표현, 과도한 노출 언급
            4. 충격적인 콘텐츠 — 혐오감, 신체 부위·체액
            5. 유해한 행위 및 신뢰할 수 없는 콘텐츠 — 위험한 챌린지, 잘못된 의료·과학 정보
            6. 증오성 및 경멸적인 콘텐츠 — 보호 대상 집단 비하, 차별, 괴롭힘
            7. 기분전환용 약물 및 마약
            8. 총기 관련 콘텐츠
            9. 논란의 소지가 있는 문제 — 학대, 자해, 자살, 식이장애, 가정폭력, 낙태 등
            10. 민감한 사건 — 테러, 재난, 참사, 전쟁
            11. 부정 행위 조장 — 해킹, 사기, 시험 부정, 계정 판매
            12. 아동과 가족에게 부적절한 콘텐츠
            13. 도발, 비하 — 특정인·집단을 모욕하거나 창피 주기
            14. 담배 관련 콘텐츠 — 흡연, 전자담배 홍보

            등급은 세 가지다.
            - DEMONETIZED: 광고가 아예 안 붙는다. 명백하고 심각한 위반.
            - LIMITED: 광고가 제한된다. 이른바 노란 딱지.
            - OK: 문제없다. 보고하지 않는다.

            가장 중요한 원칙 — 맥락이 결정한다.
            유튜브는 같은 내용이라도 어떤 맥락에서 다뤘는지에 따라 다르게 본다.
            - 교육, 뉴스 보도, 다큐멘터리, 예술적 표현은 상당 부분 허용된다.
            - 마약을 "설명" 하는 것과 "미화" 하는 것은 완전히 다르다.
            - 사건을 "보도" 하는 것과 "자극적으로 소비" 하는 것은 다르다.
            - 욕설이 이따금 나오는 것은 문제가 아니다.
              영상 대부분에서 욕설을 쓰거나, 제목·초반에 강한 욕설이 나올 때 걸린다.

            위치도 중요하다.
            영상 초반 15초 안에 나오는 내용은 더 엄격하게 본다.
            입력에 [초반] 표시가 있으면 그 점을 감안해라.

            판정 원칙:
            - 확실한 것만 보고한다. 애매하면 보고하지 마라.
            - 단어 하나만 보고 판단하지 말고 문장 전체의 의도를 봐라.
            - 일반적인 대화, 정보 전달, 일상 표현은 문제가 아니다.
            - 문제가 없으면 빈 배열을 반환한다.

            반드시 이 JSON 형식으로만 답한다:
            {"findings":[{"index":0,"level":"LIMITED","topic":"부적절한 언어","score":0.8,"reason":"왜 걸리는지 한 문장","suggestion":"어떻게 고치면 되는지 한 문장"}]}

            index 는 입력으로 준 줄 번호다.
            topic 은 위 14개 주제 이름 중 하나를 그대로 쓴다.
            score 는 0.0~1.0 확신도다.
            reason 과 suggestion 은 한국어로 쓴다.
            suggestion 은 실제로 실행 가능한 조치여야 한다 (예: "해당 단어를 묵음 처리하세요").
            """;

    private final OpenAiClient openAiClient;

    @Override
    public String key() {
        return "monetization";
    }

    @Override
    public String displayName() {
        return "수익화 위험 검토";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        // 유형과 무관하게 모든 영상에서 본다. 노란 딱지는 장르를 가리지 않는다.
        return (context.hasTranscript() || context.hasScreenText()) && openAiClient.isEnabled();
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<Line> lines = collectLines(context);
        if (lines.isEmpty()) {
            return List.of();
        }

        List<RiskFinding> findings = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += WINDOW_SIZE - OVERLAP) {
            int end = Math.min(start + WINDOW_SIZE, lines.size());
            findings.addAll(analyzeWindow(context, lines.subList(start, end)));
            if (end == lines.size()) break;
        }

        List<RiskFinding> deduped = dedupe(findings);
        log.info("[monetization] videoId={} 검토={}줄 findings={} (중복제거 후 {})",
                context.video().getId(), lines.size(), findings.size(), deduped.size());
        return deduped;
    }

    private List<RiskFinding> analyzeWindow(AnalysisContext context, List<Line> window) {
        StringBuilder prompt = new StringBuilder("영상의 발언과 화면 자막이다.\n\n");
        for (int i = 0; i < window.size(); i++) {
            Line line = window.get(i);
            prompt.append("[%d]%s (%s) %s%n".formatted(
                    i,
                    line.startMs() < EARLY_PART_MS ? "[초반]" : "",
                    line.type() == TimelineEventType.SPEECH ? "발언" : "화면자막",
                    line.text()));
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

            AdSuitability level = AdSuitability.fromOrDefault(item.level(), null);
            if (level == null || level == AdSuitability.MONETIZED) continue;

            Line line = window.get(index);
            double score = item.score() == null ? 0.6 : Math.max(0.0, Math.min(1.0, item.score()));

            // 수익화 문제는 등급 자체가 심각도다. 확신도가 낮아도 광고 불가는 크게 표시한다.
            if (level == AdSuitability.DEMONETIZED) {
                score = Math.max(score, 0.8);
            }

            findings.add(build(context, line, level, item, score));
        }
        return findings;
    }

    private RiskFinding build(AnalysisContext context, Line line,
                              AdSuitability level, LlmFinding item, double score) {
        String reason = "[%s · %s] %s".formatted(
                level.getLabel(),
                item.topic() == null ? "가이드라인" : item.topic(),
                item.reason() == null ? "광고 게재에 부적합할 수 있습니다." : item.reason());

        if (item.suggestion() != null && !item.suggestion().isBlank()) {
            reason = reason + " → " + item.suggestion();
        }

        RiskFinding.RiskFindingBuilder builder = RiskFinding.builder()
                .video(context.video())
                .eventType(line.type())
                .category(level == AdSuitability.DEMONETIZED
                        ? RiskCategory.AD_DEMONETIZED : RiskCategory.AD_LIMITED)
                .source(line.type() == TimelineEventType.SPEECH
                        ? EvidenceSource.SUBTITLE : EvidenceSource.VISION)
                .score(score)
                .startMs(line.startMs())
                .endMs(line.endMs())
                .reason(reason)
                .frame(line.frame());

        if (line.type() == TimelineEventType.SPEECH) {
            builder.text(line.text());
        } else {
            builder.captionText(line.text());
        }
        return builder.build();
    }

    /** 발언과 화면 자막을 시간순으로 합친다. 유튜브도 둘을 나눠 보지 않는다. */
    private List<Line> collectLines(AnalysisContext context) {
        List<Line> lines = new ArrayList<>();

        if (context.hasTranscript()) {
            for (TranscriptSegment s : context.transcript()) {
                lines.add(new Line(TimelineEventType.SPEECH,
                        s.getStartMs(), s.getEndMs(), s.getText(), null));
            }
        }
        if (context.hasScreenText()) {
            for (ScreenText s : context.screenTexts()) {
                lines.add(new Line(TimelineEventType.CAPTION,
                        s.getStartMs(), s.getEndMs(), s.getText(), s.getFrame()));
            }
        }
        lines.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return lines;
    }

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

    private record Line(TimelineEventType type, long startMs, long endMs,
                        String text, VideoFrame frame) {}

    record LlmResult(List<LlmFinding> findings) {}

    record LlmFinding(Integer index, String level, String topic,
                      Double score, String reason, String suggestion) {}
}
