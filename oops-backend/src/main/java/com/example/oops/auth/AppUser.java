package com.example.oops.auth;

import com.example.oops.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 서비스 회원. 로그인 수단(구글 등)은 {@link UserIdentity} 로 따로 연결한다.
 * 이메일은 표시·연락용일 뿐 계정 식별자가 아니다(구글은 sub 로 식별).
 * 전화번호·생년월일·주소는 받지 않는다.
 */
@Getter
@Entity
@Table(name = "app_user")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser extends BaseTimeEntity {

    public enum Status { ACTIVE, SUSPENDED, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(length = 320)
    private String email;

    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private Status status;

    private LocalDateTime lastLoginAt;

    public AppUser(String displayName, String email, boolean emailVerified) {
        this.displayName = clip(displayName == null || displayName.isBlank() ? "사용자" : displayName, 100);
        this.email = email == null ? null : clip(email, 320);
        this.emailVerified = emailVerified;
        this.status = Status.ACTIVE;
    }

    /** 로그인할 때마다 구글 프로필의 이름·이메일을 새 값으로 맞춘다. */
    public void loggedIn(String displayName, String email, boolean emailVerified, LocalDateTime at) {
        if (displayName != null && !displayName.isBlank()) this.displayName = clip(displayName, 100);
        if (email != null) this.email = clip(email, 320);
        this.emailVerified = emailVerified;
        this.lastLoginAt = at;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
