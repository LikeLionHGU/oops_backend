package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.domain.*;

import java.util.List;

/**
 * 검토 후보 1건. 명세 §5 · v2.1 §10.
 *
 * type 을 discriminator 로 하는 유니온입니다.
 * 해당 타입에 없는 필드는 JSON 에서 아예 빠집니다
 * (application.yml 의 default-property-inclusion: non_null).
 *
 *   type          어디서 나왔나  SPEECH = 발언 / CAPTION = 화면 글자
 *   candidateType 왜 확인하나   SPEECH_REVIEW / FACT_CHECK
 */
public record TimelineEventDto(
        String id,
        long startMs,
        long endMs,
        TimelineEventType type,
        CandidateType candidateType,

        /** 카드 제목. 한 줄로 무엇을 확인하는지 */
        String title,

        /** 왜 후보로 올렸는지 */
        String reason,

        String frameUrl,

        /** 항상 배열. 자료가 없으면 빈 배열을 준다 */
        List<ReviewReferenceDto> references,

        /** 아직 결정하지 않았으면 null */
        ReviewActionType reviewAction,

        /** 내부 값. v2.1 §10-6 에서 optional 로 내려갔다 */
        Severity severity,

        /**
         * 같은 후보가 영상에서 몇 번 등장했는지. 1 이면 한 번.
         * 2 이상이면 startMs~endMs 가 그 전체 구간을 뜻한다.
         */
        int occurrences,

        // ---- SPEECH 전용 ----
        String text,
        /** 직전 대본 줄. 없으면 null */
        String contextBefore,
        /** 직후 대본 줄. 없으면 null */
        String contextAfter,
        List<String> riskTypes,

        // ---- CAPTION 전용 ----
        String speechText,
        String captionText
) {
    public static TimelineEventDto from(RiskFinding f, ReviewActionType action,
                                        String before, String after) {
        boolean caption = f.getEventType() == TimelineEventType.CAPTION;

        return new TimelineEventDto(
                Ids.of(f.getId()),
                f.getStartMs(),
                f.getEndMs(),
                f.getEventType(),
                CandidateType.from(f.getCategory()),
                buildTitle(f),
                buildReason(f),
                frameUrl(f),
                references(f),
                action,
                f.getSeverity(),
                f.getMergedCount(),
                caption ? null : f.getText(),
                caption ? null : before,
                caption ? null : after,
                caption ? null : List.of(f.getCategory().name()),
                caption ? f.getSpeechText() : null,
                caption ? f.getCaptionText() : null
        );
    }

    /**
     * 카드 제목.
     *
     * 카테고리 이름만 쓰면 카드마다 똑같아서 목록에서 구분이 안 된다.
     * 무엇에 대한 지적인지(target)를 붙여야 훑어볼 수 있다.
     */
    private static String buildTitle(RiskFinding f) {
        String label = f.getCategory().getLabel();
        String target = f.getTarget();
        if (target == null || target.isBlank()) {
            return label;
        }
        return "'%s' — %s".formatted(target, label);
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

    /** 명세 §5 — references 는 항상 배열이다. 없으면 빈 배열. */
    private static List<ReviewReferenceDto> references(RiskFinding f) {
        if (f.getReferences() == null || f.getReferences().isEmpty()) {
            return List.of();
        }
        return f.getReferences().stream().map(ReviewReferenceDto::from).toList();
    }

    private static String frameUrl(RiskFinding f) {
        if (f.getFrame() == null) {
            return null;
        }
        return "/api/v1/videos/%d/frames/%d".formatted(
                f.getVideo().getId(), f.getFrame().getId());
    }
}
