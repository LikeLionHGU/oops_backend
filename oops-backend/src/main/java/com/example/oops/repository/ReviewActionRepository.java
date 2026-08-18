package com.example.oops.repository;

import com.example.oops.domain.ReviewAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewActionRepository extends JpaRepository<ReviewAction, Long> {

    Optional<ReviewAction> findByFindingId(Long findingId);

    List<ReviewAction> findByVideoIdOrderByIdAsc(Long videoId);

    void deleteByVideoId(Long videoId);

    /** 이력 화면의 '수정 수'. 영상별로 EDITED 개수를 한 번에 센다 */
    @org.springframework.data.jpa.repository.Query("""
            select a.video.id, count(a)
            from ReviewAction a
            where a.video.id in :videoIds
              and a.action = com.example.oops.domain.ReviewActionType.EDITED
            group by a.video.id
            """)
    List<Object[]> countEditedByVideoIds(
            @org.springframework.data.repository.query.Param("videoIds") List<Long> videoIds);
}
