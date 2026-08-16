package com.example.oops.repository;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

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

    void deleteByVideoId(Long videoId);
}
