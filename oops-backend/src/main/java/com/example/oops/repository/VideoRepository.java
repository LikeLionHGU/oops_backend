package com.example.oops.repository;

import com.example.oops.domain.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.example.oops.domain.AnalysisStatus;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VideoRepository extends JpaRepository<Video, Long> {

    /**
     * 분석 시작 전 영상 행을 비관적 락으로 잡는다.
     * 동시에 두 요청이 startAnalysis 를 호출해도 하나만 통과한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Video v WHERE v.id = :id")
    Optional<Video> findByIdForUpdate(Long id);

    /** 검수 이력 필터. 명세 §4 — ALL / COMPLETED / FAILED */
    org.springframework.data.domain.Page<Video> findByStatusIn(
            java.util.List<com.example.oops.domain.AnalysisStatus> statuses,
            org.springframework.data.domain.Pageable pageable);


    /** 보관 기간이 지난 영상. 자동 정리에 쓴다. */
    List<Video> findByCreatedAtBefore(LocalDateTime threshold);

    /** 분석이 끝난 것만 지우기 위한 조건 */
    List<Video> findByCreatedAtBeforeAndStatusIn(LocalDateTime threshold, List<AnalysisStatus> statuses);
}
