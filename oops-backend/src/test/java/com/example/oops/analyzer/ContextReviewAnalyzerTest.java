package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OopsProperties;
import com.example.oops.domain.*;
import com.example.oops.lexicon.ContextLexicon;
import com.example.oops.review.ReviewPrompts;
import com.example.oops.review.TaxonomyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 맥락 검토(2단계)의 코드 쪽 규칙을 잠근다.
 * AI 응답은 가짜로 넣고, 코드가 그 응답을 어떻게 카드로 바꾸는지만 본다.
 */
class ContextReviewAnalyzerTest {

    private OpenAiClient openAi;
    private ContextReviewAnalyzer analyzer;
    private final Video video = Video.builder().sourceType(SourceType.UPLOAD).build();

    @BeforeEach
    void setUp() throws Exception {
        openAi = mock(OpenAiClient.class);
        when(openAi.isEnabled()).thenReturn(true);

        ContextLexicon lexicon = new ContextLexicon();
        Method load = ContextLexicon.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(lexicon);

        OopsProperties props = new OopsProperties(null,
                new OopsProperties.Analysis(List.of("context-review"), true, "srt",
                        new OopsProperties.ContextReview(30, 3, 6, 8)));
        analyzer = new ContextReviewAnalyzer(openAi, lexicon, props);
    }

    private AnalysisContext context(List<String> speech, List<ScreenText> captions) {
        List<TranscriptSegment> t = new java.util.ArrayList<>();
        for (int i = 0; i < speech.size(); i++) {
            t.add(new TranscriptSegment(video, i * 3000L, i * 3000L + 2500, speech.get(i)));
        }
        return new AnalysisContext(video, ContentGenre.TALK_PODCAST, t, captions);
    }

    private void screenReturns(ContextReviewAnalyzer.ScreenItem... items) {
        when(openAi.completeAsJson(eq(ReviewPrompts.SCREEN_PROMPT), anyString(), eq(ContextReviewAnalyzer.ScreenResult.class)))
                .thenReturn(Optional.of(new ContextReviewAnalyzer.ScreenResult(List.of(items))));
    }

    private void verifyReturns(TaxonomyType type, ContextReviewAnalyzer.VerifyItem... items) {
        when(openAi.completeAsJson(eq(ReviewPrompts.verifyPrompt(type)), anyString(), eq(ContextReviewAnalyzer.VerifyResult.class)))
                .thenReturn(Optional.of(new ContextReviewAnalyzer.VerifyResult(List.of(items))));
    }

    private static ContextReviewAnalyzer.VerifyItem flag(String subtype, String target, String reason, double score,
                                                         List<String> also, boolean teasing, boolean minor) {
        return new ContextReviewAnalyzer.VerifyItem(0, "FLAG", subtype, target, reason, score, also, teasing, minor);
    }

