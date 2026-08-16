package com.example.oops.repository;

import com.example.oops.domain.ReviewAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewActionRepository extends JpaRepository<ReviewAction, Long> {

    Optional<ReviewAction> findByFindingId(Long findingId);

    List<ReviewAction> findByVideoIdOrderByIdAsc(Long videoId);

    void deleteByVideoId(Long videoId);
}
