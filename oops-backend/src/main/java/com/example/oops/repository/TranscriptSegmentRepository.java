package com.example.oops.repository;

import com.example.oops.domain.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    /** 화면에 보여줄 실행의 전사. 재분석마다 전사 행이 새로 저장되므로 실행으로 걸러야 한다. */
    @Query("select f from TranscriptSegment f where f.video.id = :videoId and (f.runId = f.video.currentRunId or (f.video.currentRunId is null and f.runId is null)) order by f.startMs asc, f.id asc")
    List<TranscriptSegment> findCurrentByVideoId(@Param("videoId") Long videoId);

    void deleteByVideoId(Long videoId);
}
