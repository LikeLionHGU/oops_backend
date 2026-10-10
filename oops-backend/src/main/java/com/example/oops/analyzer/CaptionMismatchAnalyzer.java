package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 실제 발언과 편집 자막이 다른 지점을 찾는다.
 *
 * 편집을 남에게 맡기는 구조에서 가장 흔한 사각지대다.
 * 출연자는 자기가 한 말을 기억하기 때문에 최종본을 봐도 자막 차이를 잘 못 느낀다.
 * 시청자가 먼저 발견하는 일이 생긴다.
 *
 * **2026-10 고도화: 자막을 SRT 파일로 받으면서 다시 켰다.**
 * 껐던 이유는 OCR 이 편집 자막과 화면 속 글자(간판, 메뉴판, 채널 로고)를 구분하지 못해서
 * "발언은 A 인데 자막은 'POGUES' 다" 같은 의미 없는 결과가 대부분이었기 때문이다.
 * SRT 는 편집 자막만 정확한 글자로 들어온다. 이제 글자가 틀릴 수 있는 쪽은 음성 인식이다.
 * 프롬프트와 비교 기준도 그 방향으로 바꿨다.
 *
 * 짝짓기도 바꿨다. 예전에는 발언 하나와 자막 하나를 1:1 로 짝지었는데,
 * 자막은 보통 발언보다 짧게 끊겨서 한 발언에 자막이 여러 줄 걸린다.
 * 지금은 **발언 한 줄 기준으로, 그 발언에 붙은 자막 줄을 이어 붙여** 비교한다.
 * 자막 한 줄은 시간이 가장 많이 겹치는 발언 하나에만 붙는다.
 *
 * 비교는 양방향이다.
 * - 자막 → 발언: 자막에 발언에 없던 말이 생겼는지 (표현 강화, 내용 추가)
 * - 발언 → 자막: 발언의 말이 자막에서 빠졌는지 (욕설 순화, 부정어 삭제)
 * 한쪽만 보면 "그건 사실이 아니에요" → "그건 사실이에요" 처럼 글자를 빼기만 한 자막이
 * 발언에 다 들어 있다는 이유로 받아 적은 자막으로 빠진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaptionMismatchAnalyzer implements ContentAnalyzer {

    /** 한 번에 LLM 에 보내는 쌍의 개수 */
    private static final int BATCH_SIZE = 15;

    /**
     * 자막 글자가 이 비율 이상 같은 시간 발언에 들어 있으면 받아 적은 자막으로 보고 비교하지 않는다.
     *
     * 음성 인식이 몇 글자 틀려도 받아 적은 자막은 여기서 걸러진다.
     * 너무 낮추면 "안 좋아" → "좋아" 처럼 글자 몇 개로 뜻이 뒤집힌 자막까지 빠지므로 높게 둔다.
     */
    static final double VERBATIM_THRESHOLD = 0.9;

    /**
     * 발언 내용이 이 비율 이상 자막에 들어 있으면 빠진 말이 없다고 본다.
     * 발언 쪽은 음성 인식 오류가 섞여 자막에 없는 글자가 생기므로 자막 쪽보다 조금 낮게 둔다.
     */
    static final double SPEECH_KEPT_THRESHOLD = 0.8;

    /**
     * 이 점수 아래는 버린다. (2026-10 고도화 · 1차 테스트 후)
     * 첫 테스트에서 "그냥 → 내가", 음성 인식 오류 같은 소소한 차이가 카드 13장 중 대부분이었다.
     * 큰 의미 차이만 남기려고 모델에게 MAJOR/MINOR 를 따로 묻고, MAJOR 라도 점수가 낮으면 뺀다.
     */
    static final double MIN_SCORE = 0.6;

    /**
     * 자막이 이 비율 이상 발언과 시간이 겹쳐야 그 발언의 자막으로 본다.
     * 여유 시간(700ms)만으로 짝지으면 다음 사람이 한 말의 자막이 앞 발언에 붙어
     * "자막이 시장 가자는 말을 덧붙였다" 같은 엉뚱한 결과가 나왔다.
     */
    static final double MIN_TIME_OVERLAP = 0.4;

    static final String SYSTEM_PROMPT = """
            너는 영상 공개 전에 확인할 지점을 짚어주는 검수 보조자다.
            같은 장면의 (실제 발언, 편집 자막) 쌍을 받는다. 앞뒤 자막도 참고용으로 함께 준다.
            - 발언은 음성 인식 결과라 글자가 자주 틀린다.
            - 자막은 편집자가 직접 입력한 것이라 글자는 정확하다.

            목표: 시청자가 자막만 보고 실제 발언과 **다른 메시지**를 받게 되는 경우만 찾는다.
            사소한 차이는 전부 무시한다. 애매하면 MINOR 다.

            MAJOR (알린다) — 아래 셋 중 하나에 분명히 해당할 때만
            - PROVOCATIVE: 발언에 없던 비하·욕설·성적 의미·조롱·차별 표현이 자막에서 새로 생겼다
            - SOFTENED: 특정 대상을 향한 욕설·비하·차별·위험한 말이 자막에서 빠지거나 순화돼
              실제보다 훨씬 온건하게 읽힌다
            - DISTORTED: 사실관계가 바뀌었다 — 부정·긍정이 뒤집힘, 숫자·날짜·이름·대상이 바뀜,
              하지 않은 주장이 들어감

            MINOR (알리지 않는다) — 반드시 무시
            - 음성 인식 오류: 발음이 비슷한 다른 단어, 뜻이 통하지 않는 발언 글자
              (예: 발언 "반산두종" ↔ 자막 "반찬 투정"). 발언이 말이 안 되면 자막이 맞게 옮긴 것이다.
            - 소리가 같은 말을 성적·비하 의미로 넓혀 읽지 마라. 앞뒤 맥락이 평범하면 평범한 뜻이다
              (예: 잠을 안 잔다는 이야기 중의 "자지도 못")
            - 주어·대상을 밝히거나 뺀 것 ("그냥" → "내가"), 상황 설명을 덧붙인 것, 요약·축약
            - 말버릇·추임새·감탄형 욕설(대상 없는 "씨발", "존나")을 뺀 것, 말줄임표
            - 자막이 바로 앞뒤의 다른 발언이나 다른 사람 말을 옮긴 것으로 보이는 경우 (시간이 살짝 어긋남)
            - 표기·맞춤법 교정, 사투리를 표준어로 옮긴 것
            - 예능 자막·효과 문구 (단, 그 자막에 새로운 비하·성적 의미가 있으면 PROVOCATIVE)

            반드시 이 JSON 형식으로만 답한다. MINOR 는 넣지 않아도 된다:
            {"findings":[{"index":0,"level":"MAJOR","type":"DISTORTED","score":0.8,"reason":"어떻게 달라졌는지 한 문장"}]}

            reason 은 "발언은 A 인데 자막은 B 로 되어 있어, ~로 읽힙니다." 처럼 사실과 달라진 의미만 적는다.
            "확인해 보세요" 같은 안내는 붙이지 않는다.
            score 는 의미가 확실히 달라졌으면 0.8 이상, MAJOR 지만 해석 여지가 있으면 0.6.
            문제가 없으면 {"findings":[]} 를 반환한다. 한국어로 쓴다.
            """;

    private final OpenAiClient openAiClient;

    @Override
    public String key() {
        return "caption-mismatch";
    }

    @Override
    public String displayName() {
        return "발언과 자막 비교";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return context.hasTranscript() && context.hasScreenText() && openAiClient.isEnabled();
    }

    /** 대상을 창 단위로 훑으므로 길이에 비례해 호출이 는다. */
    @Override
    public boolean scalesWithLength() {
        return true;
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<Pair> pairs = matchByTime(context);
        if (pairs.isEmpty()) {
            log.info("[caption-mismatch] 비교할 쌍이 없습니다 (자막이 모두 발언을 그대로 옮김). videoId={}",
                    context.video().getId());
            return List.of();
        }

        List<RiskFinding> findings = new ArrayList<>();
        for (int start = 0; start < pairs.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, pairs.size());
            findings.addAll(analyzeBatch(context, pairs.subList(start, end)));
        }

        log.info("[caption-mismatch] videoId={} 자막={}줄 비교할 발언={} findings={}",
                context.video().getId(), context.screenTexts().size(), pairs.size(), findings.size());
        return findings;
    }

    /**
     * 발언 한 줄마다 그 발언에 붙은 자막을 이어 붙여 짝짓는다.
     * 자막이 없는 발언(자막을 안 단 구간), 발언과 자막이 양방향으로 거의 같은 줄은 뺀다.
     */
    List<Pair> matchByTime(AnalysisContext context) {
        List<TranscriptSegment> transcript = context.transcript();
        java.util.Map<TranscriptSegment, List<ScreenText>> bySpeech = new java.util.LinkedHashMap<>();
        for (ScreenText caption : context.screenTexts()) {
            if (caption.getText() == null || caption.getText().isBlank()) continue;
            TranscriptSegment best = bestSpeech(caption, transcript);
            if (best != null && overlapRatio(caption, best) >= MIN_TIME_OVERLAP) {
                bySpeech.computeIfAbsent(best, k -> new ArrayList<>()).add(caption);
            }
        }

        List<Pair> pairs = new ArrayList<>();
        for (var entry : bySpeech.entrySet()) {
            String speech = entry.getKey().getText();
            if (speech == null || speech.isBlank()) continue;
            List<ScreenText> captions = entry.getValue();
            String captionText = String.join(" ", captions.stream().map(ScreenText::getText).toList());

            boolean nothingAdded = CaptionAlignment.coverage(captionText, speech) >= VERBATIM_THRESHOLD;
            boolean nothingDropped = CaptionAlignment.coverage(speech, captionText) >= SPEECH_KEPT_THRESHOLD;
            if (nothingAdded && nothingDropped) continue;

            pairs.add(new Pair(captions, captionText, speech));
        }
        return pairs;
    }

    /** 자막 길이 중 발언과 실제로 겹치는 비율. 길이가 0인 자막은 겹치기만 하면 1. */
    static double overlapRatio(ScreenText caption, TranscriptSegment speech) {
        long overlap = CaptionAlignment.overlapMs(caption.getStartMs(), caption.getEndMs(),
                speech.getStartMs(), speech.getEndMs());
        long length = caption.getEndMs() - caption.getStartMs();
        if (length <= 0) return overlap >= 0 ? 1.0 : 0.0;
        return Math.max(0, overlap) / (double) length;
    }

    /** 자막과 시간이 가장 많이 겹치는 발언. 여유 시간 안에 겹치는 발언이 없으면 null. */
    static TranscriptSegment bestSpeech(ScreenText caption, List<TranscriptSegment> transcript) {
        TranscriptSegment best = null;
        long bestOverlap = Long.MIN_VALUE;
        for (TranscriptSegment s : CaptionAlignment.overlapping(caption, transcript)) {
            long overlap = CaptionAlignment.overlapMs(caption.getStartMs(), caption.getEndMs(),
                    s.getStartMs(), s.getEndMs());
            if (overlap > bestOverlap) {
                best = s;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    private List<RiskFinding> analyzeBatch(AnalysisContext context, List<Pair> batch) {
        StringBuilder prompt = new StringBuilder("같은 장면의 발언과 편집 자막이다.\n\n");
        List<ScreenText> all = context.screenTexts();
        for (int i = 0; i < batch.size(); i++) {
            Pair pair = batch.get(i);
            prompt.append("[%d] 발언: %s%n     자막: %s%n".formatted(i, pair.speech(), pair.captionText()));
            String before = neighbour(all, pair.captions().get(0), -1);
            String after = neighbour(all, pair.captions().get(pair.captions().size() - 1), +1);
            if (!before.isEmpty() || !after.isEmpty()) {
                prompt.append("     (앞 자막: %s / 뒤 자막: %s)%n".formatted(
                        before.isEmpty() ? "없음" : before, after.isEmpty() ? "없음" : after));
            }
            prompt.append('\n');
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
            if (index < 0 || index >= batch.size()) continue;

            // 큰 의미 차이만 남긴다
            if (!"MAJOR".equalsIgnoreCase(item.level() == null ? "" : item.level().trim())) continue;
            if (typeLabel(item.type()).isEmpty()) continue;
            if (item.score() == null || item.score() < MIN_SCORE) continue;

            String reason = item.reason() == null ? "" : item.reason().trim();
            if (!VagueReasonFilter.isUseful(reason)) continue;

            Pair pair = batch.get(index);
            double score = Math.max(0.0, Math.min(1.0, item.score()));

            findings.add(RiskFinding.builder()
                    .video(context.video())
                    .eventType(TimelineEventType.CAPTION)
                    .category(RiskCategory.CAPTION_MISMATCH)
                    .source(EvidenceSource.SUBTITLE)
                    .score(score)
                    .startMs(pair.startMs())
                    .endMs(pair.endMs())
                    .speechText(pair.speech())
                    .captionText(pair.captionText())
                    .frame(pair.captions().get(0).getFrame())
                    .reason("(발언과 자막 차이%s) %s".formatted(typeLabel(item.type()), reason))
                    .build());
        }
        return findings;
    }

    private static String typeLabel(String type) {
        if (type == null) return "";
        return switch (type.trim().toUpperCase()) {
            case "SOFTENED" -> " · 순화";
            case "DISTORTED" -> " · 의미 변화";
            case "PROVOCATIVE" -> " · 표현 강화";
            default -> "";
        };
    }

    /** 발언 한 줄과 거기 붙은 자막 줄들. 자막은 시간순이고 하나 이상이다. */
    record Pair(List<ScreenText> captions, String captionText, String speech) {
        long startMs() {
            return captions.get(0).getStartMs();
        }

        long endMs() {
            return captions.stream().mapToLong(ScreenText::getEndMs).max().orElse(startMs());
        }
    }

    record LlmResult(List<LlmFinding> findings) {}

    record LlmFinding(Integer index, String level, String type, Double score, String reason) {}

    /** 자막 목록에서 바로 앞(-1) 또는 뒤(+1) 자막 글. 없으면 빈 문자열. */
    private static String neighbour(List<ScreenText> all, ScreenText of, int direction) {
        int i = all.indexOf(of);
        int j = i + direction;
        if (i < 0 || j < 0 || j >= all.size()) return "";
        String t = all.get(j).getText();
        return t == null ? "" : t.trim();
    }
}
