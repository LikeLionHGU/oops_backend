package com.example.oops.repository;

import com.example.oops.domain.ScreenText;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScreenTextRepository extends JpaRepository<ScreenText, Long> {

    List<ScreenText> findByVideoIdOrderByStartMsAsc(Long videoId);

    void deleteByVideoId(Long videoId);
}
