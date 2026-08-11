package com.example.videoguard.analyzer;

import com.example.videoguard.domain.ScreenText;
import com.example.videoguard.domain.TranscriptSegment;
import com.example.videoguard.domain.Video;

import java.util.List;

/**
 * 분석기들이 공유하는 입력 묶음.
 * 새 데이터 소스(댓글, 프레임 등)가 생기면 여기에 필드를 추가한다.
 */
public record AnalysisContext(
        Video video,
        List<TranscriptSegment> transcript,   // 음성 → STT 대본
        List<ScreenText> screenTexts          // 화면 → OCR 텍스트
        // TODO: List<YoutubeComment> comments
) {
    public boolean hasTranscript() {
        return transcript != null && !transcript.isEmpty();
    }

    public boolean hasScreenText() {
        return screenTexts != null && !screenTexts.isEmpty();
    }
}
