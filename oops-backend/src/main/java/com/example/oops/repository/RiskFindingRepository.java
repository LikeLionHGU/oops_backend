package com.example.oops.repository;

import com.example.oops.domain.RiskFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RiskFindingRepository extends JpaRepository<RiskFinding, Long> {

    List<RiskFinding> findByVideoId(Long videoId);

    /** 화면에 보여줄 실행(Video.currentRunId)의 카드만. 재분석 전 실행의 카드는 이력으로 남는다. */
    @Query("select f from RiskFinding f where f.video.id = :videoId and (f.runId = f.video.currentRunId or (f.video.currentRunId is null and f.runId is null))")
    List<RiskFinding> findCurrentByVideoId(@Param("videoId") Long videoId);

    /** 모든 영상의 현재 실행 카드 (검수 지표용) */
    @Query("select f from RiskFinding f where (f.runId = f.video.currentRunId or (f.video.currentRunId is null and f.runId is null))")
    List<RiskFinding> findAllCurrent();

    /** 실행 하나의 카드 (이력 조회용) */
    List<RiskFinding> findByVideoIdAndRunIdOrderByStartMsAsc(Long videoId, Long runId);

    /**
     * 리포트 조회용. 참고 자료까지 한 번에 가져온다.
     * 그냥 findByVideoId 를 쓰면 카드 수만큼 추가 쿼리가 나간다.
     */
    @Query("select distinct f from RiskFinding f left join fetch f.references where f.video.id = :videoId and (f.runId = f.video.currentRunId or (f.video.currentRunId is null and f.runId is null))")
    List<RiskFinding> findByVideoIdWithReferences(@Param("videoId") Long videoId);

    /**
     * 목록 화면의 eventCount 용. 영상별 검토 후보 개수를 한 번에 센다.
     * 반환은 [videoId, count] 쌍이다.
     */
    @Query("""
            select f.video.id, count(f)
            from RiskFinding f
            where f.video.id in :videoIds and (f.runId = f.video.currentRunId or (f.video.currentRunId is null and f.runId is null))
            group by f.video.id
            """)
    List<Object[]> countByVideoIds(@Param("videoIds") List<Long> videoIds);

    void deleteByVideoId(Long videoId);
}
