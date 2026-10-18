package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Fixture integrity and evaluation boundaries only; no model or runtime integration. */
class ContextContentContrastTest {
    static JsonNode fixture() throws Exception {
        try (var in = ContextContentContrastTest.class.getResourceAsStream("/evals/context-content-contrasts-v1.json")) {
            if (in == null) throw new IllegalStateException("Missing approved contrast fixture");
            return JSON.readTree(in.readAllBytes());
        }
    }
    static JsonNode sourceOnly(JsonNode item) {
        return JSON.createObjectNode().set("dialogue", item.path("dialogue").deepCopy());
    }
    @Test void preservesExactApprovedDialogueAndDecisions() throws Exception {
        var f = fixture();
        var expected = List.of(
                List.of("이 음식은 할머니가 해주시던 맛이에요.", "어릴 때 먹던 게 떠오르네요."),
                List.of("이 음식 맛을 ‘할머니 살을 뜯는 것 같다’고 표현하고 싶어요.", "그 비유가 딱 맞네요."),
                List.of("‘할머니 살을 뜯는 것 같다’는 음식 비유는 쓰지 맙시다.", "추억의 맛이라는 표현으로 바꾸죠."),
                List.of("그 사람이 ‘할머니가 해주시던 맛’이라고 했어요.", "그 말을 한 사람은 머리가 빈 사람이죠."));
        var decisions = List.of("PASS", "REVIEW_REQUIRED", "PASS", "REVIEW_REQUIRED");
        assertThat(f.path("cases").size()).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            var c = f.path("cases").get(i);
            assertThat(c.path("id").asText()).isEqualTo("A0" + (i + 1));
            assertThat(c.at("/context/status").asText()).isEqualTo("USER_APPROVED");
            assertThat(c.at("/context/expectedDecision").asText()).isEqualTo(decisions.get(i));
            assertThat(c.path("dialogue").size()).isEqualTo(2);
            for (int j = 0; j < 2; j++) {
                assertThat(c.path("dialogue").get(j).path("id").asText()).isEqualTo("s" + j);
                assertThat(c.path("dialogue").get(j).path("text").asText()).isEqualTo(expected.get(i).get(j));
            }
        }
    }
    @Test void advisoryDraftsDoNotBecomeApprovedLabelsOrRuntimeRules() throws Exception {
        var f = fixture();
        assertThat(f.path("runtimeEnabled").asBoolean()).isFalse();
        assertThat(f.path("independentGold").asBoolean()).isFalse();
        assertThat(f.path("sourceType").asText()).isEqualTo("SYNTHETIC");
        assertThat(f.at("/approval/scope").asText()).isEqualTo("CONTEXT_DECISION_ONLY");
        assertThat(f.at("/approval/date").asText()).isEqualTo("2026-10-18");
        for (var c : f.path("cases")) {
            assertThat(c.at("/advisory/status").asText()).isEqualTo("DRAFT_UNAPPROVED");
            assertThat(c.at("/advisory/expectedDecision").isNull()).isTrue();
        }
    }
    @Test void futureSourcePayloadExcludesLabelsNotesAndDraftTags() throws Exception {
        for (var c : fixture().path("cases")) {
            var source = sourceOnly(c);
            assertThat(source.size()).isEqualTo(1);
            assertThat(source.path("dialogue")).isEqualTo(c.path("dialogue"));
            assertThat(source.toString()).doesNotContain("expectedDecision", "reviewerNotes", "proposedTags", "USER_APPROVED");
            // A mutable preparation copy must not overwrite the approved fixture.
            ((tools.jackson.databind.node.ObjectNode) source.path("dialogue").get(0)).put("text", "changed copy");
            assertThat(c.path("dialogue").get(0).path("text").asText()).isNotEqualTo("changed copy");
        }
    }
}
