package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** Reads saved transcripts or plan raw snapshots; stdout only, no Spring, network or DB. */
public final class MechanismShadowAudit {
    record AuditRow(String id, String labelStatus, int segments, MechanismCandidateShadow.Result shadow) {}
    static List<AuditRow> audit(JsonNode root) {
        var json = JsonMapper.builder().build();
        List<AuditRow> results = new ArrayList<>();
        if (root.path("success").asBoolean() && root.path("data").isArray()) {
            List<ReviewInput.Segment> rows = new ArrayList<>(); int i = 0;
            for (var row : root.path("data")) {
                if (!row.path("startMs").isIntegralNumber() || !row.path("endMs").isIntegralNumber() || !row.path("text").isString())
                    throw new IllegalArgumentException("Invalid transcript row");
                rows.add(new ReviewInput.Segment("stt-index-" + i++, TimelineEventType.SPEECH,
                        row.path("startMs").asLong(),row.path("endMs").asLong(),row.path("text").asText(),null));
            }
            results.add(new AuditRow("transcript", "UNSPECIFIED", rows.size(),MechanismCandidateShadow.inspect(new ReviewInput(rows))));
        } else {
            var cases = root.has("conditions") ? root.path("conditions") : root.path("cases");
            if (!cases.isArray() || cases.isEmpty() || cases.size() > 100) throw new IllegalArgumentException("Saved raw cases required");
            Set<String> ids = new HashSet<>();
            for (var item : cases) {
                if (!item.path("id").isString() || item.path("id").asText().isBlank() || !ids.add(item.path("id").asText())
                        || !item.path("raw").isArray() || item.path("raw").isEmpty()) throw new IllegalArgumentException("Unique case ID and nonempty raw required");
                // Expectations, hypotheses, references, labels and previous model responses never enter inspect().
                List<ReviewInput.Segment> segments = json.convertValue(item.path("raw"),
                        json.getTypeFactory().constructCollectionType(List.class,ReviewInput.Segment.class));
                results.add(new AuditRow(item.path("id").asText(),item.path("labelStatus").asText("UNSPECIFIED"),
                        segments.size(),MechanismCandidateShadow.inspect(new ReviewInput(segments))));
            }
        }
        return List.copyOf(results);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One local saved transcript response required");
        Path path = Path.of(args[0]);
        if (Files.size(path) > 8_000_000) throw new IllegalArgumentException("Input too large");
        var json = JsonMapper.builder().build();
        var root = json.readTree(Files.readString(path));
        System.out.println(json.writeValueAsString(Map.of("inputSha256",ReviewGuidelineLibrary.retrievalHash(Files.readString(path)),
                "idBasis","SAVED_RAW_IDS_OR_TRANSCRIPT_INDEX_NOT_CROSS_SNAPSHOT_IDENTITY","providerCalls",0,"audits",audit(root))));
    }
}
