package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

class RuleUnionSavedReplayTest {
    RuleUnionSavedReplay.Tape tape() {
        return new RuleUnionSavedReplay.Tape(JSON.readTree("""
                [{"kind":"Discovery","system":"fixed","input":{"a":1,"b":2},"output":{"candidates":[]},"failureCode":null}]
                """));
    }
    @Test void matchesExactSystemAndInputTreeOnlyAndConsumesOnce() {
        var t=tape();
        assertTrue(t.take("other","{\"a\":1,\"b\":2}",CandidateReviewEngine.Discovery.class).isEmpty());
        assertTrue(t.take("fixed","{\"a\":3,\"b\":2}",CandidateReviewEngine.Discovery.class).isEmpty());
        assertTrue(t.take("fixed","{\"b\":2,\"a\":1}",CandidateReviewEngine.Discovery.class).isPresent());
        assertTrue(t.take("fixed","{\"a\":1,\"b\":2}",CandidateReviewEngine.Discovery.class).isEmpty());
        assertEquals(1,t.consumed);assertEquals(3,t.missing);
    }
    @Test void unknownRequestThroughMockCannotMakeNetworkCall() {
        var t=tape();var client=t.client();
        assertTrue(client.completeAsJson("changed","{}",CandidateReviewEngine.Discovery.class).isEmpty());
        assertEquals(1,t.missing);assertEquals(0,t.consumed);
    }
    @Test void onlyReferenceObjectKeyOrderMayDifferInSystem() {
        assertEquals(RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":1,\"b\":[2,3]}"),
                RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"b\":[2,3],\"a\":1}"));
        assertNotEquals(RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":1,\"b\":[2,3]}"),
                RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":1,\"b\":[3,2]}"));
        assertNotEquals(RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":1}"),
                RuleUnionSavedReplay.systemKey("changed\ncontextReference={\"a\":1}"));
        assertNotEquals(RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":1}"),
                RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":2}"));
        assertNotEquals(RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":1}\n# rule A"),
                RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":1}\n# rule B"));
        assertEquals(RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"text\":\"brace } and \\\" quote\",\"a\":1}\n# rule"),
                RuleUnionSavedReplay.systemKey("fixed\ncontextReference={\"a\":1,\"text\":\"brace } and \\\" quote\"}\n# rule"));
    }
}
