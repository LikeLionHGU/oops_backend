package com.example.oops.repository;

import com.example.oops.domain.RiskFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RiskFindingRepository extends JpaRepository<RiskFinding, Long> {

    List<RiskFinding> findByVideoId(Long videoId);

    /**
     * 리포트 조회용. 참고 자료까지 한 번에 가져온다.
     * 그냥 findByVideoId 를 쓰면 카드 수만큼 추가 쿼리가 나간다.
     */
    @Query("select distinct f from RiskFinding f left join fetch f.references where f.video.id = :videoId")
    List<RiskFinding> findByVideoIdWithReferences(@Param("videoId") Long videoId);

    /**
     * 목록 화면의 eventCount 용. 영상별 검토 후보 개수를 한 번에 센다.
     * 반환은 [videoId, count] 쌍이다.
     */
    @Query("""
            select f.video.id, count(f)
            from RiskFinding f
            where f.video.id in :videoIds
            group by f.video.id
            """)
    List<Object[]> countByVideoIds(@Param("videoIds") List<Long> videoIds);

    void deleteByVideoId(Long videoId);
}
