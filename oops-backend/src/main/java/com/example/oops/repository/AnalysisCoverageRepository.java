package com.example.oops.repository;

import com.example.oops.domain.AnalysisCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnalysisCoverageRepository extends JpaRepository<AnalysisCoverage, Long> {

    @Query("select f from AnalysisCoverage f where f.video.id = :videoId and (f.runId = f.video.currentRunId or (f.video.currentRunId is null and f.runId is null)) order by f.id asc")
    List<AnalysisCoverage> findCurrentByVideoId(@Param("videoId") Long videoId);

    void deleteByVideoId(Long videoId);
}
