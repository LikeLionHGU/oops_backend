package com.example.videoguard.transcript;

import com.example.videoguard.client.AnalysisServerClient;
import com.example.videoguard.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Python 분석 서버의 /transcribe (OpenAI Whisper) 를 호출해 타임스탬프 대본을 만든다.
 * @Order(1) 이라 DummyTranscriptProvider 보다 먼저 선택된다.
 */
@Slf4j
@Order(1)
@Component
@RequiredArgsConstructor
public class WhisperTranscriptProvider implements TranscriptProvider {

    private final AnalysisServerClient analysisServerClient;

    @Override
    public boolean supports(Video video) {
        // URL 이든 업로드 파일이든 분석 서버가 처리한다
        return video.getSourceUrl() != null || video.getStorageKey() != null;
    }

    @Override
    public List<TranscriptLine> fetch(Video video) {
        var response = analysisServerClient.transcribe(video).orElse(null);

        if (response == null || response.segments() == null || response.segments().isEmpty()) {
            log.warn("[whisper] 대본을 받지 못했습니다. videoId={}", video.getId());
            return List.of();
        }

        // 분석 서버가 알아낸 제목/길이를 영상 메타데이터에 반영
        video.updateMetadata(response.title(), null, response.durationSec());

        return response.segments().stream()
                .filter(s -> s.text() != null && !s.text().isBlank())
                .map(s -> new TranscriptLine(s.startMs(), s.endMs(), s.text().trim()))
                .toList();
    }
}
