package com.example.videoguard.dto;

import com.example.videoguard.domain.RiskFinding;
import com.example.videoguard.domain.Severity;
import com.example.videoguard.domain.TimelineEventType;

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
                f.getReason(),
                frameUrl(f),
                caption ? null : f.getText(),
                caption ? null : List.of(f.getCategory().name()),
                caption ? f.getSpeechText() : null,
                caption ? f.getCaptionText() : null
        );
    }

    private static String frameUrl(RiskFinding f) {
        if (f.getFrame() == null) {
            return null;
        }
        return "/api/v1/videos/%d/frames/%d".formatted(
                f.getVideo().getId(), f.getFrame().getId());
    }
}
