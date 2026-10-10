package com.example.oops.repository;

import com.example.oops.domain.ExpressionOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExpressionOccurrenceRepository extends JpaRepository<ExpressionOccurrence, Long> {
    List<ExpressionOccurrence> findByVideoIdOrderByStartMsAscStartOffsetAsc(Long videoId);
    void deleteByVideoId(Long videoId);
}
