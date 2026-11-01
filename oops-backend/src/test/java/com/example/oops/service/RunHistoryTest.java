package com.example.oops.service;

import com.example.oops.common.BusinessException;
import com.example.oops.domain.*;
import com.example.oops.dto.ReviewActionRequest;
import com.example.oops.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** redesign-v3 8단계: 재분석해도 이전 실행 결과와 검수 기록이 남고, 화면은 현재 실행만 읽는다. */
@SpringBootTest
@ActiveProfiles("test")
class RunHistoryTest {
    @Autowired VideoRepository videos;
    @Autowired AnalysisJobRepository jobs;
    @Autowired AnalysisResultWriter writer;
    @Autowired RiskFindingRepository findings;
    @Autowired TranscriptSegmentRepository segments;
    @Autowired AnalysisCoverageRepository coverage;
    @Autowired AnalysisRunRepository runs;
    @Autowired AnalysisTraceRepository traces;
    @Autowired ReviewActionRepository actions;
    @Autowired ReviewActionService reviews;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean ProgressPublisher publisher;

    private Long startJob(Long videoId) {
        return new TransactionTemplate(manager).execute(tx -> {
            Video video = videoId == null
                    ? videos.save(Video.builder().sourceType(SourceType.UPLOAD).filename("run-history.mp4").build())
                    : videos.findById(videoId).orElseThrow();
            var job = new AnalysisJob(video);
            job.start();
            video.updateStatus(AnalysisStatus.PROCESSING);
            return jobs.saveAndFlush(job).getId();
        });
    }

    private Video videoOf(Long jobId) {
        Long id = new TransactionTemplate(manager).execute(tx -> jobs.findById(jobId).orElseThrow().getVideo().getId());
        return videos.findById(id).orElseThrow();
    }

    private AnalysisResultWriter.Result result(Video video, String line, String findingText, String version) {
        var finding = RiskFinding.builder().video(video).eventType(TimelineEventType.SPEECH)
                .category(RiskCategory.CONTEXT_CRITERION).source(EvidenceSource.SUBTITLE)
                .score(.5).startMs(0).endMs(1000).text(findingText).reason("근거").build();
        finding.recordCriterion("G01", "기준", version);
        return new AnalysisResultWriter.Result(video, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(video, 0, 1000, line)), List.of(),
                List.of(AnalysisCoverage.of(video, CoverageStep.STT, AnalyzerStatus.SUCCESS, null)),
                List.of(finding), 10, "요약", "offline", version, false,
                List.of(new AnalysisResultWriter.TraceEntry("context-review", "{\"v\":\"" + version + "\"}")));
    }

    @Test void reanalysisKeepsPreviousRunAndShowsOnlyTheNewOne() {
        Long first = startJob(null);
        Video video = videoOf(first);
        assertThat(writer.save(first, result(video, "첫 원문", "첫 카드", "oops-all@0.3"))).isTrue();

        Long oldFinding = findings.findCurrentByVideoId(video.getId()).get(0).getId();
        new TransactionTemplate(manager).executeWithoutResult(tx -> reviews.save(video.getId(), oldFinding.toString(),
                new ReviewActionRequest(ReviewActionType.CONFIRMED, null)));

        Long second = startJob(video.getId());
        assertThat(writer.save(second, result(video, "둘째 원문", "둘째 카드", "oops-all@0.4-draft"))).isTrue();

        assertThat(findings.findCurrentByVideoId(video.getId())).extracting(RiskFinding::getText).containsExactly("둘째 카드");
        assertThat(segments.findCurrentByVideoId(video.getId())).extracting(TranscriptSegment::getText).containsExactly("둘째 원문");
        assertThat(coverage.findCurrentByVideoId(video.getId())).hasSize(1);
        assertThat(actions.findCurrentByVideoId(video.getId())).isEmpty();

        // 이전 실행은 지워지지 않는다
        assertThat(findings.findByVideoIdAndRunIdOrderByStartMsAsc(video.getId(), first))
                .extracting(RiskFinding::getText).containsExactly("첫 카드");
        assertThat(actions.findByVideoIdOrderByIdAsc(video.getId())).hasSize(1);
        assertThat(runs.findByVideoIdOrderByIdDesc(video.getId()))
                .extracting(AnalysisRun::getJobId, AnalysisRun::getCriteriaVersion)
                .containsExactly(tuple(second, "oops-all@0.4-draft"), tuple(first, "oops-all@0.3"));
        assertThat(traces.findByRunIdOrderByIdAsc(second)).singleElement()
                .extracting(AnalysisTrace::getAnalyzer).isEqualTo("context-review");

        // 이전 실행 카드에는 새 결정을 붙이지 않는다
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> reviews.save(video.getId(),
                oldFinding.toString(), new ReviewActionRequest(ReviewActionType.HOLD, null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test void legacyRowsWithoutRunAreStillReadUntilTheFirstNewRun() {
        Long job = startJob(null);
        Video video = videoOf(job);
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                segments.save(new TranscriptSegment(videos.findById(video.getId()).orElseThrow(), 0, 1000, "옛 원문")));
        assertThat(segments.findCurrentByVideoId(video.getId())).extracting(TranscriptSegment::getText).containsExactly("옛 원문");
        assertThat(writer.save(job, result(video, "새 원문", "카드", "v"))).isTrue();
        assertThat(segments.findCurrentByVideoId(video.getId())).extracting(TranscriptSegment::getText).containsExactly("새 원문");
    }
}
