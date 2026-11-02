package com.example.oops.auth;

import java.io.Serializable;

/** 세션에 넣는 로그인 사용자. 권한 판단은 매 요청 DB 의 계정 상태로 다시 한다. */
public record AuthenticatedUser(Long userId, String displayName, String email, boolean local) implements Serializable {
}
