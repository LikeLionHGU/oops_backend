package com.example.videoguard.repository;

import com.example.videoguard.domain.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    List<TranscriptSegment> findByVideoIdOrderByStartMsAsc(Long videoId);

    void deleteByVideoId(Long videoId);
}
