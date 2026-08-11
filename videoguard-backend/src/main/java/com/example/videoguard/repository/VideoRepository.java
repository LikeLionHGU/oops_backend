package com.example.videoguard.repository;

import com.example.videoguard.domain.Video;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoRepository extends JpaRepository<Video, Long> {
}
