package com.example.oops.analyzer;

import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.ScreenText;
import com.example.oops.domain.TranscriptSegment;
import com.example.oops.domain.Video;

import java.util.List;

/**
 * 분석기들이 공유하는 입력 묶음.
 * 새 데이터 소스(댓글, 프레임 등)가 생기면 여기에 필드를 추가한다.
 */
public record AnalysisContext(
        Video video,
        ContentGenre genre,                   // 영상 유형. 분석기가 자기를 돌릴지 판단하는 데 쓴다
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

    public ContentGenre genreOrGeneral() {
        return genre == null ? ContentGenre.GENERAL : genre;
    }
}
