package com.example.oops.auth;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.repository.VideoRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 모든 /api 요청에서 두 가지를 확인한다.
 *   1. 계정 상태 — 세션이 남아 있어도 정지·탈퇴한 계정이면 401 (세션을 끊는다)
 *   2. 영상 주인 — 경로에 {videoId} 가 있으면 그 영상의 주인인지. 아니면 404 (남의 영상이 있는지조차 알리지 않는다)
 *
 * 컨트롤러(VideoController·MediaController·TranscriptReplayController)마다 검사를 넣지 않고 여기 한 곳에서 한다.
 * 새 영상 API 를 만들 때도 경로 변수 이름을 videoId 로 하면 자동으로 검사된다.
 */
@Component
@RequiredArgsConstructor
public class AccessInterceptor implements HandlerInterceptor {

    private final CurrentUser currentUser;
    private final AppUserService users;
    private final VideoRepository videos;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!currentUser.enforcesOwnership()) return true;
        var user = currentUser.get();
        if (user.isPresent() && !user.get().local()) {
            boolean active = users.find(user.get().userId()).map(AppUser::isActive).orElse(false);
            if (!active) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "사용할 수 없는 계정입니다.");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String raw = vars == null ? null : vars.get("videoId");
        if (raw == null) return true;
        Long id;
        try {
            id = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return true;   // 컨트롤러가 형식 오류를 낸다
        }
        var video = videos.findById(id).orElse(null);
        if (video == null || !currentUser.canAccess(video)) throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
        return true;
    }
}
