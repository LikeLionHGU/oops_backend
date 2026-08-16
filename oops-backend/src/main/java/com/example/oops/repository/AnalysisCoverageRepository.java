package com.example.oops.repository;

import com.example.oops.domain.AnalysisCoverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisCoverageRepository extends JpaRepository<AnalysisCoverage, Long> {

    List<AnalysisCoverage> findByVideoIdOrderByIdAsc(Long videoId);

    void deleteByVideoId(Long videoId);
}
