package com.example.oops.service;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.common.Ids;
import com.example.oops.domain.*;
import com.example.oops.dto.ReviewActionRequest;
import com.example.oops.dto.ReviewActionResponse;
import com.example.oops.dto.ReviewCompletionResponse;
import com.example.oops.repository.ReviewActionRepository;
import com.example.oops.repository.RiskFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 제작자가 검토 후보를 어떻게 처리했는지 저장한다. 명세 §6.
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

    /**
     * 후보 한 건의 결정을 저장한다.
     *
     * 같은 요청을 반복해도 결과가 같다(idempotent).
     * 프론트가 재시도하거나 더블클릭해도 안전해야 한다.
     */
    @Transactional
    public ReviewActionResponse save(Long videoId, String eventId, ReviewActionRequest request) {
        Video video = videoService.getEntity(videoId);
        RiskFinding finding = findingRepository.findById(Ids.parse(eventId))
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));

        // 다른 영상의 후보 id 를 보내면 엉뚱한 곳에 기록된다
        if (!finding.getVideo().getId().equals(videoId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND,
                    "이 영상의 검토 후보가 아닙니다.");
        }

        ReviewAction action = actionRepository.findByFindingId(finding.getId())
                .map(existing -> {
                    existing.update(request.action(), request.note());
                    return existing;
                })
                .orElseGet(() -> actionRepository.save(
                        ReviewAction.of(video, finding, request.action(), request.note())));

        // 명세 §6 — 첫 결정을 저장하면 IN_REVIEW 가 된다
        video.markReviewStarted();

        // 응답의 updatedAt 을 방금 값으로 주려면 여기서 밀어넣어야 한다
        actionRepository.flush();

        log.info("[review-action] videoId={} eventId={} → {}",
                videoId, finding.getId(), request.action());
        return ReviewActionResponse.from(action);
    }

    /**
     * 검수 완료. 명세 §6.
     *
     * 남은 후보가 있으면 거절한다. "다 봤다" 는 기록은 실제로 다 봤을 때만 남아야 한다.
     */
    @Transactional
    public ReviewCompletionResponse complete(Long videoId) {
        Video video = videoService.getEntity(videoId);

        List<RiskFinding> findings = findingRepository.findByVideoId(videoId);
        List<ReviewAction> actions = actionRepository.findByVideoIdOrderByIdAsc(videoId);

        Set<Long> decided = new HashSet<>();
        int confirmed = 0, edited = 0, hold = 0, notUseful = 0;

        for (ReviewAction a : actions) {
            decided.add(a.getFinding().getId());
            switch (a.getAction()) {
                case CONFIRMED -> confirmed++;
                case EDITED -> edited++;
                case HOLD -> hold++;
                case NOT_USEFUL -> notUseful++;
            }
        }

        long remaining = findings.stream()
                .filter(f -> !decided.contains(f.getId()))
                .count();

        if (remaining > 0) {
            throw new BusinessException(ErrorCode.REVIEW_INCOMPLETE,
                    "아직 결정하지 않은 검토 후보가 %d건 있습니다.".formatted(remaining));
        }

        LocalDateTime now = LocalDateTime.now();
        video.markReviewCompleted(now);

        log.info("[review] videoId={} 검수 완료 — 확인 {} 수정 {} 보류 {} 유용하지않음 {}",
                videoId, confirmed, edited, hold, notUseful);

        return new ReviewCompletionResponse(
                Ids.of(videoId),
                ReviewStatus.COMPLETED,
                Ids.utc(now),
                new ReviewCompletionResponse.Summary(
                        findings.size(), confirmed, edited, hold, notUseful));
    }

    /**
     * 저장된 결정 목록.
     *
     * 리포트의 events[].reviewAction 으로도 오지만,
     * 새로고침 복구를 이것만으로 처리하고 싶을 때 쓴다.
     */
    public List<ReviewActionResponse> findByVideo(Long videoId) {
        videoService.getEntity(videoId);
        return actionRepository.findByVideoIdOrderByIdAsc(videoId).stream()
                .map(ReviewActionResponse::from)
                .toList();
    }
}
