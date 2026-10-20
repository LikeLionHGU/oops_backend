package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

/** Reads an existing transcript API response; stdout only, no Spring, network or DB. */
public final class MechanismShadowAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One local saved transcript response required");
        Path path = Path.of(args[0]);
        if (Files.size(path) > 8_000_000) throw new IllegalArgumentException("Input too large");
        var json = JsonMapper.builder().build();
        var root = json.readTree(Files.readString(path));
        if (!root.path("success").asBoolean() || !root.path("data").isArray()) throw new IllegalArgumentException("Transcript response required");
        List<ReviewInput.Segment> rows = new ArrayList<>(); int i = 0;
        for (var row : root.path("data")) {
            if (!row.path("startMs").isIntegralNumber() || !row.path("endMs").isIntegralNumber() || !row.path("text").isString())
                throw new IllegalArgumentException("Invalid transcript row");
            rows.add(new ReviewInput.Segment("stt-index-" + i++, TimelineEventType.SPEECH,
                    row.path("startMs").asLong(),row.path("endMs").asLong(),row.path("text").asText(),null));
        }
        System.out.println(json.writeValueAsString(Map.of("inputSha256",ReviewGuidelineLibrary.retrievalHash(Files.readString(path)),
                "idBasis","SNAPSHOT_INDEX_NOT_DATABASE_ID","providerCalls",0,"shadow",MechanismCandidateShadow.inspect(new ReviewInput(rows)))));
    }
}
