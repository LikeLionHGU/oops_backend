package com.example.oops.dto;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;

import java.time.ZoneOffset;

/**
 * 검수 이력 1건. 명세 §3-2 · §15-2 VideoHistoryItem.
 *
 * 앞의 7개가 프론트와 고정한 계약이고, 뒤의 4개는 관리·디버깅용 확장 필드다.
 * 프론트는 모르는 필드를 무시한다.
 */
public record VideoSummaryResponse(
        Long videoId,
        String filename,

        /** 업로드 시각. ISO-8601 UTC 문자열 (예: 2026-08-16T12:30:00Z) */
        String uploadedAt,

        AnalysisStatus status,

        /** 0~100. 현재 Job 의 진행률 */
        int progress,

        /** 완료된 리포트의 events.length 와 같은 기준 */
        int eventCount,

        /** 원본이 서버에 없으면 null (유튜브 링크 등록) */
        String streamUrl,

        // ---- 아래는 확장 필드. 프론트 계약은 아니다 ----
        SourceType sourceType,
        String sourceUrl,
        String title,
        ContentGenre genre
) {
    public static VideoSummaryResponse of(Video video, AnalysisJob job, int eventCount) {
        return new VideoSummaryResponse(
                video.getId(),
                displayName(video),
                toUtcIso(video),
                job == null ? video.getStatus() : job.getStatus(),
                job == null ? 0 : job.getProgress(),
                eventCount,
                video.isStreamable() ? "/api/v1/videos/%d/stream".formatted(video.getId()) : null,
                video.getSourceType(),
                video.getSourceUrl(),
                video.getTitle(),
                video.getGenre()
        );
    }

    /**
     * 유튜브로 등록한 영상은 filename 이 없다.
     * 목록에서 빈칸으로 보이면 어떤 영상인지 알 수 없어서 제목이나 주소로 대신 채운다.
     */
    private static String displayName(Video video) {
        if (video.getFilename() != null && !video.getFilename().isBlank()) {
            return video.getFilename();
        }
        if (video.getTitle() != null && !video.getTitle().isBlank()) {
            return video.getTitle();
        }
        return video.getSourceUrl();
    }

    /** 서버 로컬 시간대에 의존하면 프론트에서 시간이 어긋난다. UTC 로 고정해 보낸다. */
    private static String toUtcIso(Video video) {
        if (video.getCreatedAt() == null) {
            return null;
        }
        return video.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
    }
}
