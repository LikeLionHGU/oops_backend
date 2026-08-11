package com.example.oops.analyzer;

import com.example.oops.domain.RiskFinding;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * [미구현] 영상 프레임에서 부적절한 포즈/제스처를 감지한다.
 *
 * 붙일 때 할 일:
 *   1. FFmpeg 로 N초 간격 프레임 추출
 *   2. MediaPipe/YOLO 를 띄운 Python 서버에 프레임 전송 (RestClient)
 *   3. 응답을 GESTURE 카테고리 finding 으로 변환 (startMs = 프레임 타임코드)
 *   4. application.yml 의 enabled-analyzers 에 "pose" 추가
 */
@Component
public class PoseAnalyzer implements ContentAnalyzer {

    @Override
    public String key() {
        return "pose";
    }

    @Override
    public String displayName() {
        return "포즈/제스처 분석";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return false; // 구현 전까지 항상 스킵
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        return List.of();
    }
}
