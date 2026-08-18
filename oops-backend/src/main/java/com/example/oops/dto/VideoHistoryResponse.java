package com.example.oops.dto;

import java.util.List;

/** 검수 이력 페이지. 명세 §4 */
public record VideoHistoryResponse(
        List<VideoSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
