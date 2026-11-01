package com.example.oops.fusion;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Reconstructs fusion inputs from stored observations; no provider, DB or publication. */
class SavedA02FusionReplayTest {
    @Test void sameGraphicEvidenceFromDifferentAxesReachesFusion() throws Exception {
        Path root=Path.of("../datasets/controversy"), input=root.resolve("evaluation/active-context-regression-20261018/inputs.json"),
                result=root.resolve("experiments/synthetic-regression-resume-v2-result-20261101/results.json");
        Assumptions.assumeTrue(Files.exists(input)&&Files.exists(result));var json=JsonMapper.builder().build();
        var source=json.readTree(Files.readAllBytes(input));var saved=json.readTree(Files.readAllBytes(result));
        var caseRow=java.util.stream.StreamSupport.stream(source.spliterator(),false).filter(x->x.path("caseId").asText().equals("A02")).findFirst().orElseThrow();
        var run=java.util.stream.StreamSupport.stream(saved.path("results").spliterator(),false).filter(x->x.path("caseId").asText().equals("A02")).findFirst().orElseThrow();
        Map<String,tools.jackson.databind.JsonNode> raw=new HashMap<>();caseRow.path("raw").forEach(x->raw.put(x.path("id").asText(),x));
        var findings=new ArrayList<RiskFinding>();
        for(var evaluation:run.path("evaluations"))for(var obs:evaluation.path("observations")){
            if(!obs.path("decision").asText().equals("REVIEW_REQUIRED"))continue;
            String anchor=obs.path("anchorId").asText();var segment=raw.get(anchor);assertThat(segment).isNotNull();
            var details=obs.path("details");var target=details.path("target");
            var f=RiskFinding.builder().eventType(TimelineEventType.SPEECH).category(RiskCategory.valueOf(details.path("category").asText()))
                    .source(EvidenceSource.SUBTITLE).startMs(segment.path("startMs").asLong()).endMs(segment.path("endMs").asLong())
                    .text(segment.path("text").asText()).target(target.isNull()||target.isMissingNode()?null:target.asText())
                    .score(details.path("score").asDouble()).reason(obs.path("reason").asText()).build();
            var quotes=new ArrayList<FindingSupport.Quote>();for(var e:obs.path("evidence")){
                var original=raw.get(e.path("segmentId").asText());assertThat(original).isNotNull();
                quotes.add(new FindingSupport.Quote(e.path("segmentId").asText(),TimelineEventType.SPEECH,
                        original.path("startMs").asLong(),original.path("endMs").asLong(),e.path("quote").asText(),e.path("role").asText()));}
            var type=details.path("targetGrounding").path("type");
            f.recordValidatedSupports(List.of(new FindingSupport(anchor,type.isMissingNode()||type.isNull()?null:type.asText(),quotes)));
            findings.add(f);
        }
        assertThat(findings).hasSize(2).allSatisfy(f->assertThat(f.getCategory()).isEqualTo(RiskCategory.GRAPHIC_METAPHOR));
        var fused=new FindingFusionService().fuse(findings);
        assertThat(fused).hasSize(1);assertThat(fused.get(0).getMergedCount()).isEqualTo(2);
        assertThat(fused.get(0).getOccurrenceCount()).isEqualTo(1);
    }
}
