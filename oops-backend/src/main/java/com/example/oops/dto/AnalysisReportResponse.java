package com.example.oops.dto;

import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.ReviewStatus;
import com.example.oops.domain.SourceType;

import java.util.List;

/** 검수 리포트. 명세 §5 */
public record AnalysisReportResponse(
        String videoId,
        String jobId,
        String filename,

        /** 리포트 생성 시각. ISO-8601 UTC */
        String generatedAt,

        Long durationMs,

        /**
         * 어떻게 등록한 영상인지. **재생 방법이 이 값으로 갈린다.**
         *   UPLOAD  → streamUrl 을 video 태그에
         *   YOUTUBE → embedUrl 을 iframe 에
         */
        SourceType sourceType,

        /** 업로드한 영상만 값이 있다. 유튜브면 null */
        String streamUrl,

        /**
         * 유튜브 등 외부 링크로 등록한 영상의 원본 URL.
         * 사용자에게 "원본 보기" 링크로 보여줄 때 쓴다.
         *
         * **이 값을 video 태그에 넣으면 검은 화면이 된다.**
         * 유튜브 주소는 영상 파일이 아니라 HTML 페이지다.
         * 재생에는 아래 embedUrl 을 써야 한다.
         */
        String sourceUrl,

        /**
         * iframe 삽입용 주소. 유튜브 영상만 값이 있다.
         *
         * 유튜브로 등록한 영상은 서버에 파일이 없다.
         * 분석할 때 임시로 받아 쓰고 끝나면 지우기 때문에 streamUrl 이 null 이다.
         * 재생하려면 유튜브 삽입 플레이어를 써야 한다.
         *
         * enablejsapi=1 이 붙어 있어 카드를 눌렀을 때 그 시각으로 이동할 수 있다.
         * origin 파라미터는 프론트가 자기 도메인으로 덧붙이면 된다.
         */
        String embedUrl,

        /** 분석 상태와 별개인 사용자의 검수 진행 상태 */
        ReviewStatus reviewStatus,

        AnalysisStatus status,
        RiskSummary summary,
        ReviewSummaryDto reviewSummary,
        CoverageDto coverage,

        /** 수행하지 못한 단계. 없으면 빈 배열 */
        List<AnalysisWarningDto> warnings,

        List<TimelineEventDto> events,

        // ---- 아래는 내부 확장. 프론트 계약은 아니다 ----
        ContentGenre genre
) {}
