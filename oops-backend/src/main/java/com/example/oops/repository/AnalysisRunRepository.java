package com.example.oops.repository;

import com.example.oops.domain.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {

    List<AnalysisRun> findByVideoIdOrderByIdDesc(Long videoId);

    void deleteByVideoId(Long videoId);
}
