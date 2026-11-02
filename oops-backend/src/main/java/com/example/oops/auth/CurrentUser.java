package com.example.oops.auth;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.Video;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * 지금 요청을 보낸 사용자. 요청 본문·파라미터의 userId 는 믿지 않고 세션만 본다.
 * 분석 작업 스레드(백그라운드)에서는 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final AuthProperties properties;
    private final AppUserService users;

    public Optional<AuthenticatedUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) return Optional.of(user);
        return Optional.empty();
    }

    public AuthenticatedUser require() {
        var user = get();
        if (user.isPresent()) return user.get();
        // 로그인을 끈 환경에서는 요청 밖(테스트·내부 호출)에서도 로컬 개발자로 본다.
        if (!properties.isEnabled()) return users.localDeveloper();
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    /** 로그인을 켠 서버에서만 영상 주인을 검사한다. 로컬(로그인 끔)은 한 사람이 쓰는 환경으로 본다. */
    public boolean enforcesOwnership() {
        return properties.isEnabled();
    }

    /** 이 사용자가 이 영상을 볼 수 있는가. 주인이 없는 옛 영상은 아무도 못 본다. */
    public boolean canAccess(Video video) {
        if (!enforcesOwnership()) return true;
        return get().map(u -> video.getOwnerId() != null && Objects.equals(video.getOwnerId(), u.userId())).orElse(false);
    }

    /** 새 영상의 주인 */
    public Long ownerIdForNewVideo() {
        return require().userId();
    }

    public boolean isAdmin() {
        if (!enforcesOwnership()) return true;
        return get().map(AuthenticatedUser::email).filter(e -> e != null && !e.isBlank())
                .map(e -> properties.adminEmailsOrEmpty().stream().anyMatch(a -> a.equalsIgnoreCase(e)))
                .orElse(false);
    }
}
