package com.example.oops.repository;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findTopByVideoIdOrderByIdDesc(Long videoId);

    Optional<AnalysisJob> findByJobKey(String jobKey);

    boolean existsByVideoIdAndStatusIn(Long videoId, List<AnalysisStatus> statuses);

    void deleteByVideoId(Long videoId);
}
