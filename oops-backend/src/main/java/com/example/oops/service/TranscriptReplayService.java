package com.example.oops.service;

import com.example.oops.common.*;
import com.example.oops.domain.*;
import com.example.oops.repository.*;
import com.example.oops.transcript.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Copy under the source lock, then use normal after-commit bounded admission. */
@Service
@RequiredArgsConstructor
public class TranscriptReplayService {
    private final VideoRepository videos;
    private final TranscriptSegmentRepository transcripts;
    private final AnalysisAdmissionService admission;

    public record Response(String sourceVideoId,String videoId,String jobId,AnalysisStatus status,
                           int transcriptSegments,String transcriptSha256) {}
    @Transactional
    public Response create(Long sourceId) {
        var source=videos.findByIdForUpdate(sourceId).orElseThrow(()->new BusinessException(ErrorCode.VIDEO_NOT_FOUND));
        if(source.getStatus()!=AnalysisStatus.COMPLETED)
            throw new BusinessException(ErrorCode.INVALID_ANALYSIS_STATE,"완료된 영상의 저장 전사만 재사용할 수 있습니다.");
        var stored=transcripts.findByVideoIdOrderByStartMsAscIdAsc(sourceId);
        if(stored.isEmpty()) throw new BusinessException(ErrorCode.INVALID_ANALYSIS_STATE,"재사용할 저장 전사가 없습니다.");
        var lines=stored.stream().map(s->new TranscriptProvider.TranscriptLine(s.getStartMs(),s.getEndMs(),s.getText())).toList();
        String sha=TranscriptSnapshot.sha256(lines);
        // Never share storageKey: deleting the replay must not delete the source media.
        var replay=Video.builder().sourceType(source.getSourceType()).filename(source.getFilename())
                .sourceUrl(source.getSourceUrl()).title(source.getTitle()).channelName(source.getChannelName())
                .durationSec(source.getDurationSec()).genre(source.getGenre()).build();
        replay.assignTranscriptSnapshot(sourceId,sha);
        replay=videos.save(replay);
        var target=replay;
        transcripts.saveAll(lines.stream().map(s->new TranscriptSegment(target,s.startMs(),s.endMs(),s.text())).toList());
        var job=admission.admit(replay.getId(),false);
        return new Response(sourceId.toString(),replay.getId().toString(),job.getJobKey(),job.getStatus(),lines.size(),sha);
    }
}
