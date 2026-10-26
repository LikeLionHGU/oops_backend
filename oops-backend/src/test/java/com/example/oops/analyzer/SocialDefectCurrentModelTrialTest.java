package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.Tag("dataset-backed")
class SocialDefectCurrentModelTrialTest {
    static final String PLAN = "../datasets/controversy/experiments/social-defect-current-model-plan-20261026-v1/plan.json";
    static final String PLAN_SHA = "e6ec4e09cbdbc0abbc124e05faf1d3f11c458d4853b846fe2b05482595c6ac22";

    @Test void frozenPlanLoadsExactApprovedPositiveAndNormalInputsOffline() throws Exception {
        var cases = SocialDefectCurrentModelTrial.loadCases(Path.of(PLAN));
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).id()).isEqualTo("podcast-social-defect-positive");
        assertThat(cases.get(0).expected()).isEqualTo("REVIEW_REQUIRED");
        assertThat(cases.get(0).sourceIds()).containsExactly("stt-index-454", "stt-index-455", "stt-index-456",
                "stt-index-457", "stt-index-458", "stt-index-459", "stt-index-461", "stt-index-462");
        assertThat(cases.get(1).id()).isEqualTo("social-defect-synthetic-normal-control");
        assertThat(cases.get(1).expected()).isEqualTo("PASS");
        assertThat(cases.get(1).review().segments()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("synthetic-1");
            assertThat(s.text()).isEqualTo("요즘 책임질 일이 늘어서 친구를 자주 못 만나요. 사람마다 관계의 폭과 원하는 만남 횟수는 다를 수 있어요.");
        });
    }

    @Test void preflightLocksRuntimeRetrievalInputsAndNoProviderCalls() throws Exception {
        var manifest = SocialDefectCurrentModelTrial.preflight(Path.of(PLAN), PLAN_SHA);
        assertThat(manifest.path("providerCalls").asInt()).isZero();
        assertThat(manifest.path("cases").size()).isEqualTo(2);
        assertThat(manifest.at("/cases/0/retrievedExampleIds").toString())
                .contains("podcast-dev#podcast-social-defect");
        assertThat(manifest.path("analyses").asInt()).isEqualTo(6);
        assertThat(manifest.path("maxHttpAttempts").asInt()).isEqualTo(24);
    }

    @Test void reservationMatchesApprovedHardCapWithNoCacheDiscount() {
        assertThat(SocialDefectCurrentModelTrial.reservation(24, 120_000))
                .isCloseTo(0.3864576, within(1e-12));
        assertThat(SocialDefectCurrentModelTrial.reservation(24, 120_000))
                .isLessThan(SocialDefectCurrentModelTrial.CAP_USD);
        assertThat(SocialDefectCurrentModelTrial.MAX_HTTP_PER_ANALYSIS).isEqualTo(4);
    }

    @Test void egressGuardRejectsLabelsMediaWrongEndpointOversizeAndRetry(@TempDir Path dir) throws Exception {
        var props = new OpenAiProperties("offline", null, null, "https://api.openai.com/v1",
                "gpt-6-luna", "none", Duration.ofSeconds(1), null);
        var client = new OpenAiClient(RestClient.create(), props, true);
        String raw = "{\"raw\":[{\"id\":\"synthetic-1\",\"text\":\"정상 대조 텍스트\"}]}";
        byte[] body = JSON.writeValueAsBytes(client.replayRequestBody("fixed system", raw, CandidateReviewEngine.VerificationResult.class));
        var guard = new SocialDefectCurrentModelTrial.Guard(dir);
        guard.beginAnalysis();
        byte[] bounded = guard.accept(body, SocialDefectCurrentModelTrial.ENDPOINT);
        assertThat(bounded).isNotEmpty();
        assertThat(JSON.readTree(bounded).path("max_completion_tokens").asInt())
                .isEqualTo(SocialDefectCurrentModelTrial.MAX_OUTPUT_TOKENS);
        assertThatThrownBy(() -> guard.accept(body, SocialDefectCurrentModelTrial.ENDPOINT))
                .hasMessageContaining("Duplicate request");
        assertThatThrownBy(() -> guard.accept(body, "https://example.com/chat/completions"))
                .hasMessageContaining("endpoint");

        String leaked = "{\"expectedDecision\":\"PASS\",\"raw\":[]}";
        byte[] leakedBody = JSON.writeValueAsBytes(client.replayRequestBody("fixed system", leaked, CandidateReviewEngine.VerificationResult.class));
        guard.beginAnalysis();
        assertThatThrownBy(() -> guard.accept(leakedBody, SocialDefectCurrentModelTrial.ENDPOINT))
                .hasMessageContaining("label/approval");

        var media = JSON.readTree(body);
        var user = (tools.jackson.databind.node.ObjectNode) media.path("messages").get(1);
        user.set("content", JSON.readTree("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,AA==\"}}]"));
        guard.beginAnalysis();
        assertThatThrownBy(() -> guard.accept(JSON.writeValueAsBytes(media), SocialDefectCurrentModelTrial.ENDPOINT))
                .hasMessageContaining("Multimodal");

        guard.beginAnalysis();
        assertThatThrownBy(() -> guard.accept(new byte[SocialDefectCurrentModelTrial.MAX_BODY_BYTES + 1],
                SocialDefectCurrentModelTrial.ENDPOINT)).hasMessageContaining("body cap");

        guard.beginAnalysis();
        guard.totalAttempts = SocialDefectCurrentModelTrial.MAX_HTTP_TOTAL;
        assertThatThrownBy(() -> guard.accept(body, SocialDefectCurrentModelTrial.ENDPOINT))
                .hasMessageContaining("attempt cap");
    }
}
