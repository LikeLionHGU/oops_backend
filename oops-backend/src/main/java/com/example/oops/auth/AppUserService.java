package com.example.oops.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserService {

    static final String GOOGLE = "google";
    static final String LOCAL = "local";

    private final AppUserRepository users;
    private final UserIdentityRepository identities;
    private final AtomicReference<AuthenticatedUser> localDev = new AtomicReference<>();

    /**
     * 구글 로그인 결과로 회원을 찾거나 만든다. 같은 (issuer, sub) 면 같은 회원이다.
     * 이메일이 같아도 다른 로그인 수단의 계정과 합치지 않는다.
     */
    @Transactional
    public AppUser loginWithGoogle(String issuer, String subject, String name, String email, boolean emailVerified) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("구글 sub 가 없습니다.");
        String iss = issuer == null || issuer.isBlank() ? "https://accounts.google.com" : issuer;
        AppUser user = identities.find(GOOGLE, iss, subject).map(UserIdentity::getUser).orElseGet(() -> {
            AppUser created = users.save(new AppUser(name, email, emailVerified));
            identities.save(new UserIdentity(created, GOOGLE, iss, subject));
            log.info("[auth] 새 회원 userId={}", created.getId());
            return created;
        });
        user.loggedIn(name, email, emailVerified, LocalDateTime.now());
        return user;
    }

    /** oops.auth.enabled=false 일 때 모든 요청을 대신하는 "로컬 개발자" 계정 */
    @Transactional
    public AuthenticatedUser localDeveloper() {
        AuthenticatedUser cached = localDev.get();
        if (cached != null) return cached;
        AppUser user = identities.find(LOCAL, "oops", "developer").map(UserIdentity::getUser).orElseGet(() -> {
            AppUser created = users.save(new AppUser("로컬 개발자", null, false));
            identities.save(new UserIdentity(created, LOCAL, "oops", "developer"));
            return created;
        });
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getDisplayName(), null, true);
        localDev.compareAndSet(null, principal);
        return localDev.get();
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> find(Long userId) {
        return users.findById(userId);
    }
}
