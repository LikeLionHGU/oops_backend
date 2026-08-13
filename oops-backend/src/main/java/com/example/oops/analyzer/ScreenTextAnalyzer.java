package com.example.oops.analyzer;

import com.example.oops.domain.EvidenceSource;
import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.ScreenText;
import com.example.oops.domain.TimelineEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR 로 읽은 화면 텍스트 자체에 문제가 있는지 본다.
 * 발언에는 없고 편집 자막에만 들어간 욕설/개인정보가 여기서 잡힌다.
 * 룰 엔진은 SubtitleAnalyzer 것을 그대로 재사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenTextAnalyzer implements ContentAnalyzer {

    private final RiskRuleEngine ruleEngine;

    @Override
    public String key() {
        return "screen-text";
    }

    @Override
    public String displayName() {
        return "화면 자막 검사";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return context.hasScreenText();
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<RiskFinding> findings = new ArrayList<>();

        for (ScreenText screenText : context.screenTexts()) {
            for (RiskRuleEngine.Hit hit : ruleEngine.detect(screenText.getText())) {
                findings.add(RiskFinding.builder()
                        .video(context.video())
                        .eventType(TimelineEventType.CAPTION)
                        .category(hit.category())
                        .source(EvidenceSource.VISION)
                        .score(hit.score())
                        .startMs(screenText.getStartMs())
                        .endMs(screenText.getEndMs())
                        .captionText(screenText.getText())
                        .frame(screenText.getFrame())
                        .reason("화면 자막: " + hit.reason())
                        .build());
            }
        }

        log.info("[screen-text] videoId={} findings={}", context.video().getId(), findings.size());
        return findings;
    }
}
