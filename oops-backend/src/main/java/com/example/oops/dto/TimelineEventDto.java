package com.example.oops.dto;

import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.Severity;
import com.example.oops.domain.TimelineEventType;

import java.util.List;

/**
 * API 명세 6. type 을 discriminator 로 하는 유니온 타입.
 *
 * 자바에서 타입별 클래스를 나누면 컨트롤러 시그니처가 복잡해지므로 한 레코드로 두고,
 * 해당 타입에 없는 필드는 null 로 둔다.
 * application.yml 의 default-property-inclusion: non_null 덕분에
 * 실제 JSON 에는 SPEECH 면 text/riskTypes 만, CAPTION 이면 speechText/captionText 만 나간다.
 */
public record TimelineEventDto(
        Long id,
        long startMs,
        long endMs,
        TimelineEventType type,
        Severity severity,
        String reason,
        String frameUrl,

        /**
         * 같은 논란이 영상에서 몇 번 등장했는지. 1 이면 한 번만 나온 것.
         * 2 이상이면 startMs~endMs 가 그 전체 구간을 뜻한다.
         * (명세에는 없는 추가 필드다. 안 써도 무방하다)
         */
        int occurrences,

        // SPEECH 전용
        String text,
        List<String> riskTypes,

        // CAPTION 전용
        String speechText,
        String captionText
) {
    public static TimelineEventDto from(RiskFinding f) {
        boolean caption = f.getEventType() == TimelineEventType.CAPTION;

        return new TimelineEventDto(
                f.getId(),
                f.getStartMs(),
                f.getEndMs(),
                f.getEventType(),
                f.getSeverity(),
                buildReason(f),
                frameUrl(f),
                f.getMergedCount(),
                caption ? null : f.getText(),
                caption ? null : List.of(f.getCategory().name()),
                caption ? f.getSpeechText() : null,
                caption ? f.getCaptionText() : null
        );
    }

    /**
     * 여러 번 등장한 건은 어디어디에 나왔는지 함께 보여준다.
     * 구간만 주면 "00:26 ~ 00:59 사이 어딘가" 로 뭉뚱그려져 찾기 어렵다.
     */
    private static String buildReason(RiskFinding f) {
        String reason = f.getReason() == null ? "" : f.getReason();
        if (f.getOccurrenceTimes() != null && !f.getOccurrenceTimes().isBlank()) {
            reason = reason + " (등장: " + f.getOccurrenceTimes() + ")";
        }
        return reason;
    }

    private static String frameUrl(RiskFinding f) {
        if (f.getFrame() == null) {
            return null;
        }
        return "/api/v1/videos/%d/frames/%d".formatted(
                f.getVideo().getId(), f.getFrame().getId());
    }
}
