package com.example.oops.analyzer;

import com.example.oops.domain.EvidenceSource;
import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.TimelineEventType;
import com.example.oops.domain.TranscriptSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * STT 대본에 대한 룰 기반 1차 탐지.
 *
 * LLM 판정(SpeechReviewAnalyzer)과 역할이 다르다.
 * 여기는 개인정보 노출 후보를 API 키 없이도 잡는 안전망이고,
 * 욕설/논쟁 표현 등장은 별도 ExpressionService, 맥락 판단은 LLM이 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubtitleAnalyzer implements ContentAnalyzer {

    private final RiskRuleEngine ruleEngine;

    @Override
    public String key() {
        return "subtitle";
    }

    @Override
    public String displayName() {
        return "대본 키워드 검사";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return context.hasTranscript();
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<RiskFinding> findings = new ArrayList<>();

        for (TranscriptSegment segment : context.transcript()) {
            for (RiskRuleEngine.Hit hit : ruleEngine.detect(segment.getText())) {
                findings.add(RiskFinding.builder()
                        .video(context.video())
                        .eventType(TimelineEventType.SPEECH)
                        .category(hit.category())
                        .source(EvidenceSource.SUBTITLE)
                        .score(hit.score())
                        .startMs(segment.getStartMs())
                        .endMs(segment.getEndMs())
                        .text(segment.getText())
                        .reason(hit.reason())
                        .build());
            }
        }

        log.info("[subtitle] videoId={} segments={} findings={}",
                context.video().getId(), context.transcript().size(), findings.size());
        return findings;
    }
}
