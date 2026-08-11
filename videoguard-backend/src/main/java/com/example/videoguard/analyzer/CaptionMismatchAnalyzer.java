package com.example.videoguard.analyzer;

import com.example.videoguard.client.OpenAiClient;
import com.example.videoguard.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 화면에 박힌 편집 자막(OCR)과 실제 발언(STT)을 같은 시간대끼리 대조한다.
 *
 * 잡으려는 것:
 *  - 발언은 수위가 높은데 자막에서 순화해 놓은 경우 ("병신" → "바보")
 *  - 자막이 발언에 없는 말을 덧붙여 의미를 바꾼 경우
 *  - 자막에만 등장하는 자극적 문구 (어그로성 편집 자막)
 *
 * 시간대 매칭까지는 규칙으로 하고, 의미가 어긋나는지 판단만 LLM 에 맡긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaptionMismatchAnalyzer implements ContentAnalyzer {

    /** 두 구간이 이 비율 이상 겹치면 같은 장면으로 본다 */
    private static final double MIN_OVERLAP_RATIO = 0.3;
    /** 한 번에 LLM 에 보내는 쌍의 개수 */
    private static final int BATCH_SIZE = 15;

    /**
     * 글자가 이 비율 이상 같으면 같은 말로 보고 아예 검사하지 않는다.
     *
     * OCR 은 한글 자막을 자주 잘못 읽는다. "왜 나오노" 가 "리국 왜나오노" 로 나오는 식이다.
     * 이걸 그대로 LLM 에 주면 "자막이 발언을 왜곡했다" 고 판정해 버린다.
     * 실제로는 자막이 발언을 그대로 옮긴 것이고 OCR 이 틀린 것뿐이다.
     */
    private static final double SAME_TEXT_THRESHOLD = 0.55;

    private static final String SYSTEM_PROMPT = """
            너는 영상의 화면 자막과 실제 발언을 대조하는 검수자다.
            같은 장면의 (발언, 화면자막) 쌍을 받아서 문제가 되는 것만 골라낸다.

            문제로 판정할 경우:
            - SOFTENED: 실제 발언은 수위가 높은데 자막이 순화해서 시청자를 오도한다
            - DISTORTED: 자막이 발언에 없는 내용을 넣어 의미를 바꾼다
            - PROVOCATIVE: 자막이 발언보다 훨씬 자극적이거나 단정적이다

            문제가 아닌 경우 (반드시 제외):
            - 단순 오탈자, 띄어쓰기, 조사 차이
            - 말을 줄여 쓴 요약 자막 (의미가 같으면 정상이다)
            - OCR 인식 오류로 보이는 깨진 글자
            - 발언과 무관한 화면 UI 텍스트 (구독, 좋아요, 채널명 등)
            - 화면 자막이 발언과 아예 무관한 다른 문구인 경우
              (예능 자막, 효과음 표기, 진행 안내 등은 원래 발언과 다른 게 정상이다)

            각 쌍에는 "글자 일치율" 이 함께 주어진다.
            일치율이 30% 를 넘으면 같은 말을 OCR 이 잘못 읽었을 가능성이 크다.
            그런 경우는 왜곡으로 보지 말고 넘어가라.
            정말로 의미가 뒤집히거나 없던 주장이 추가된 경우에만 보고해라.

            반드시 이 JSON 형식으로만 답한다:
            {"findings":[{"index":0,"type":"SOFTENED","score":0.7,"reason":"한 문장 설명"}]}

            문제가 없으면 {"findings":[]} 를 반환한다. reason 은 한국어로 쓴다.
            """;

    private final OpenAiClient openAiClient;

    @Override
    public String key() {
        return "caption-mismatch";
    }

    @Override
    public String displayName() {
        return "자막-발언 비교";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return context.hasTranscript() && context.hasScreenText() && openAiClient.isEnabled();
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<Pair> pairs = matchByTime(context);
        if (pairs.isEmpty()) {
            log.info("[caption-mismatch] 시간이 겹치는 쌍이 없습니다. videoId={}", context.video().getId());
            return List.of();
        }

        List<RiskFinding> findings = new ArrayList<>();
        for (int start = 0; start < pairs.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, pairs.size());
            findings.addAll(analyzeBatch(context, pairs.subList(start, end)));
        }

        log.info("[caption-mismatch] videoId={} 쌍={} findings={}",
                context.video().getId(), pairs.size(), findings.size());
        return findings;
    }

    /** 발언 구간과 화면 자막 구간을 시간축에서 겹치는 것끼리 짝지운다. */
    private List<Pair> matchByTime(AnalysisContext context) {
        List<Pair> pairs = new ArrayList<>();

        for (TranscriptSegment speech : context.transcript()) {
            for (ScreenText caption : context.screenTexts()) {
                long overlapStart = Math.max(speech.getStartMs(), caption.getStartMs());
                long overlapEnd = Math.min(speech.getEndMs(), caption.getEndMs());
                long overlap = overlapEnd - overlapStart;
                if (overlap <= 0) continue;

                long speechLength = Math.max(1, speech.getEndMs() - speech.getStartMs());
                if ((double) overlap / speechLength < MIN_OVERLAP_RATIO) continue;

                double similarity = similarity(speech.getText(), caption.getText());
                if (similarity >= SAME_TEXT_THRESHOLD) {
                    // 같은 말인데 OCR 이 일부 글자를 틀린 경우. 왜곡이 아니다.
                    continue;
                }

                pairs.add(new Pair(speech, caption, similarity));
            }
        }
        return pairs;
    }

    private List<RiskFinding> analyzeBatch(AnalysisContext context, List<Pair> batch) {
        StringBuilder prompt = new StringBuilder("같은 장면의 발언과 화면 자막이다.\n\n");
        for (int i = 0; i < batch.size(); i++) {
            Pair pair = batch.get(i);
            // 글자 일치율을 같이 준다. 높을수록 OCR 오인식일 가능성이 크다는 힌트다.
            prompt.append("[%d] 발언: %s%n     자막: %s%n     글자 일치율: %d%%%n%n"
                    .formatted(i, pair.speech().getText(), pair.caption().getText(),
                            Math.round(pair.similarity() * 100)));
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

            Pair pair = batch.get(index);
            double score = item.score() == null ? 0.5 : Math.max(0.0, Math.min(1.0, item.score()));

            findings.add(RiskFinding.builder()
                    .video(context.video())
                    .eventType(TimelineEventType.CAPTION)
                    .category(RiskCategory.CAPTION_MISMATCH)
                    .source(EvidenceSource.VISION)
                    .score(score)
                    .startMs(Math.min(pair.speech().getStartMs(), pair.caption().getStartMs()))
                    .endMs(Math.max(pair.speech().getEndMs(), pair.caption().getEndMs()))
                    .speechText(pair.speech().getText())
                    .captionText(pair.caption().getText())
                    .frame(pair.caption().getFrame())
                    .reason("[%s] %s".formatted(
                            item.type() == null ? "MISMATCH" : item.type(),
                            item.reason() == null ? "발언과 자막의 의미가 다릅니다." : item.reason()))
                    .build());
        }
        return findings;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Punct}]", "");
    }

    /** 글자 일치율 계산. 조사·기호를 빼고 겹치는 글자 수를 센다. */
    private double similarity(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0;

        Map<Character, Integer> counts = new HashMap<>();
        for (char c : x.toCharArray()) {
            counts.merge(c, 1, Integer::sum);
        }
        int common = 0;
        for (char c : y.toCharArray()) {
            Integer left = counts.get(c);
            if (left != null && left > 0) {
                counts.put(c, left - 1);
                common++;
            }
        }
        return (double) common / Math.min(x.length(), y.length());
    }

    private record Pair(TranscriptSegment speech, ScreenText caption, double similarity) {}

    record LlmResult(List<LlmFinding> findings) {}

    record LlmFinding(Integer index, String type, Double score, String reason) {}
}
