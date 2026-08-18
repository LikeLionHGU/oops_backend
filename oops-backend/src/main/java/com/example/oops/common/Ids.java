package com.example.oops.common;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 응답에 나가는 식별자와 시각의 형식을 한 곳에서 정한다.
 *
 * 명세 §1:
 *   - 식별자는 문자열로 전달
 *   - 날짜·시간은 ISO 8601 UTC 문자열
 *
 * DB 는 Long 그대로 둔다. 바깥 표현만 문자열이다.
 * 엔티티 키까지 문자열로 바꾸면 인덱스와 조인이 전부 느려지는데,
 * 프론트가 원하는 건 "숫자로 파싱하지 않아도 되는 값" 이지 키 타입이 아니다.
 */
public final class Ids {

    private Ids() {}

    public static String of(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    /** 경로로 들어온 문자열 id 를 Long 으로. 숫자가 아니면 400 */
    public static Long parse(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "식별자가 비어 있습니다.");
        }
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "식별자 형식이 올바르지 않습니다: " + id);
        }
    }

    /** 서버 로컬 시간대에 의존하지 않도록 UTC 로 고정해 내보낸다 */
    public static String utc(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
    }
}
