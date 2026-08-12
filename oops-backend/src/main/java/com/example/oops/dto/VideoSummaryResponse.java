package com.example.oops.dto;

import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;

import java.time.LocalDateTime;

/** 목록 조회용. 관리 화면과 디버깅에 쓴다. */
public record VideoSummaryResponse(
        Long videoId,
        SourceType sourceType,
        String filename,
        String sourceUrl,
        String title,
        ContentGenre genre,
        AnalysisStatus status,
        LocalDateTime createdAt
) {
    public static VideoSummaryResponse from(Video video) {
        return new VideoSummaryResponse(
                video.getId(),
                video.getSourceType(),
                video.getFilename(),
                video.getSourceUrl(),
                video.getTitle(),
                video.getGenre(),
                video.getStatus(),
                video.getCreatedAt()
        );
    }
}
