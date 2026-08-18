package com.example.oops.repository;

import com.example.oops.domain.Video;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.oops.domain.AnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;

public interface VideoRepository extends JpaRepository<Video, Long> {

    /** 검수 이력 필터. 명세 §4 — ALL / COMPLETED / FAILED */
    org.springframework.data.domain.Page<Video> findByStatusIn(
            java.util.List<com.example.oops.domain.AnalysisStatus> statuses,
            org.springframework.data.domain.Pageable pageable);


    /** 보관 기간이 지난 영상. 자동 정리에 쓴다. */
    List<Video> findByCreatedAtBefore(LocalDateTime threshold);

    /** 분석이 끝난 것만 지우기 위한 조건 */
    List<Video> findByCreatedAtBeforeAndStatusIn(LocalDateTime threshold, List<AnalysisStatus> statuses);
}
