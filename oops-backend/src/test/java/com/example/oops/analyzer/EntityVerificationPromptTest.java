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
