package com.example.oops.service;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.ReviewAction;
import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.Video;
import com.example.oops.dto.ReviewActionRequest;
import com.example.oops.dto.ReviewActionResponse;
import com.example.oops.repository.ReviewActionRepository;
import com.example.oops.repository.RiskFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 제작자가 검토 후보를 어떻게 처리했는지 저장한다. (명세 §9-2)
 *
 * 새로고침해도 남아야 하고, 나중에 오탐 비율을 세는 근거가 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewActionService {

    private final VideoService videoService;
    private final RiskFindingRepository findingRepository;
    private final ReviewActionRepository actionRepository;

    @Transactional
    public ReviewActionResponse save(Long videoId, ReviewActionRequest request) {
        Video video = videoService.getEntity(videoId);
        RiskFinding finding = findingRepository.findById(request.eventId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));

        // 다른 영상의 후보 id 를 보내면 엉뚱한 곳에 기록된다.
        // 명세도 이 경우를 FRAME_NOT_FOUND 가 아니라 EVENT_NOT_FOUND 로 구분하라고 했다.
        if (!finding.getVideo().getId().equals(videoId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND,
                    "이 영상의 검토 후보가 아닙니다.");
        }

        // 같은 후보를 다시 처리하면 마지막 것으로 덮는다
        ReviewAction action = actionRepository.findByFindingId(finding.getId())
                .map(existing -> {
                    existing.update(request.action(), request.note());
                    return existing;
                })
                .orElseGet(() -> actionRepository.save(
                        ReviewAction.of(video, finding, request.action(), request.note())));

        // 응답의 updatedAt 을 방금 값으로 주려면 여기서 밀어넣어야 한다.
        // 커밋 때 갱신되게 두면 수정한 경우에 직전 시각이 나간다.
        actionRepository.flush();

        log.info("[review-action] videoId={} eventId={} → {}",
                videoId, finding.getId(), request.action());

        return ReviewActionResponse.from(action);
    }

    /**
     * 저장된 처리 목록.
     *
     * 명세에는 POST 만 있지만 이게 없으면 새로고침 후 복구가 안 된다.
     * 저장만 하고 못 읽으면 저장하는 의미가 없다.
     */
    public List<ReviewActionResponse> findByVideo(Long videoId) {
        videoService.getEntity(videoId);   // 없는 영상이면 VIDEO_NOT_FOUND
        return actionRepository.findByVideoIdOrderByIdAsc(videoId).stream()
                .map(ReviewActionResponse::from)
                .toList();
    }
}
