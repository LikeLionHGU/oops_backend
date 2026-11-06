package com.example.oops.analyzer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

/**
 * Evidence that the budget counter matches gpt-6-luna billing tokenization: stored 2026-10-18
 * chat requests (two messages, json_object) report prompt_tokens equal to o200k_base content
 * tokens plus a constant 10 framing tokens. Private stored run; skipped when absent.
 */
class TokenizerAgreementTest {
    private static final Path RUN = Path.of("../datasets/controversy/experiments/candidate-issue-scope-comparison-results-20261018");
    private static final int TWO_MESSAGE_FRAMING_TOKENS = 10;

    @Test void storedGpt6LunaPromptTokensEqualO200kContentPlusFraming() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(RUN.resolve("plan.json")), "Private stored run not present");
        var json = JsonMapper.builder().build();
        var plan = json.readTree(Files.readAllBytes(RUN.resolve("plan.json")));
        var results = json.readTree(Files.readAllBytes(RUN.resolve("results.json"))).path("results");
        var o200k = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        assertThat(ContextTokenBudget.COUNTER_ID).endsWith("o200k_base");
        int checked = 0;
        for (var r : results) {
            var body = plan.path("requestBodies").get(r.path("requestIndex").asInt());
            assertThat(body.path("model").asText()).isEqualTo("gpt-6-luna");
            assertThat(body.path("messages")).hasSize(2);
            int content = 0;
            for (var m : body.path("messages")) content += o200k.countTokensOrdinary(m.path("content").asText());
            var response = r.path("providerResponse");
            assertThat(response.path("model").asText()).isEqualTo("gpt-6-luna");
            assertThat(response.path("usage").path("prompt_tokens").asLong()).isEqualTo(content + TWO_MESSAGE_FRAMING_TOKENS);
            checked++;
        }
        assertThat(checked).isEqualTo(30);
    }
}
