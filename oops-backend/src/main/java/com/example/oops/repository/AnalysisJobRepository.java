package com.example.oops.repository;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from AnalysisJob j where j.id = :id")
    Optional<AnalysisJob> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<AnalysisJob> findFirstByVideoIdOrderByIdDesc(Long videoId);

    Optional<AnalysisJob> findTopByVideoIdOrderByIdDesc(Long videoId);

    /**
     * 목록 화면에서 영상마다 진행률을 붙이기 위한 조회.
     *
     * id 오름차순이라 Map 에 넣으면 나중 것이 앞의 것을 덮어써서 최신 Job 이 남는다.
     * 영상 하나씩 조회하면 100건에 쿼리가 100번 나간다.
     */
    List<AnalysisJob> findByVideoIdInOrderByIdAsc(List<Long> videoIds);

    Optional<AnalysisJob> findByJobKey(String jobKey);

    boolean existsByVideoIdAndStatusIn(Long videoId, List<AnalysisStatus> statuses);

    /** 서버 재시작 시 중간에 끊긴 잡을 찾는 용도 */
    List<AnalysisJob> findByStatusIn(List<AnalysisStatus> statuses);

    void deleteByVideoId(Long videoId);
}
