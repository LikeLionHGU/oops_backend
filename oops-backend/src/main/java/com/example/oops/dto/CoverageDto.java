package com.example.oops.dto;

/**
 * 무엇을 분석했는지. 명세 §5.
 *
 * 후보 0건과 "그 단계를 못 돌았다" 를 구분하기 위한 필드입니다.
 * 어느 단계가 왜 안 됐는지는 warnings 에 담깁니다.
 */
public record CoverageDto(boolean speechAnalyzed,
                          boolean screenTextAnalyzed,
                          boolean sceneAnalyzed) {}
