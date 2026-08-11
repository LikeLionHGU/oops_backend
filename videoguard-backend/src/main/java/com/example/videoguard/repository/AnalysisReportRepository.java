package com.example.videoguard.repository;

import com.example.videoguard.domain.AnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    Optional<AnalysisReport> findByVideoId(Long videoId);
}
