package com.example.oops.analyzer;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CasebookIntakeAuditTest {
    @TempDir Path temp;
    private String sha(byte[] raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    }
    private Map<String, Object> query(String hash) {
        return new LinkedHashMap<>(Map.of("schemaVersion", "casebook-intake-query-1", "planSha256", "0".repeat(64),
                "runtimeSha256", hash, "unitId", "synthetic#example", "focusIds", List.of("s1"),
                "raw", List.of(Map.of("id", "s1", "text", "합성 원문", "startMs", 0, "endMs", 1000))));
    }
    @Test void rejectsUnpinnedOrInvalidQueriesBeforeLoadingAnyRuntime() throws Exception {
        byte[] runtime = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var runtimePath = temp.resolve("runtime.json"); Files.write(runtimePath, runtime);
        for (String change : List.of("hash", "plan", "focus", "duplicate-focus", "time", "oversize")) {
            var q = query(sha(runtime));
            if (change.equals("hash")) q.put("runtimeSha256", "0".repeat(64));
            if (change.equals("plan")) q.put("planSha256", "not-a-hash");
            if (change.equals("focus")) q.put("focusIds", List.of("outside"));
            if (change.equals("duplicate-focus")) q.put("focusIds", List.of("s1", "s1"));
            if (change.equals("time")) q.put("raw", List.of(Map.of("id", "s1", "text", "합성 원문", "startMs", 1000, "endMs", 0)));
            if (change.equals("oversize")) q.put("raw", List.of(Map.of("id", "s1", "text", "가".repeat(351), "startMs", 0, "endMs", 1000)));
            var input = temp.resolve("query.json"); Files.writeString(input, ReviewGuidelineLibrary.JSON.writeValueAsString(q));
            assertThatThrownBy(() -> CasebookIntakeAudit.audit(input, runtimePath)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void oversizedFileIsRejected() throws Exception {
        var file = temp.resolve("big.json"); Files.write(file, new byte[1_048_577]);
        assertThatThrownBy(() -> CasebookIntakeAudit.audit(file, file)).isInstanceOf(IllegalArgumentException.class);
    }
    @org.junit.jupiter.api.Tag("dataset-backed")
    @Test void realLocalBoundaryAuditUsesProductionSelectorWithoutRawOutputOrProvider() throws Exception {
        var root = Path.of("../datasets/controversy");
        var unitPath = root.resolve("context-units/podcast-introduction-banter.json");
        assumeTrue(Files.exists(unitPath));
        var unit = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(unitPath));
        var source = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(root.resolve(unit.at("/source/path").asText())));
        var runtime = root.resolve("guidelines/runtime.json");
        var q = query(sha(Files.readAllBytes(runtime)));
        q.put("unitId", unit.path("id").asText()); q.put("raw", source.path("segments"));
        q.put("focusIds", unit.at("/roles/evaluationIds"));
        var input = temp.resolve("query.json"); Files.writeString(input, ReviewGuidelineLibrary.JSON.writeValueAsString(q));
        var result = CasebookIntakeAudit.audit(input, runtime);
        assertThat(result.get("retrieved")).isEqualTo(true);
        assertThat(result.get("providerCalls")).isEqualTo(0);
        assertThat(result.get("modelAccuracyMeasured")).isEqualTo(false);
        assertThat(ReviewGuidelineLibrary.JSON.writeValueAsString(result)).doesNotContain("쭉정이", "sourceGroupId", "approvalRecord");
    }
}
