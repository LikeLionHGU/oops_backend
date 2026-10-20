package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RuleUnionTrialPlanTest {
    byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    String normal="""
            {"conditions":[{"id":"v2","labelStatus":"USER_NORMAL_PRODUCT_REVIEW_DEVELOPMENT",
            "raw":[{"id":"normal-0","type":"SPEECH","startMs":0,"endMs":1000,"text":"맛이 좋네요","confidence":null}]}]}
            """;
    @Test void preparationFreezesInputsAndKeepsDraftsSeparate() throws Exception {
        byte[] source=bytes("{\"success\":true,\"data\":[{\"startMs\":0,\"endMs\":1000,\"text\":\"원문\"}]}");
        var p=RuleUnionTrialPlan.prepare(source,bytes(normal),bytes("{}"));
        assertEquals(16,p.engineExecutions());
        assertEquals("PREPARED_NOT_APPROVED_NOT_EXECUTED",p.state());
        assertEquals(ReferenceOnlyTrial.sha(source),p.sourceHashes().get("transcript"));
        assertEquals(4,p.conditions().size());
        assertTrue(p.conditions().subList(2,4).stream().allMatch(c -> c.labelStatus().contains("NOT_HUMAN_GOLD")));
        assertEquals(true,p.fixedSettings().get("structuredSearch"));
        assertFalse(p.implementationHashes().isEmpty());
        assertEquals("원문",p.conditions().get(0).raw().get(0).text());
    }
    @Test void malformedOrUnapprovedSourceRejected() {
        assertThrows(IllegalArgumentException.class,() -> RuleUnionTrialPlan.prepare(bytes("{}"),bytes(normal),bytes("{}")));
        assertThrows(IllegalArgumentException.class,() -> RuleUnionTrialPlan.prepare(
                bytes("{\"success\":true,\"data\":[]}"),bytes(normal.replace("USER_NORMAL_PRODUCT_REVIEW_DEVELOPMENT","DRAFT")),bytes("{}")));
    }
    @Test void draftsKeepExactSpeechAndUniqueIds() {
        var c=RuleUnionTrialPlan.draft("boundary","첫 줄","다음 줄");
        assertEquals(java.util.List.of("첫 줄","다음 줄"),c.raw().stream().map(ReviewInput.Segment::text).toList());
        assertEquals(2,c.raw().stream().map(ReviewInput.Segment::id).distinct().count());
        assertTrue(c.labelStatus().contains("NOT_PROMOTED"));
    }
}
