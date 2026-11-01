package com.example.oops.repository;

import com.example.oops.domain.ReviewAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewActionRepository extends JpaRepository<ReviewAction, Long> {

    Optional<ReviewAction> findByFindingId(Long findingId);

    List<ReviewAction> findByVideoIdOrderByIdAsc(Long videoId);

    /** 현재 실행 카드에 대한 결정만. 이전 실행 카드의 결정은 이력으로 남는다. */
    @org.springframework.data.jpa.repository.Query(
            "select a from ReviewAction a where a.video.id = :videoId and (a.finding.runId = a.video.currentRunId or (a.video.currentRunId is null and a.finding.runId is null)) order by a.id asc")
    List<ReviewAction> findCurrentByVideoId(@org.springframework.data.repository.query.Param("videoId") Long videoId);

    @org.springframework.data.jpa.repository.Query("select a from ReviewAction a where (a.finding.runId = a.video.currentRunId or (a.video.currentRunId is null and a.finding.runId is null))")
    List<ReviewAction> findAllCurrent();

    void deleteByVideoId(Long videoId);

    /** 이력 화면의 '수정 수'. 영상별로 EDITED 개수를 한 번에 센다 */
    @org.springframework.data.jpa.repository.Query("""
            select a.video.id, count(a)
            from ReviewAction a
            where a.video.id in :videoIds
              and a.action = com.example.oops.domain.ReviewActionType.EDITED
              and (a.finding.runId = a.video.currentRunId or (a.video.currentRunId is null and a.finding.runId is null))
            group by a.video.id
            """)
    List<Object[]> countEditedByVideoIds(
            @org.springframework.data.repository.query.Param("videoIds") List<Long> videoIds);
}
