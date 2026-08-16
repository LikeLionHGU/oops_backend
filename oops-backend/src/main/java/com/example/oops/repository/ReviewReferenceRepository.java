package com.example.oops.repository;

import com.example.oops.domain.ReviewReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewReferenceRepository extends JpaRepository<ReviewReference, Long> {

    /**
     * 영상의 참고 자료를 전부 지운다.
     *
     * risk_finding 을 지우기 전에 먼저 호출해야 한다.
     * finding 쪽 삭제는 벌크 쿼리라 cascade 가 타지 않아서, 남아 있으면 외래키에 걸린다.
     */
    @Modifying
    @Query("delete from ReviewReference r where r.finding.video.id = :videoId")
    void deleteByVideoId(@Param("videoId") Long videoId);
}
