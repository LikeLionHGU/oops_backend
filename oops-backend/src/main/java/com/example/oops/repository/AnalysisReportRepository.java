package com.example.oops.repository;

import com.example.oops.domain.AnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    Optional<AnalysisReport> findByVideoId(Long videoId);

    void deleteByVideoId(Long videoId);
}
