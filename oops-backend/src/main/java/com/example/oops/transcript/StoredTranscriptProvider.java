package com.example.oops.transcript;

import com.example.oops.domain.Video;
import com.example.oops.repository.TranscriptSegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.List;

/** Replay its own immutable admission snapshot. Never falls back to remote STT. */
@Component
@Order(0)
@RequiredArgsConstructor
public class StoredTranscriptProvider implements TranscriptProvider {
    private final TranscriptSegmentRepository transcripts;
    @Override public boolean supports(Video video) {return video.hasStoredTranscript();}
    @Override public List<TranscriptLine> fetch(Video video) {
        var lines=transcripts.findByVideoIdOrderByStartMsAscIdAsc(video.getId()).stream()
                .map(s->new TranscriptLine(s.getStartMs(),s.getEndMs(),s.getText())).toList();
        if(!TranscriptSnapshot.sha256(lines).equals(video.getTranscriptSnapshotSha256()))
            throw new IllegalStateException("Stored transcript snapshot changed");
        return lines;
    }
}
