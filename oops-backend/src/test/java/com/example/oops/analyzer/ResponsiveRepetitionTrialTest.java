package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

class ResponsiveRepetitionTrialTest {
    @TempDir Path temp;
    @Test void inputPreservesTextTimesAndHasNoLabelsOrImaginedSpeakers() {
        var raw=ResponsiveRepetitionTrial.synthetic(List.of("あ", "무슨 싸구려야. 선물 받은 물건이야.","미안해요"));
        var input=JSON.readTree(ResponsiveRepetitionTrial.input(raw,1,List.of(0,1,2)));
        assertThat(input.path("raw").toString()).isEqualTo(JSON.writeValueAsString(raw));
        assertThat(input.at("/candidates/0/anchorId").asText()).isEqualTo("stt-index-1");
        assertThat(input.has("expectedDecision")).isFalse();assertThat(input.toString()).doesNotContain("speakerId","USER_APPROVED");
    }
    @Test void budgetCountsAttemptsAndStopsBeforeNinthOrOverCap() throws Exception {
        var budget=new TextBoundaryTrial.Budget(.25,.10,.50,temp);
        for(int i=0;i<8;i++) ResponsiveRepetitionTrial.reserve(budget,1000);
        assertThat(budget.attempts).isEqualTo(8);
        assertThatThrownBy(()->ResponsiveRepetitionTrial.reserve(budget,1000)).hasMessageContaining("Eight");
        assertThat(budget.attempts).isEqualTo(8);
        var tiny=new TextBoundaryTrial.Budget(.001,.10,.50,temp);
        assertThatThrownBy(()->ResponsiveRepetitionTrial.reserve(tiny,1000)).hasMessageContaining("exhausted");
        assertThat(tiny.attempts).isZero();
    }
    @Test void frozenGuardRejectsModifiedBodyAndRepeatedAttempt() {
        var props=new com.example.oops.config.OpenAiProperties("offline",null,null,"https://api.openai.com/v1","gpt-6-luna","none",java.time.Duration.ofSeconds(1),null);
        var client=new com.example.oops.client.OpenAiClient(org.springframework.web.client.RestClient.create(),props);
        byte[] body=JSON.writeValueAsBytes(client.replayRequestBody("system","{}",CandidateReviewEngine.VerificationResult.class));
        var guard=new DefaultVerificationTrial.RequestGuard();guard.begin(body);
        var altered=(tools.jackson.databind.node.ObjectNode)JSON.readTree(body);altered.put("model","other-model");
        assertThatThrownBy(()->guard.accept(JSON.writeValueAsBytes(altered),DefaultVerificationTrial.ENDPOINT)).isInstanceOf(IllegalArgumentException.class);
        guard.accept(body,DefaultVerificationTrial.ENDPOINT);
        assertThatThrownBy(()->guard.accept(body,DefaultVerificationTrial.ENDPOINT)).hasMessageContaining("extra HTTP");
    }
    @Test void rejectsWrongSourceAndUnapprovedPlanShape() {
        assertThatThrownBy(()->ResponsiveRepetitionTrial.raw(JSON.readTree("{\"success\":true,\"data\":[]}"))).isInstanceOf(IllegalArgumentException.class);
        var plan=new ResponsiveRepetitionTrial.Plan(ResponsiveRepetitionTrial.VERSION,"gpt-6-luna",.25,9,"a","b",Map.of(),List.of(),.04);
        assertThatThrownBy(()->ResponsiveRepetitionTrial.validate(plan)).hasMessageContaining("eight-call");
    }
}
