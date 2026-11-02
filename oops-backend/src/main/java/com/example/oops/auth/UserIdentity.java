package com.example.oops.auth;

import com.example.oops.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 수단 연결. (provider, issuer, subject) 가 한 계정을 가리킨다.
 * 이메일이 같다고 다른 로그인 수단 계정을 자동으로 합치지 않는다.
 */
@Getter
@Entity
@Table(name = "user_identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_identity_subject", columnNames = {"provider", "issuer", "subject"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserIdentity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(nullable = false, length = 200)
    private String issuer;

    @Column(nullable = false, length = 255)
    private String subject;

    public UserIdentity(AppUser user, String provider, String issuer, String subject) {
        this.user = user;
        this.provider = provider;
        this.issuer = issuer;
        this.subject = subject;
    }
}
