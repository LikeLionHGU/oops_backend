package com.example.oops.repository;

import com.example.oops.domain.AnalysisTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisTraceRepository extends JpaRepository<AnalysisTrace, Long> {

    List<AnalysisTrace> findByRunIdOrderByIdAsc(Long runId);

    void deleteByVideoId(Long videoId);
}
