package com.example.oops.repository;

import com.example.oops.domain.Video;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.oops.domain.AnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;

public interface VideoRepository extends JpaRepository<Video, Long> {

    /** 검수 이력 필터. 명세 §4 — ALL / COMPLETED / FAILED */
    org.springframework.data.domain.Page<Video> findByStatusIn(
            java.util.List<com.example.oops.domain.AnalysisStatus> statuses,
            org.springframework.data.domain.Pageable pageable);


    /** 보관 기간이 지난 영상. 자동 정리에 쓴다. */
    List<Video> findByCreatedAtBefore(LocalDateTime threshold);

    /** 분석이 끝난 것만 지우기 위한 조건 */
    List<Video> findByCreatedAtBeforeAndStatusIn(LocalDateTime threshold, List<AnalysisStatus> statuses);

    /**
     * 원본 미디어를 지울 때가 된 영상.
     *
     * 기준을 업로드 시각이 아니라 **분석이 끝난 시각**으로 잡는다.
     * 큰 영상은 분석에만 몇십 분이 걸려서, 업로드 시각으로 재면
     * 사용자가 결과를 받자마자 원본이 사라지는 일이 생긴다.
     *
     * 조건 셋을 모두 만족해야 한다.
     *   - 아직 안 지웠고 (sourcePurgedAt is null)
     *   - 원본 파일이 있고 (storageKey is not null)
     *   - 분석이 threshold 이전에 끝났고, 지금 도는 작업이 없다
     */
    @org.springframework.data.jpa.repository.Query("""
            select v from Video v
            where v.storageKey is not null
              and v.sourcePurgedAt is null
              and exists (
                  select 1 from AnalysisJob j
                  where j.video = v
                    and j.finishedAt is not null
                    and j.finishedAt < :threshold
              )
              and not exists (
                  select 1 from AnalysisJob r
                  where r.video = v and r.status in :running
              )
            """)
    List<Video> findSourcePurgeTargets(
            @org.springframework.data.repository.query.Param("threshold") LocalDateTime threshold,
            @org.springframework.data.repository.query.Param("running") List<AnalysisStatus> running);
}
