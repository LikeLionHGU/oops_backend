package com.example.oops.dto;

import com.example.oops.domain.ReviewActionType;
import jakarta.validation.constraints.NotNull;

/** 명세 §9-2 요청 본문 */
public record ReviewActionRequest(

        /** 처리할 검토 후보 id. report 의 events[].id 와 같다 */
        @NotNull(message = "eventId 는 필수입니다.")
        Long eventId,

        @NotNull(message = "action 은 필수입니다.")
        ReviewActionType action,

        /** 무엇을 어떻게 고쳤는지. 선택 */
        String note
) {}
