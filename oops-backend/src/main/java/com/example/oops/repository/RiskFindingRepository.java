package com.example.oops.repository;

import com.example.oops.domain.RiskFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskFindingRepository extends JpaRepository<RiskFinding, Long> {

    List<RiskFinding> findByVideoId(Long videoId);

    void deleteByVideoId(Long videoId);
}
