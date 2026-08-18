package com.example.oops.dto;

import com.example.oops.domain.ReviewActionType;
import jakarta.validation.constraints.NotNull;

/**
 * 검수 결정 저장 요청. 명세 §6.
 *
 * eventId 는 경로에 있으므로 본문에는 없습니다.
 */
public record ReviewActionRequest(

        @NotNull(message = "action 은 필수입니다.")
        ReviewActionType action,

        /** 무엇을 어떻게 고쳤는지. 선택 */
        String note
) {}
