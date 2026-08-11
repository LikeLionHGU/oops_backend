package com.example.oops.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 유튜브 링크로 등록할 때 쓴다.
 * 명세에는 없는 확장 엔드포인트지만, 나중에 댓글 분석을 붙이려면 원본 URL 이 필요하다.
 */
public record VideoRegisterRequest(
        @NotBlank(message = "영상 URL은 필수입니다.")
        String url,
        String title,
        String channelName
) {}
