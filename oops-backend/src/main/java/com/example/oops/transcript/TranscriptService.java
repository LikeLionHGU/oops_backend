package com.example.oops.transcript;

import com.example.oops.domain.TranscriptSegment;
import com.example.oops.domain.Video;
import com.example.oops.repository.TranscriptSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptService {

    /** 스프링이 @Order 순서대로 주입해준다. */
    private final List<TranscriptProvider> providers;
    private final TranscriptSegmentRepository segmentRepository;

    @Transactional
    public List<TranscriptSegment> extractAndSave(Video video) {
        segmentRepository.deleteByVideoId(video.getId());

        TranscriptProvider provider = providers.stream()
                .filter(p -> p.supports(video))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("사용 가능한 TranscriptProvider 가 없습니다."));

        log.info("[transcript] videoId={} provider={}", video.getId(), provider.getClass().getSimpleName());

        List<TranscriptSegment> segments = provider.fetch(video).stream()
                .map(line -> new TranscriptSegment(video, line.startMs(), line.endMs(), line.text()))
                .toList();

        return segmentRepository.saveAll(segments);
    }
}
