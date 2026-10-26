package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import com.example.oops.transcript.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:transcript-replay-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Transactional
class TranscriptReplayServiceTest {
    @Autowired TranscriptReplayService replays;
    @Autowired ScriptImportService scriptImports;
    @Autowired VideoRepository videos;
    @Autowired TranscriptSegmentRepository transcripts;
    @Autowired AnalysisJobRepository jobs;
    @Autowired AnalysisReportRepository reports;
    @Autowired StoredTranscriptProvider provider;
    @Autowired TranscriptService transcriptService;
    @MockitoBean AnalysisDispatchService dispatch;
    @MockitoBean com.example.oops.client.AnalysisServerClient worker;

    @org.junit.jupiter.api.Tag("dataset-backed")
    @Test void fullPrivatePodcastScriptPersistsAndExtractsWithoutRemoteStt() throws Exception {
        var file = java.nio.file.Path.of("../datasets/controversy/experiments/podcast-Duvb0IftlbI-20261024/import-request.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(file));
        var input = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(java.nio.file.Files.readAllBytes(file), ScriptImportService.Request.class);
        var response = scriptImports.create(input);
        var target = videos.findById(Long.valueOf(response.videoId())).orElseThrow();
        var extracted = transcriptService.extract(target);
        assertThat(extracted).hasSize(input.segments().size());
        assertThat(extracted.stream().map(s -> new TranscriptProvider.TranscriptLine(s.getStartMs(),s.getEndMs(),s.getText())).toList())
                .isEqualTo(input.segments());
        assertThat(target.getTranscriptSourceVideoId()).isNull();
        assertThat(target.getDurationSec()).isNull();
        assertThat(provider.supports(target)).isTrue();
        verifyNoInteractions(worker);
    }

    @Test void newVideoOwnsSnapshotAndOriginalReportAndMediaArePreserved() {
        var source=Video.builder().sourceType(SourceType.UPLOAD).storageKey("videos/source/original.mp4").filename("original.mp4").build();
        source.updateStatus(AnalysisStatus.COMPLETED);source=videos.saveAndFlush(source);
        var first=new TranscriptSegment(source,0,1000," 원문 첫 줄 ");
        var second=new TranscriptSegment(source,0,1500,"시작 시간이 같은 다음 줄");
        transcripts.saveAllAndFlush(List.of(first,second));
        reports.saveAndFlush(new AnalysisReport(source,0,0,"원본 결과"));
        long originalReport=reports.findByVideoId(source.getId()).orElseThrow().getId();
        var response=replays.create(source.getId());
        long targetId=Long.parseLong(response.videoId());
        var target=videos.findById(targetId).orElseThrow();
        assertThat(targetId).isNotEqualTo(source.getId());
        assertThat(target.getStorageKey()).isNull();assertThat(target.isStreamable()).isFalse();
        assertThat(target.getTranscriptSourceVideoId()).isEqualTo(source.getId());
        assertThat(provider.fetch(target)).containsExactly(new TranscriptProvider.TranscriptLine(0,1000," 원문 첫 줄 "),
                new TranscriptProvider.TranscriptLine(0,1500,"시작 시간이 같은 다음 줄"));
        assertThat(transcriptService.extract(target)).extracting(TranscriptSegment::getText).containsExactly(" 원문 첫 줄 ","시작 시간이 같은 다음 줄");
        verify(worker,never()).transcribe(any());
        assertThat(transcripts.findByVideoIdOrderByStartMsAscIdAsc(source.getId())).extracting(TranscriptSegment::getId)
                .containsExactly(first.getId(),second.getId());
        assertThat(source.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(source.getStorageKey()).isEqualTo("videos/source/original.mp4");
        assertThat(reports.findByVideoId(source.getId()).orElseThrow().getId()).isEqualTo(originalReport);
        var job=jobs.findTopByVideoIdOrderByIdDesc(targetId).orElseThrow();
        verify(dispatch).submit(job.getId());assertThat(response.jobId()).isEqualTo(job.getJobKey());
        // The copied snapshot does not require the source transcript to remain present.
        transcripts.deleteByVideoId(source.getId());transcripts.flush();
        assertThat(provider.fetch(target)).hasSize(2);
    }
    @Test void runningOrEmptySourceCreatesNoNewVideoOrJob() {
        var source=videos.saveAndFlush(Video.builder().sourceType(SourceType.YOUTUBE).sourceUrl("https://example.invalid/video").build());
        long count=videos.count();
        assertThatThrownBy(()->replays.create(source.getId())).isInstanceOf(com.example.oops.common.BusinessException.class);
        source.updateStatus(AnalysisStatus.COMPLETED);videos.saveAndFlush(source);
        assertThatThrownBy(()->replays.create(source.getId())).isInstanceOf(com.example.oops.common.BusinessException.class);
        assertThat(videos.count()).isEqualTo(count);verifyNoInteractions(dispatch);
    }
}
