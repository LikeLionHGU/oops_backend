package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.oops.analyzer.ReviewEvaluation.*;

class CrossOriginFindingGateTest {
    Observation observation(String anchor,String category,String target,List<EvidenceSpan> evidence) {
        return new Observation(anchor,Decision.REVIEW_REQUIRED,"근거 있는 검토 설명",evidence,List.of(),
                new Details(category,target,.5,null,null,null,"정상 해석도 대조했습니다"));
    }
    List<EvidenceSpan> evidence=List.of(new EvidenceSpan("a","원문",0,2,EvidenceRole.PRIMARY));
    @Test void suppressesOnlyOppositeOriginAndRetainsProvenance() {
        var gate=new CrossOriginFindingGate();var o=observation("a","GRAPHIC_METAPHOR",null,evidence);
        assertTrue(gate.publish("ai1",false,"EXPRESSION_CONTENT",o));
        assertTrue(gate.publish("ai2",false,"EXPRESSION_CONTENT",o));
        assertFalse(gate.publish("rule1",true,"EXPRESSION_CONTENT",o));
        assertEquals(List.of(new CrossOriginFindingGate.Suppressed("rule1","ai1")),gate.suppressed());
        var reverse=new CrossOriginFindingGate();
        assertTrue(reverse.publish("rule",true,"EXPRESSION_CONTENT",o));
        assertFalse(reverse.publish("ai",false,"EXPRESSION_CONTENT",o));
    }
    @Test void differencesInIssueOrEvidenceAreNeverSilentlyMerged() {
        var gate=new CrossOriginFindingGate();
        assertTrue(gate.publish("ai",false,"EXPRESSION_CONTENT",observation("a","GRAPHIC_METAPHOR",null,evidence)));
        assertTrue(gate.publish("rule-axis",true,"TARGET_TREATMENT",observation("a","GRAPHIC_METAPHOR",null,evidence)));
        assertTrue(gate.publish("rule-target",true,"EXPRESSION_CONTENT",observation("a","GRAPHIC_METAPHOR","다른 대상",evidence)));
        assertTrue(gate.publish("rule-category",true,"EXPRESSION_CONTENT",observation("a","MOCKERY",null,evidence)));
        assertTrue(gate.publish("rule-anchor",true,"EXPRESSION_CONTENT",observation("b","GRAPHIC_METAPHOR",null,evidence)));
        assertTrue(gate.publish("rule-evidence",true,"EXPRESSION_CONTENT",observation("a","GRAPHIC_METAPHOR",null,
                List.of(new EvidenceSpan("a","원문",0,2,EvidenceRole.PRIMARY),new EvidenceSpan("b","별도 문맥",0,5,EvidenceRole.CONTEXT)))));
        assertTrue(gate.suppressed().isEmpty());
    }
}
