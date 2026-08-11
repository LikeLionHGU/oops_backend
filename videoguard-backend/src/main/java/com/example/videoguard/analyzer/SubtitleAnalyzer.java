package com.example.videoguard.analyzer;

import com.example.videoguard.domain.EvidenceSource;
import com.example.videoguard.domain.RiskFinding;
import com.example.videoguard.domain.TimelineEventType;
import com.example.videoguard.domain.TranscriptSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * STT 대본에 대한 룰 기반 1차 탐지.
 *
 * LLM 판정(SpeechRiskAnalyzer)과 역할이 다르다.
 * 여기는 욕설·개인정보처럼 확실한 것을 API 키 없이도 잡는 안전망이고,
 * LLM 은 문맥이 필요한 조롱/일반화를 맡는다. 겹치는 건 병합 단계에서 정리된다.
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
