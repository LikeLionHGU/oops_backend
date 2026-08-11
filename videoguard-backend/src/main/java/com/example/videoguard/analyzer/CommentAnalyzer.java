package com.example.videoguard.analyzer;

import com.example.videoguard.domain.RiskFinding;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * [미구현] 유튜브 댓글 여론으로 논란을 감지한다.
 *
 * 붙일 때 할 일:
 *   1. YouTube Data API v3 commentThreads.list 로 댓글 수집
 *   2. AnalysisContext 에 comments 필드 추가
 *   3. 부정 감성 비율 / 특정 키워드 급증을 COMMENT_BACKLASH finding 으로 변환
 *   4. application.yml 의 enabled-analyzers 에 "comment" 추가
 */
@Component
public class CommentAnalyzer implements ContentAnalyzer {

    @Override
    public String key() {
        return "comment";
    }

    @Override
    public String displayName() {
        return "댓글 여론 분석";
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
