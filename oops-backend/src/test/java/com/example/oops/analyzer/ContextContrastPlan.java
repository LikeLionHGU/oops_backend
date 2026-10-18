package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

/** Preparation only: deliberately has no HTTP client, credential access or execution mode. */
public final class ContextContrastPlan {
    private ContextContrastPlan() {}
    static JsonNode build() throws Exception {
        var fixture = ContextContentContrastTest.fixture();
        var p = JSON.createObjectNode().put("version", "context-contrast-baseline-1")
                .put("fixtureSha256", SourceFirstReviewBoundary.hash(fixture.toString()))
                .put("contractVersion", SourceFirstJudgmentContract.VERSION)
                .put("approvalStatus", "AWAITING_NEW_BUDGET_AND_TEXT_CONSENT")
                .put("maxHttpAttempts", 12).put("retryAllowed", false)
                .put("runtimePublicationAuthorized", false).put("independentGold", false)
                .put("timestampMeaning", "SYNTHETIC_ORDER_ONLY_NOT_MEDIA_ALIGNMENT");
        var requests = p.putArray("requests"); double reserve = 0;
        for (int repeat = 1; repeat <= 3; repeat++) {
            var order = new ArrayList<JsonNode>(); fixture.path("cases").forEach(order::add);
            Collections.rotate(order, repeat - 1);
            for (var item : order) {
                // Last turn is the neutral focus for these two-turn contrasts, not a risk label.
                var source = JSON.createObjectNode().put("anchorId", "s1");
                var raw = source.putArray("raw"); int i = 0;
                for (var line : item.path("dialogue")) {
                    raw.addObject().put("id", line.path("id").asText()).put("text", line.path("text").asText())
                            .put("startMs", i * 1000).put("endMs", ++i * 1000);
                }
                source.putNull("sourceReading");
                var body = SourceFirstJudgmentTrial.body(source);
                var row = requests.addObject().put("id", item.path("id").asText() + "-" + repeat)
                        .put("caseId", item.path("id").asText()).put("repeat", repeat);
                row.set("input", source); row.set("body", body);
                // Local evaluation metadata only; never part of the provider body.
                row.set("localContextLabel", item.path("context").deepCopy());
                reserve += SourceFirstJudgmentTrial.reserve(body);
            }
        }
        p.put("reservationEstimatedUsd", reserve);
        p.put("estimateBasis", "Configured USD per 1M tokens: input .10/output .50; UTF8 bytes+1024 and output cap2048 per request; not billing guarantee");
        return p;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("New private plan file required; preparation only");
        var bytes = JSON.writeValueAsBytes(build());
        SourceRelationExtractionTrial.writePrivate(Path.of(args[0]), bytes);
        System.out.println("Prepared 12 requests; no HTTP calls. Plan SHA-256: " + ReferenceOnlyTrial.sha(bytes));
    }
}
