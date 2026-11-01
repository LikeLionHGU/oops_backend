package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.common.YouTubeUrls;
import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.ReviewStatus;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;

import java.time.LocalDateTime;

/**
 * 검수 이력 1건. 명세 §4.
 *
 * 화면의 파일명 → 업로드 일자 → 전체 후보 수 → 수정 수 → 상태를
 * 이 응답만으로 그릴 수 있어야 합니다.
 *
 * **분석 상태와 검수 상태는 다른 필드입니다.**
 * 분석은 끝났어도 사람이 아직 안 봤을 수 있습니다.
 */
public record VideoSummaryResponse(
        String videoId,
        String filename,

        /** ISO-8601 UTC */
        String uploadedAt,

        AnalysisStatus analysisStatus,
        ReviewStatus reviewStatus,

        /** 전체 검토 후보 수 */
        int eventCount,

        /** 그중 '수정함' 으로 결정한 수 */
        int editedCount,

        /** 검수를 마친 시각. 아직이면 null */
        String reviewedAt,

        /** 업로드·유튜브 구분. 재생 방법이 이 값으로 갈린다 */
        SourceType sourceType,

        /** 원본이 서버에 없으면 null */
        String streamUrl,

        /** 유튜브 영상의 iframe 삽입 주소. 업로드 영상이면 null */
        String embedUrl
) {
    public static VideoSummaryResponse of(Video video, AnalysisJob job,
                                          int eventCount, int editedCount,
                                          ReviewStatus reviewStatus, LocalDateTime reviewedAt) {
        return new VideoSummaryResponse(
                Ids.of(video.getId()),
                displayName(video),
                Ids.utc(video.getCreatedAt()),
                job == null ? video.getStatus() : job.getStatus(),
                reviewStatus,
                eventCount,
                editedCount,
                Ids.utc(reviewedAt),
                video.getSourceType(),
                video.isStreamable()
                        ? "/api/v1/videos/%d/stream".formatted(video.getId()) : null,
                YouTubeUrls.embedUrl(video.getSourceUrl())
        );
    }

    /**
     * 유튜브로 등록한 영상은 filename 이 없다.
     * 목록에서 빈칸이면 어떤 영상인지 알 수 없어서 제목이나 주소로 채운다.
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
}
