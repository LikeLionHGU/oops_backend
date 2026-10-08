package com.example.oops.screentext;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ScreenTextClassifierTest {
    private ScreenText text(String text, int changes) {
        var s = new ScreenText(null, 0, 1_000, text, 0.9, null);
        s.attachRegion(new OcrRegion(0.1, 0.8, 0.5, 0.05, "region", 1, changes));
        return s;
    }

    @Test
    void speechAlignmentAndGeometryIdentifyProvisionalEditorialText() {
        var text = text("이 음식은 제 입에는 별로예요", 0);
        ScreenTextClassifier.classify(List.of(text), List.of(new TranscriptSegment(null, 0, 1_000, "이 음식은 제 입에는 별로예요")));
        assertThat(text.roleOrUncertain()).isEqualTo(ScreenTextRole.EDITORIAL);
        assertThat(text.getRoleReason()).contains("추정");
    }

    @Test
    void changingEditorialTextDoesNotRequireSpeechMatch() {
        var text = text("충격적인 반전", 3);
        ScreenTextClassifier.classify(List.of(text), List.of());
        assertThat(text.isEditorial()).isTrue();
    }

    @Test
    void bottomPositionConfidenceAndLongPersistenceDoNotProveSubtitle() {
        var menu = text("짜장면 9000원", 0);
        var sign = text("영양 식당", 0);
        ScreenTextClassifier.classify(List.of(menu, sign), List.of());
        assertThat(menu.roleOrUncertain()).isEqualTo(ScreenTextRole.UNCERTAIN);
        assertThat(sign.roleOrUncertain()).isEqualTo(ScreenTextRole.UNCERTAIN);
        assertThat(menu.getText()).isEqualTo("짜장면 9000원");
    }

    @Test
    void missingInvalidGeometryAndLegacyRowsStayUncertain() {
        var legacy = new ScreenText(null, 0, 1_000, "같은 시간대의 발언 자막", 0.9, null);
        var invalid = text("같은 시간대의 발언 자막", 4);
        invalid.attachRegion(new OcrRegion(Double.NaN, 0.1, 0.5, 0.05, "bad", 1, 4));
        ScreenTextClassifier.classify(List.of(legacy, invalid), List.of(new TranscriptSegment(null, 0, 1_000, legacy.getText())));
        assertThat(legacy.roleOrUncertain()).isEqualTo(ScreenTextRole.UNCERTAIN);
        assertThat(invalid.roleOrUncertain()).isEqualTo(ScreenTextRole.UNCERTAIN);
    }

    @Test
    void distantSpeechCannotPromoteBackgroundTextAndUiIsNotDeleted() {
        var text = text("오늘도 구독 부탁드립니다", 0);
        var ui = text("구독", 0);
        ScreenTextClassifier.classify(List.of(text, ui), List.of(new TranscriptSegment(null, 60_000, 61_000, text.getText())));
        assertThat(text.roleOrUncertain()).isEqualTo(ScreenTextRole.UNCERTAIN);
        assertThat(ui.roleOrUncertain()).isEqualTo(ScreenTextRole.BACKGROUND);
        assertThat(ui.getText()).isEqualTo("구독");
    }
}
