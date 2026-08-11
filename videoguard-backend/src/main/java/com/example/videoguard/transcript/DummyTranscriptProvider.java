package com.example.videoguard.transcript;

import com.example.videoguard.domain.Video;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 분석 서버 없이 파이프라인만 돌려보고 싶을 때 쓰는 데모용 구현.
 * application.yml 에 videoguard.use-dummy-transcript=true 를 넣어야 활성화된다.
 */
@Component
@Order(Integer.MAX_VALUE)
@ConditionalOnProperty(name = "videoguard.use-dummy-transcript", havingValue = "true")
public class DummyTranscriptProvider implements TranscriptProvider {

    @Override
    public boolean supports(Video video) {
        return true; // 최후의 폴백
    }

    @Override
    public List<TranscriptLine> fetch(Video video) {
        return List.of(
                new TranscriptLine(0, 4000, "안녕하세요 오늘도 찾아와 주셔서 감사합니다"),
                new TranscriptLine(4000, 9000, "오늘은 요즘 제일 핫한 주제를 가져와 봤는데요"),
                new TranscriptLine(9000, 15000, "솔직히 그 사람들은 다 병신 같고 수준이 너무 낮아요"),
                new TranscriptLine(15000, 22000, "여자들은 원래 이런 거 잘 모르잖아요 그냥 그런 겁니다"),
                new TranscriptLine(22000, 28000, "이 제품 진짜 좋아서 제 돈 주고 샀습니다 광고 아니에요"),
                new TranscriptLine(28000, 34000, "그리고 저 사람 전화번호가 010-1234-5678 이래요"),
                new TranscriptLine(34000, 40000, "그럼 오늘 영상은 여기까지 구독과 좋아요 부탁드립니다")
        );
    }
}
