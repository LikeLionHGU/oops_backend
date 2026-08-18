package com.example.oops.dto;

/**
 * 검수 진행 집계. 명세 §5.
 *
 * remaining 이 0 이어야 검수 완료를 요청할 수 있습니다.
 */
public record ReviewSummaryDto(int decided, int remaining,
                               int confirmed, int edited, int hold, int notUseful) {}
