package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import com.example.oops.transcript.*;
import com.example.oops.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptImportServiceTest {
    ScriptImportService.Request request(List<TranscriptProvider.TranscriptLine> lines) {
        return new ScriptImportService.Request("https://www.youtube.com/watch?v=Duvb0IftlbI", "제공 스크립트", "NEXT_CUE_BOUNDARIES_LAST_POINT", lines);
    }
    @Test void importsOwnSnapshotAndAdmitsTextOnlyWithoutInventingSourceVideo() {
        var videos = mock(VideoRepository.class); var transcripts = mock(TranscriptSegmentRepository.class);
        var admission = mock(AnalysisAdmissionService.class);
        var job = mock(AnalysisJob.class); when(job.getJobKey()).thenReturn("job_test");
        when(admission.admit(44L, false)).thenReturn(job);
        when(videos.save(any(Video.class))).thenAnswer(call -> {
            Video v = call.getArgument(0); ReflectionTestUtils.setField(v, "id", 44L); return v;
        });
        var lines = List.of(new TranscriptProvider.TranscriptLine(2000,7000," [음악] "),
                new TranscriptProvider.TranscriptLine(7000,7000,"원문 오류 93km"));
        var result = new ScriptImportService(videos, transcripts, admission).create(request(lines));
        assertThat(result.videoId()).isEqualTo("44"); assertThat(result.transcriptSegments()).isEqualTo(2);
        assertThat(result.transcriptSha256()).isEqualTo(TranscriptSnapshot.sha256(lines));
        var captured = org.mockito.ArgumentCaptor.forClass(Video.class); verify(videos).save(captured.capture());
        assertThat(captured.getValue().hasStoredTranscript()).isTrue();
        assertThat(captured.getValue().getTranscriptSourceVideoId()).isNull();
        assertThat(captured.getValue().isStreamable()).isFalse();
        assertThat(captured.getValue().getDurationSec()).isNull();
        assertThat(new StoredTranscriptProvider(transcripts).supports(captured.getValue())).isTrue();
        verify(admission).admit(44L, false);
    }
    @Test void malformedOrOverlappingInputCannotWriteOrStartPaidWork() {
        var videos = mock(VideoRepository.class); var transcripts = mock(TranscriptSegmentRepository.class);
        var admission = mock(AnalysisAdmissionService.class); var service = new ScriptImportService(videos,transcripts,admission);
        var bad = List.of(
                request(List.of()),
                request(List.of(new TranscriptProvider.TranscriptLine(-1,1,"a"))),
                request(List.of(new TranscriptProvider.TranscriptLine(0,10,"a"),new TranscriptProvider.TranscriptLine(5,20,"b"))),
                request(List.of(new TranscriptProvider.TranscriptLine(0,1," "))),
                request(List.of(new TranscriptProvider.TranscriptLine(0,1,"x".repeat(2001)))),
                new ScriptImportService.Request("http://127.0.0.1/private","title","NEXT_CUE_BOUNDARIES_LAST_POINT",List.of(new TranscriptProvider.TranscriptLine(0,1,"a"))));
        for (var input : bad) assertThatThrownBy(() -> service.create(input)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(videos, transcripts, admission);
    }
    @Test void regularMediaAndReplayKeepTheirProviderContract() {
        var media = Video.builder().sourceType(SourceType.YOUTUBE).build();
        assertThat(media.hasStoredTranscript()).isFalse();
        var replay = Video.builder().sourceType(SourceType.UPLOAD).build();
        replay.assignTranscriptSnapshot(12L,"a".repeat(64));
        assertThat(replay.hasStoredTranscript()).isTrue();
        assertThat(replay.getTranscriptSourceVideoId()).isEqualTo(12L);
    }
}
