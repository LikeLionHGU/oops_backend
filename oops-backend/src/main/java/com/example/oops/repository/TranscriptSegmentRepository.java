package com.example.oops.repository;

import com.example.oops.domain.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    List<TranscriptSegment> findByVideoIdOrderByStartMsAsc(Long videoId);

    void deleteByVideoId(Long videoId);
}
