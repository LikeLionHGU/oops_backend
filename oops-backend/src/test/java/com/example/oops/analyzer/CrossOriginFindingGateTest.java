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
        assertTrue(gate.publish("rule-target",true,"EXPRESSION_CONTENT",observation("a","GRAPHIC_METAPHOR","다른 대상",evidence)));
        assertTrue(gate.publish("rule-category",true,"EXPRESSION_CONTENT",observation("a","MOCKERY",null,evidence)));
        assertTrue(gate.publish("rule-anchor",true,"EXPRESSION_CONTENT",observation("b","GRAPHIC_METAPHOR",null,evidence)));
        assertTrue(gate.publish("rule-evidence",true,"EXPRESSION_CONTENT",observation("a","GRAPHIC_METAPHOR",null,
                List.of(new EvidenceSpan("a","원문",0,2,EvidenceRole.PRIMARY),new EvidenceSpan("b","별도 문맥",0,5,EvidenceRole.CONTEXT)))));
        assertTrue(gate.suppressed().isEmpty());
    }
    @Test void finalTargetlessGraphicIssueIgnoresDiscoveryAxisAndRedundantPrimaryContextOnly() {
        var gate=new CrossOriginFindingGate();
        var context=new EvidenceSpan("b","주변 맥락",0,5,EvidenceRole.CONTEXT);
        var ai=List.of(evidence.get(0),new EvidenceSpan("a","원문",0,2,EvidenceRole.CONTEXT),context);
        assertTrue(gate.publish("ai",false,"TARGET_TREATMENT",observation("a","GRAPHIC_METAPHOR",null,ai)));
        assertFalse(gate.publish("rule",true,"EXPRESSION_CONTENT",observation("a","GRAPHIC_METAPHOR",null,List.of(context,evidence.get(0)))));
        assertEquals("ai",gate.retainedCandidateId("rule"));
        assertEquals(3,ai.size()); // Key normalization never mutates the source inventory.
    }
    @Test void nonGraphicOrTargetedIssueStillRequiresSameDiscoveryAxis() {
        for(var o:List.of(observation("a","MOCKERY",null,evidence),observation("a","GRAPHIC_METAPHOR","대상",evidence))) {
            var gate=new CrossOriginFindingGate();
            assertTrue(gate.publish("ai",false,"TARGET_TREATMENT",o));
            assertTrue(gate.publish("rule",true,"EXPRESSION_CONTENT",o));
            assertTrue(gate.suppressed().isEmpty());
        }
    }
    @Test void differentQuotesOffsetsAndRolesAreNotRedundantContext() {
        for(var extra:List.of(new EvidenceSpan("a","다름",0,2,EvidenceRole.CONTEXT),
                new EvidenceSpan("a","원문",2,4,EvidenceRole.CONTEXT),new EvidenceSpan("a","원문",0,2,EvidenceRole.TARGET))) {
            var gate=new CrossOriginFindingGate();
            assertTrue(gate.publish("ai",false,"TARGET_TREATMENT",observation("a","GRAPHIC_METAPHOR",null,List.of(evidence.get(0),extra))));
            assertTrue(gate.publish("rule",true,"EXPRESSION_CONTENT",observation("a","GRAPHIC_METAPHOR",null,evidence)));
        }
    }
}
