package com.example.oops.fusion;

import com.example.oops.domain.*;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Offline final-card evidence replay. Original pre-fusion scores/supports are unavailable.
 * No Spring context, provider client, DB writes or publication.
 */
public final class GraphicFusionReportReplay {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Saved private report file required");
        var json = JsonMapper.builder().build();
        for (String path : args) {
            var events = json.readTree(Files.readAllBytes(Path.of(path))).path("data").path("events");
            var input = new ArrayList<RiskFinding>();
            for (var event : events) {
                var quotes = new ArrayList<FindingSupport.Quote>();
                event.path("relatedEvidence").forEach(q -> quotes.add(json.treeToValue(q, FindingSupport.Quote.class)));
                var anchors = quotes.stream().filter(q -> "PRIMARY".equals(q.role())).map(FindingSupport.Quote::segmentId).distinct().toList();
                var category = RiskCategory.valueOf(event.path("riskTypes").get(0).asText());
                if (anchors.size() != 1 && !(category == RiskCategory.GRAPHIC_METAPHOR && anchors.size() == 2 && quotes.size() == 4))
                    throw new IllegalArgumentException("Only single anchors or stored reciprocal graphic pair supported");
                for (String anchor : anchors) {
                    var selected = anchors.size() == 1 ? quotes : quotes.stream().filter(q ->
                            ("PRIMARY".equals(q.role()) && anchor.equals(q.segmentId()))
                                    || ("CONTEXT".equals(q.role()) && !anchor.equals(q.segmentId()))).toList();
                    var primary = selected.stream().filter(q -> "PRIMARY".equals(q.role())).findFirst().orElseThrow();
                    var f = RiskFinding.builder().eventType(TimelineEventType.valueOf(event.path("type").asText()))
                            .category(category).source(EvidenceSource.SUBTITLE).score(.5) // Neutral placeholder, not original score.
                            .startMs(primary.startMs()).endMs(primary.endMs())
                            .text(primary.quote()).reason(event.path("reason").asText()).build();
                    f.recordValidatedSupports(List.of(new FindingSupport(anchor, null, selected)));
                    input.add(f);
                }
            }
            var fused = new FindingFusionService().fuse(input);
            var graphic = fused.stream().filter(f -> f.getCategory() == RiskCategory.GRAPHIC_METAPHOR).toList();
            if (graphic.size() != 1 || graphic.get(0).getOccurrenceCount() != 1
                    || graphic.get(0).validatedSupports().size() != 2)
                throw new IllegalStateException("Expected stored reciprocal pair to become one flow with two retained card supports");
            System.out.println(json.writeValueAsString(Map.of("reportFile", Path.of(path).getFileName().toString(),
                    "videoId", json.readTree(Files.readAllBytes(Path.of(path))).at("/data/videoId").asText(),
                    "beforeCards", events.size(), "afterCards", fused.size(), "graphicCards", graphic.size(),
                    "graphicOccurrences", graphic.get(0).getOccurrenceCount(), "providerCalls", 0,
                    "graphicDisplayText", graphic.get(0).getText(), "graphicDisplayStartMs", graphic.get(0).representativeStartMs(),
                    "scope", "FINAL_CARD_EVIDENCE_STRUCTURE_NOT_ORIGINAL_PREFUSION_SCORES_OR_SUPPORT_COUNTS")));
        }
    }
}
