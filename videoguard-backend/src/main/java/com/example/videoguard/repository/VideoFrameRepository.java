package com.example.videoguard.repository;

import com.example.videoguard.domain.VideoFrame;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VideoFrameRepository extends JpaRepository<VideoFrame, Long> {

    Optional<VideoFrame> findByIdAndVideoId(Long id, Long videoId);

    List<VideoFrame> findByVideoIdOrderByTimeMsAsc(Long videoId);

    void deleteByVideoId(Long videoId);
}
