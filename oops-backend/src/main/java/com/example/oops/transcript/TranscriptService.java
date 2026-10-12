package com.example.oops.transcript;

import com.example.oops.domain.TranscriptSegment;
import com.example.oops.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptService {

    /** 스프링이 @Order 순서대로 주입해준다. */
    private final List<TranscriptProvider> providers;
    /** External extraction only; the result writer persists this snapshot atomically. */
    public List<TranscriptSegment> extract(Video video) {
        TranscriptProvider provider = providers.stream()
                .filter(p -> p.supports(video))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("사용 가능한 TranscriptProvider 가 없습니다."));

        log.info("[transcript] videoId={} provider={}", video.getId(), provider.getClass().getSimpleName());

        List<TranscriptSegment> segments = provider.fetch(video).stream()
                .map(line -> new TranscriptSegment(video, line.startMs(), line.endMs(), line.text()))
                .toList();

        return segments;
    }
}
