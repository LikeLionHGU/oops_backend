package com.example.oops.analyzer;

import com.example.oops.domain.*;
import com.example.oops.lexicon.ContextLexicon;
import com.example.oops.review.TaxonomyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-10 고도화에서 바꾼 룰 안전망·사전·발언자막 비교의 경계를 잠근다. */
class UpgradeRulesTest {

    private final RiskRuleEngine rules = new RiskRuleEngine();

    @Test
    @DisplayName("룰 안전망은 8유형으로만 낸다: 욕설은 INSULT, 개인정보는 PRIVACY")
    void ruleEngineUsesNewTypes() {
        assertThat(rules.detect("아 씨발 진짜")).extracting(RiskRuleEngine.Hit::category)
                .containsExactly(RiskCategory.INSULT);
        assertThat(rules.detect("제 번호는 010-1234-5678 이에요")).extracting(RiskRuleEngine.Hit::category)
                .containsExactly(RiskCategory.PRIVACY);
    }

    @Test
    @DisplayName("소리만 같은 단어와 주제어는 룰에 걸리지 않는다")
    void ruleEngineIgnoresLookalikesAndTopics() {
        assertThat(rules.detect("여기가 시발점이야")).isEmpty();
        assertThat(rules.detect("군대 다녀왔어요. 코로나 때 선거도 했죠")).isEmpty();
        assertThat(rules.detect("한남동 맛집")).isEmpty();
    }

    @Test
    @DisplayName("사전 항목은 모두 보낼 유형이 정해져 있고, 멸칭은 대상 유형으로 간다")
    void lexiconEntriesAreRouted() throws Exception {
        ContextLexicon lexicon = new ContextLexicon();
        Method load = ContextLexicon.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(lexicon);

        assertThat(lexicon.size()).isGreaterThanOrEqualTo(99);
        assertThat(lexicon.match("틀딱들은 원래 그래")).extracting(m -> m.entry().taxonomyType())
                .containsExactly(TaxonomyType.PREJUDICE);
        assertThat(lexicon.match("그 정도면 이기야")).extracting(m -> m.entry().taxonomyType())
                .contains(TaxonomyType.SOCIOPOLITICAL_CONTEXT);
        // 일상 용법은 사전 단계에서 걸러진다
        assertThat(lexicon.match("어묵 국물 진짜 맛있다")).isEmpty();
        assertThat(lexicon.match("그거라도 먹자")).isEmpty();
        assertThat(lexicon.match("주먹밥 사 왔어")).isEmpty();
    }

    @Test
    @DisplayName("발언·자막 비교: 한 발언에 붙은 자막을 모아 비교하고, 양방향으로 같으면 건너뛴다")
    void captionMismatchPairs() {
        Video video = Video.builder().sourceType(SourceType.UPLOAD).build();
        List<TranscriptSegment> t = List.of(
                new TranscriptSegment(video, 0, 3000, "이 집 진짜 별로예요 다시는 안 와요"),
                new TranscriptSegment(video, 4000, 6000, "오늘 날씨가 정말 좋네요"));
        ScreenText same = new ScreenText(video, 0, 1500, "이 집 진짜 별로예요", 1.0, null);
        ScreenText softened = new ScreenText(video, 1500, 3000, "솔직 담백한 맛 평가 중", 1.0, null);
        ScreenText verbatim = new ScreenText(video, 4000, 6000, "오늘 날씨가 정말 좋네요", 1.0, null);
        AnalysisContext ctx = new AnalysisContext(video, ContentGenre.GENERAL, t, List.of(same, softened, verbatim));

        List<CaptionMismatchAnalyzer.Pair> pairs = new CaptionMismatchAnalyzer(null).matchByTime(ctx);
        assertThat(pairs).singleElement().satisfies(p -> {
            assertThat(p.captionText()).isEqualTo("이 집 진짜 별로예요 솔직 담백한 맛 평가 중");
            assertThat(p.startMs()).isEqualTo(0);
            assertThat(p.endMs()).isEqualTo(3000);
        });
    }

    @Test
    @DisplayName("발언·자막 비교: 글자를 빼기만 한 자막(부정어·욕설 삭제)도 비교 대상이다")
    void captionMismatchCatchesDeletion() {
        Video video = Video.builder().sourceType(SourceType.UPLOAD).build();
        List<TranscriptSegment> t = List.of(
                new TranscriptSegment(video, 0, 2000, "그건 사실이 아니에요"),
                new TranscriptSegment(video, 3000, 5000, "아 씨발 이거 진짜 맛있다"));
        ScreenText negationDropped = new ScreenText(video, 0, 2000, "그건 사실이에요", 1.0, null);
        ScreenText swearDropped = new ScreenText(video, 3000, 5000, "이거 진짜 맛있다", 1.0, null);
        AnalysisContext ctx = new AnalysisContext(video, ContentGenre.GENERAL, t, List.of(negationDropped, swearDropped));

        assertThat(new CaptionMismatchAnalyzer(null).matchByTime(ctx))
                .extracting(CaptionMismatchAnalyzer.Pair::captionText)
                .containsExactly("그건 사실이에요", "이거 진짜 맛있다");
    }

    @Test
    @DisplayName("날짜·긴 숫자는 개인정보로 잡지 않는다")
    void privacyRulesIgnoreDates() {
        assertThat(rules.detect("2024-10-10 에 촬영했어요")).isEmpty();
        assertThat(rules.detect("조회수 1234567890123 돌파")).isEmpty();
        assertThat(rules.detect("계좌는 110-123-456789 예요")).extracting(RiskRuleEngine.Hit::category)
                .containsExactly(RiskCategory.PRIVACY);
    }
}
