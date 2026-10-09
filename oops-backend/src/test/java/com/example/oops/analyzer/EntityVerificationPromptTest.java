package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.news.NewsSearchClient;
import com.example.oops.news.SourceClassifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EntityVerificationPromptTest {
    @Test void verificationReceivesQualificationAndUnconvertedTimeReference() throws Exception {
        var client = mock(OpenAiClient.class);
        when(client.completeAsJson(anyString(), anyString(), eq(EntityCheckAnalyzer.Verdict.class)))
                .thenReturn(Optional.empty());
        var analyzer = new EntityCheckAnalyzer(client, List.of(), mock(SourceClassifier.class));
        var method = EntityCheckAnalyzer.class.getDeclaredMethod("verify", String.class,
                EntityCheckAnalyzer.Claim.class, List.class, String.class);
        method.setAccessible(true);
        String raw = "[0] 이 회사 이야기야\n[1] 아마 3년 전에 설립됐을 거야";
        var claim = new EntityCheckAnalyzer.Claim(1, "이 회사는 아마 3년 전에 설립됐을 거야", "이 회사",
                "DATE", List.of("회사 설립 연도"), List.of(
                        new EntityCheckAnalyzer.ClaimEvidence(0, "이 회사"),
                        new EntityCheckAnalyzer.ClaimEvidence(1, "아마 3년 전에 설립됐을 거야")),
                "QUALIFIED", "3년 전", "공개 설립 이력 확인");
        method.invoke(analyzer, "2026-10-08", claim, List.of(), raw);
        var input = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(anyString(), input.capture(), eq(EntityCheckAnalyzer.Verdict.class));
        assertThat(input.getValue()).contains(
                "원문 사용 방식: QUALIFIED", "원문 시간 표현: 3년 전",
                "상대 시점을 오늘로 환산하거나 추측/전언을 단정으로 바꾸지 마라",
                JsonMapper.builder().build().writeValueAsString(raw)).doesNotContain("2023년");
    }

    @Test void verificationSeparatesExactRawTextFromExtractedSummary() throws Exception {
        var client = mock(OpenAiClient.class);
        when(client.completeAsJson(anyString(), anyString(), eq(EntityCheckAnalyzer.Verdict.class)))
                .thenReturn(Optional.empty());
        var analyzer = new EntityCheckAnalyzer(client, List.of(mock(NewsSearchClient.class)), mock(SourceClassifier.class));
        var method = EntityCheckAnalyzer.class.getDeclaredMethod("verify", String.class,
                EntityCheckAnalyzer.Claim.class, List.class, String.class);
        method.setAccessible(true);
        String raw = "그때는 \"아마\" 열 명이었다고 했어요.\n확실한 숫자는 몰라요.";
        var claim = new EntityCheckAnalyzer.Claim(0, "정확히 열 명이었다", "행사", "NUMBER", List.of());
        method.invoke(analyzer, "2026-10-08", claim, List.of(), raw);
        var input = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(anyString(), input.capture(), eq(EntityCheckAnalyzer.Verdict.class));
        assertThat(input.getValue())
                .contains("실제 영상 원문(JSON): " + JsonMapper.builder().build().writeValueAsString(raw))
                .contains("추출 모델의 주장 요약(원문 아님): 정확히 열 명이었다");
    }
}
