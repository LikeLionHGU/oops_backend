package com.example.oops.genre;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.ScreenText;
import com.example.oops.domain.TranscriptSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 영상이 어떤 유형인지 판별한다.
 *
 * 유형에 따라 봐야 할 것이 다르다.
 * 경제 해설물은 숫자가 틀리면 그 자체로 논란이고,
 * 인터뷰는 발언이 공개 시점의 이슈와 맞물릴 때 논란이 된다.
 * 같은 잣대로 보면 둘 다 놓친다.
 *
 * 업로드할 때 유형을 지정했으면 그걸 쓰고, 없으면 여기서 대본을 보고 정한다.
 * LLM 을 못 쓰는 상황이면 키워드로 대충 가른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenreDetector {

    /** 앞부분만 봐도 유형은 대체로 드러난다. 비용을 아낀다. */
    private static final int SAMPLE_LINES = 40;

    private static final String SYSTEM_PROMPT = """
            영상의 대본과 화면 자막을 보고 어떤 유형인지 하나만 고른다.

            - ECONOMY_POLICY: 경제 지표, 정책, 통계, 사회 현상을 설명하고 해석하는 영상.
              화자가 자료를 근거로 무언가를 설명한다.
            - INVESTMENT_FINANCE: 주식, 코인, 부동산, 재테크. 종목이나 투자 판단을 다룬다.
            - INTERVIEW_PODCAST: 두 명 이상이 대화한다. 질문과 답변, 게스트의 경험담,
              특정 인물에 대한 이야기가 중심이다.
            - GENERAL: 위 어디에도 뚜렷하게 해당하지 않는다.
              브이로그, 예능, 리뷰, 먹방, 일상은 전부 여기다.

            애매하면 GENERAL 을 고른다. 억지로 분류하지 않는다.

            반드시 이 JSON 형식으로만 답한다:
            {"genre":"ECONOMY_POLICY","confidence":0.8,"reason":"판단 근거 한 문장"}
            """;

    private final OpenAiClient openAiClient;

    public ContentGenre detect(List<TranscriptSegment> transcript, List<ScreenText> screenTexts) {
        String sample = buildSample(transcript, screenTexts);
        if (sample.isBlank()) {
            return ContentGenre.GENERAL;
        }

        if (openAiClient.isEnabled()) {
            Result result = openAiClient.completeAsJson(SYSTEM_PROMPT, sample, Result.class).orElse(null);
            if (result != null && result.genre() != null) {
                ContentGenre genre = ContentGenre.fromOrDefault(result.genre(), ContentGenre.GENERAL);
                log.info("[genre] {} (확신도 {}) — {}", genre, result.confidence(), result.reason());
                return genre;
            }
        }

        ContentGenre fallback = guessByKeyword(sample);
        log.info("[genre] {} (키워드 추정)", fallback);
        return fallback;
    }

    private String buildSample(List<TranscriptSegment> transcript, List<ScreenText> screenTexts) {
        StringBuilder sb = new StringBuilder();

        if (transcript != null && !transcript.isEmpty()) {
            sb.append("[대본]\n");
            transcript.stream().limit(SAMPLE_LINES)
                    .forEach(s -> sb.append(s.getText()).append("\n"));
        }
        if (screenTexts != null && !screenTexts.isEmpty()) {
            sb.append("\n[화면 자막]\n");
            screenTexts.stream().limit(20)
                    .forEach(s -> sb.append(s.getText()).append("\n"));
        }
        return sb.toString().trim();
    }

    /** LLM 을 못 쓸 때의 대략적인 판단. 정확하지 않아도 아무것도 없는 것보단 낫다. */
    private ContentGenre guessByKeyword(String sample) {
        String text = sample.toLowerCase(Locale.ROOT);

        if (containsAny(text, "주가", "종목", "매수", "매도", "코스피", "나스닥",
                "비트코인", "수익률", "배당", "재테크", "부동산 투자")) {
            return ContentGenre.INVESTMENT_FINANCE;
        }
        if (containsAny(text, "금리", "물가", "gdp", "실업률", "정책", "법안",
                "통계", "지표", "예산", "세금", "인플레")) {
            return ContentGenre.ECONOMY_POLICY;
        }
        if (containsAny(text, "질문 드리", "말씀해 주", "게스트", "인터뷰",
                "오늘 모신", "라고 하셨는데", "어떻게 보세요")) {
            return ContentGenre.INTERVIEW_PODCAST;
        }
        return ContentGenre.GENERAL;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    record Result(String genre, Double confidence, String reason) {}
}
