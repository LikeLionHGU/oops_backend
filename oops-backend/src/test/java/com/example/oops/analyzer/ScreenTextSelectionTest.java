package com.example.oops.analyzer;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ScreenTextSelectionTest {
    @Test
    void unknownBackgroundKeepPrivacyChecksButCannotTriggerSlangAndExpressionRules() {
        var analyzer = new ScreenTextAnalyzer(new RiskRuleEngine());
        var s = new ScreenText(null, 0, 1_000, "선거 메뉴 010-1234-5678", 0.9, null);
        var video = Video.builder().filename("test.mp4").build();
        var context = new AnalysisContext(video, null, null, List.of(s));
        var findings = analyzer.analyze(context);
        assertThat(findings).isNotEmpty().allSatisfy(f -> assertThat(f.getCategory()).isEqualTo(RiskCategory.PRIVACY));
        assertThat(analyzer.consumeCoverageNotice(context)).hasValueSatisfying(s2 -> assertThat(s2).contains("개인정보 룰만"));
    }

    @Test
    void onlyEditorialTextIsPrimaryWhileBackgroundRemainsLabeledContext() {
        var caption = new ScreenText(null, 0, 1_000, "발언 자막", 0.9, null);
        caption.classify(ScreenTextRole.EDITORIAL, "테스트용");
        var sign = new ScreenText(null, 0, 1_000, "식당 간판", 0.9, null);
        sign.classify(ScreenTextRole.BACKGROUND, "테스트용");
        var input = ReviewInput.from(null, List.of(caption, sign));
        var batch = TextReviewBatchPlanner.plan(input, TimelineEventType.CAPTION, 2).get(0);
        assertThat(batch.primary()).extracting(ReviewInput.Segment::text).containsExactly("발언 자막");
        assertThat(batch.context()).extracting(ReviewInput.Segment::role).containsExactly(ScreenTextRole.BACKGROUND);
    }
}
