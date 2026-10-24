package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.io.DefaultResourceLoader;

/** Explicit offline CLI. No Spring application, provider client, or model judgment. */
public final class CasebookIntakeAudit {
    private CasebookIntakeAudit() {}
    public static Map<String, Object> audit(Path queryPath, Path runtimePath) throws Exception {
        var json = ReviewGuidelineLibrary.JSON;
        var query = json.readTree(read(queryPath));
        byte[] runtimeBytes = read(runtimePath);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(runtimeBytes));
        if (!"casebook-intake-query-1".equals(query.path("schemaVersion").asText())
                || !sha.equals(query.path("runtimeSha256").asText())
                || !query.path("planSha256").asText().matches("[0-9a-f]{64}")
                || !query.path("unitId").asText().matches("[A-Za-z0-9_-]{1,64}(?:#[A-Za-z0-9_-]{1,64})?")
                || !query.path("raw").isArray() || query.path("raw").isEmpty() || query.path("raw").size() > 64
                || !query.path("focusIds").isArray() || query.path("focusIds").isEmpty())
            throw new IllegalArgumentException("INVALID_INTAKE_QUERY");
        var raw = new ArrayList<ReviewInput.Segment>(); var seen = new HashSet<String>(); long previousEnd = 0;
        for (var row : query.path("raw")) {
            String id = row.path("id").asText(), text = row.path("text").asText();
            if (id.isBlank() || !seen.add(id) || text.isBlank() || text.codePointCount(0, text.length()) > 350
                    || !row.path("startMs").isIntegralNumber() || !row.path("endMs").isIntegralNumber()
                    || row.path("startMs").asLong() < previousEnd || row.path("endMs").asLong() < row.path("startMs").asLong())
                throw new IllegalArgumentException("INVALID_SOURCE_WINDOW");
            raw.add(new ReviewInput.Segment(id, TimelineEventType.SPEECH, row.path("startMs").asLong(),
                    row.path("endMs").asLong(), text, null));
            previousEnd = row.path("endMs").asLong();
        }
        var focus = new ArrayList<ReviewInput.Segment>(); var focusIds = new HashSet<String>();
        for (var id : query.path("focusIds")) {
            if (!id.isString() || !focusIds.add(id.asText())) throw new IllegalArgumentException("INVALID_FOCUS");
            focus.add(raw.stream().filter(s -> s.id().equals(id.asText())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("FOCUS_OUTSIDE_RAW")));
        }
        var archive = json.readTree(runtimeBytes);
        if (!"review-guidelines-7".equals(archive.path("schemaVersion").asText()))
            throw new IllegalArgumentException("V7_REQUIRED");
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                runtimePath.toAbsolutePath().toUri().toString(), 6000, 3, 3000, "", true);
        library.load();
        var selection = library.select(TimelineEventType.SPEECH, raw, "intake-preview", focus);
        boolean retrieved = selection.trace().exampleIds().contains(query.path("unitId").asText());
        return Map.of("planSha256", query.path("planSha256").asText(), "runtimeSha256", sha,
                "unitId", query.path("unitId").asText(), "retrieved", retrieved,
                "selectedIds", selection.trace().exampleIds(), "payloadCodePoints", selection.trace().payloadCodePoints(),
                "providerCalls", 0, "modelAccuracyMeasured", false);
    }
    private static byte[] read(Path path) throws Exception {
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new IllegalArgumentException("LOCAL_FILE_TOO_LARGE");
            return bytes;
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Two local paths required");
        var result = audit(Path.of(args[0]), Path.of(args[1]));
        System.out.println("CASEBOOK_INTAKE_AUDIT=" + ReviewGuidelineLibrary.JSON.writeValueAsString(result));
        if (!Boolean.TRUE.equals(result.get("retrieved"))) throw new IllegalArgumentException("EXPECTED_REFERENCE_NOT_RETRIEVED");
    }
}
