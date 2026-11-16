package com.example.oops.transcript;

import com.example.oops.domain.Video;

import java.util.List;

/**
 * 영상에서 타임코드가 붙은 텍스트를 뽑아내는 역할.
 *
 * 구현체 (@Order 순서로 첫 supports() 가 선택된다):
 *  - StoredTranscriptProvider  : 전사 재사용 실행의 자기 소유 스냅샷. 원격 STT로 폴백하지 않는다.
 *  - WhisperTranscriptProvider : Python 서버의 Groq STT 호출
 *  - DummyTranscriptProvider   : oops.use-dummy-transcript=true 일 때만 켜지는 고정 데이터
 */
public interface TranscriptProvider {

    boolean supports(Video video);

    List<TranscriptLine> fetch(Video video);

    record TranscriptLine(long startMs, long endMs, String text) {}
}
