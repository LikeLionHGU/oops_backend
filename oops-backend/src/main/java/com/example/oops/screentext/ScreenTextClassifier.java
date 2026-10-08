package com.example.oops.screentext;

import com.example.oops.domain.*;
import java.util.List;
import java.util.Locale;

/** Conservative first-pass heuristics. No position-only decision and no deletion of raw OCR. */
public final class ScreenTextClassifier {
    private ScreenTextClassifier() {}

    public static void classify(List<ScreenText> texts, List<TranscriptSegment> transcript) {
        for (ScreenText text : texts) {
            String normalized = normalize(text.getText());
            OcrRegion r = text.getRegion();
            if (r == null || !r.hasGeometry()) {
                text.classify(ScreenTextRole.UNCERTAIN, "영역 정보가 없어 편집 자막과 배경 글자를 구분하지 못했습니다.");
                continue;
            }
            if (List.of("구독", "좋아요", "알림설정").contains(normalized)
                    || text.getText().strip().toLowerCase(Locale.ROOT).startsWith("https://")
                    || text.getText().strip().toLowerCase(Locale.ROOT).startsWith("http://")) {
                text.classify(ScreenTextRole.BACKGROUND, "화면 UI·링크 안내로 추정됩니다. 개인정보 검토는 별도로 수행합니다.");
                continue;
            }
            boolean horizontal = r.getBoxWidth() >= 0.12 && r.getBoxHeight() <= 0.15
                    && r.getBoxWidth() / r.getBoxHeight() >= 2;
            boolean speechMatch = normalized.length() >= 6 && transcript.stream()
                    .filter(s -> Math.max(0, Math.max(s.getStartMs() - text.getEndMs(), text.getStartMs() - s.getEndMs())) <= 1_000)
                    .map(s -> normalize(s.getText())).anyMatch(s -> s.contains(normalized));
            if (horizontal && speechMatch) {
                text.classify(ScreenTextRole.EDITORIAL, "가로형 영역의 문구가 같은 시간대 발언과 일치합니다. 편집 텍스트 추정입니다.");
            } else if (horizontal && r.getBoxWidth() >= 0.25
                    && r.getSlotTextChanges() != null && r.getSlotTextChanges() >= 3) {
                text.classify(ScreenTextRole.EDITORIAL, "같은 화면 영역에서 여러 문구가 교체되는 패턴입니다. 편집 텍스트 추정입니다.");
            } else {
                text.classify(ScreenTextRole.UNCERTAIN, "편집 텍스트인지 간판·메뉴판 등인지 구분할 근거가 부족합니다.");
            }
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]", "");
    }
}