    @Test
    @DisplayName("여러 유형에 걸리면 카드는 한 장, 주 유형은 우선순위가 높은 쪽이다")
    void precedencePicksPrimary() {
        screenReturns(new ContextReviewAnalyzer.ScreenItem(1, List.of("INSULT", "PREJUDICE"), "성별 비하"));
        verifyReturns(TaxonomyType.INSULT, flag("모욕", "여자", "특정 성별을 낮춰 부르는 욕설이 담긴 대목입니다.", 0.6, List.of(), false, false));
        verifyReturns(TaxonomyType.PREJUDICE, flag("폄하·차별", "여자", "성별을 능력 판단의 근거로 삼은 표현입니다.", 0.8, List.of(), false, false));

        List<RiskFinding> out = analyzer.analyze(context(List.of("안녕하세요", "여자가 무슨 운전을 해 이 멍청아", "그렇죠"), List.of()));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.getCategory()).isEqualTo(RiskCategory.PREJUDICE);
            assertThat(f.getReason()).startsWith("[폄하·차별]").contains("함께 해당: 욕설·모욕·폄하·조롱");
            assertThat(f.getEventType()).isEqualTo(TimelineEventType.SPEECH);
            assertThat(f.getStartMs()).isEqualTo(3000);
        });
    }

    @Test
    @DisplayName("출연자끼리의 친근한 놀림은 잡되 점수를 낮춘다")
    void teasingIsCapped() {
        screenReturns(new ContextReviewAnalyzer.ScreenItem(0, List.of("INSULT"), "놀림"));
        verifyReturns(TaxonomyType.INSULT, flag("조롱", "출연자 실수", "실수 직후 과장된 칭찬으로 비꼬는 대목입니다.", 0.7, List.of(), true, false));

        RiskFinding f = analyzer.analyze(context(List.of("역시 천재야 ㅋㅋ"), List.of())).get(0);
        assertThat(f.getScore()).isLessThanOrEqualTo(ContextReviewAnalyzer.TEASING_SCORE_CAP);
        assertThat(f.getReason()).contains("장난");
    }

    @Test
    @DisplayName("감탄형 욕설은 대상이 없어도 남기되 점수를 낮춘다")
    void exclamationProfanity() {
        screenReturns(new ContextReviewAnalyzer.ScreenItem(0, List.of("INSULT"), "욕설"));
        verifyReturns(TaxonomyType.INSULT, flag("욕설(감탄형)", "", "감탄처럼 내뱉은 욕설이 들어 있습니다.", 0.6, List.of(), false, false));

        RiskFinding f = analyzer.analyze(context(List.of("씨발 이거지 존나 맛있다"), List.of())).get(0);
        assertThat(f.getCategory()).isEqualTo(RiskCategory.INSULT);
        assertThat(f.getScore()).isLessThanOrEqualTo(ContextReviewAnalyzer.EXCLAMATION_SCORE_CAP);
    }

    @Test
    @DisplayName("대상을 '특정 인물' 같은 분류명으로만 적으면 버린다")
    void placeholderTargetIsDropped() {
        screenReturns(new ContextReviewAnalyzer.ScreenItem(0, List.of("PREJUDICE"), "일반화"));
        verifyReturns(TaxonomyType.PREJUDICE, flag("고정관념", "특정 인물", "특정 인물을 집단으로 묶어 판단하는 표현입니다.", 0.7, List.of(), false, false));

        assertThat(analyzer.analyze(context(List.of("걔네는 원래 그래"), List.of()))).isEmpty();
    }

    @Test
    @DisplayName("PASS 와 알맹이 없는 이유는 카드가 되지 않는다")
    void passAndVagueAreDropped() {
        screenReturns(new ContextReviewAnalyzer.ScreenItem(0, List.of("INSULT", "PREJUDICE"), "?"));
        verifyReturns(TaxonomyType.INSULT, new ContextReviewAnalyzer.VerifyItem(0, "PASS", null, null, null, null, null, null, null));
        verifyReturns(TaxonomyType.PREJUDICE, flag("고정관념", "20대", "문제가 될 수 있는 표현", 0.7, List.of(), false, false));

        assertThat(analyzer.analyze(context(List.of("요즘 20대는"), List.of()))).isEmpty();
    }

    @Test
    @DisplayName("미성년자 관련 성적 의미 부여는 장난으로 표시돼도 최우선 점수다")
    void minorAlwaysTop() {
        screenReturns(new ContextReviewAnalyzer.ScreenItem(0, List.of("SEXUAL_HARASSMENT"), "신체 평가"));
        verifyReturns(TaxonomyType.SEXUAL_HARASSMENT, flag("신체의 성적 평가", "학생 출연자", "미성년 출연자의 신체를 성적으로 평가하는 대목입니다.", 0.5, List.of(), true, true));

        RiskFinding f = analyzer.analyze(context(List.of("..."), List.of())).get(0);
        assertThat(f.getScore()).isGreaterThanOrEqualTo(ContextReviewAnalyzer.MINOR_SCORE);
        assertThat(f.getReason()).contains("미성년자");
    }

    @Test
    @DisplayName("사전에 걸린 줄은 1차 선별이 놓쳐도 사전의 유형으로 2차 검증을 받는다")
    void lexiconRoutesToType() {
        screenReturns();   // 1차 선별은 아무것도 못 찾음
        verifyReturns(TaxonomyType.VICTIM_BLAMING,
                flag("피해자 조롱", "세월호 희생자", "참사 희생자를 조롱하는 은어를 쓴 대목입니다.", 0.6, List.of(), false, false));

        List<RiskFinding> out = analyzer.analyze(context(List.of("너네 세월호 얘기 그만하고 어묵이나 먹어라"), List.of()));

        assertThat(out).singleElement().extracting(RiskFinding::getCategory).isEqualTo(RiskCategory.VICTIM_BLAMING);
        verify(openAi).completeAsJson(eq(ReviewPrompts.verifyPrompt(TaxonomyType.VICTIM_BLAMING)),
                contains("사전 정보: '어묵'"), eq(ContextReviewAnalyzer.VerifyResult.class));
    }

    @Test
    @DisplayName("받아 적은 자막은 검토 줄에서 빠지고, 편집자가 넣은 자막은 자막으로 들어간다")
    void captionsFilteredAndLabelled() {
        ScreenText mirror = new ScreenText(video, 0, 2500, "오늘 날씨 좋네요", 1.0, null);
        ScreenText editorial = new ScreenText(video, 0, 2500, "(극대노 주의)", 1.0, null);
        AnalysisContext ctx = context(List.of("오늘 날씨 좋네요"), List.of(mirror, editorial));

        List<ContextReviewAnalyzer.Line> lines = analyzer.buildLines(ctx);
        assertThat(lines).extracting(ContextReviewAnalyzer.Line::text).containsExactly("오늘 날씨 좋네요", "(극대노 주의)");
        assertThat(lines.get(1).caption()).isTrue();

        screenReturns(new ContextReviewAnalyzer.ScreenItem(1, List.of("INSULT"), "자막 비하"));
        verifyReturns(TaxonomyType.INSULT, flag("조롱", "진행자 민수", "발언에 없던 조롱이 편집 자막에서 생긴 대목입니다.", 0.6, List.of(), false, false));
        RiskFinding f = analyzer.analyze(ctx).get(0);
        assertThat(f.getEventType()).isEqualTo(TimelineEventType.CAPTION);
        assertThat(f.getCaptionText()).isEqualTo("(극대노 주의)");
        assertThat(f.getSpeechText()).isEqualTo("오늘 날씨 좋네요");
    }

    @Test
    @DisplayName("1차 선별 프롬프트에는 8유형 키가 모두 있고, 2차 프롬프트는 유형마다 있다")
    void promptsCoverAllTypes() {
        for (TaxonomyType t : TaxonomyType.values()) {
            assertThat(ReviewPrompts.SCREEN_PROMPT).contains(t.name() + ":");
            String p = ReviewPrompts.verifyPrompt(t);
            assertThat(p).contains("[유형] " + t.name()).contains("공통 원칙").contains("\"results\"");
        }
        // 1차 선별은 매 창마다 나가므로 짧게 유지한다 (예전 발언 검토 프롬프트는 7,390자였다)
        assertThat(ReviewPrompts.SCREEN_PROMPT.length()).isLessThan(2000);
    }
}
