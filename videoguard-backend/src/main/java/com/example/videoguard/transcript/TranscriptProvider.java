package com.example.videoguard.transcript;

import com.example.videoguard.domain.Video;

import java.util.List;

/**
 * 영상에서 타임코드가 붙은 텍스트를 뽑아내는 역할.
 *
 * 구현체를 갈아끼우기만 하면 된다:
 *  - DummyTranscriptProvider : 지금. 개발/데모용 고정 데이터
 *  - YoutubeCaptionProvider  : 유튜브 자막 트랙 다운로드
 *  - WhisperProvider         : Whisper STT (Python 서버 호출)
 */
public interface TranscriptProvider {

    boolean supports(Video video);

    List<TranscriptLine> fetch(Video video);

    record TranscriptLine(long startMs, long endMs, String text) {}
}
