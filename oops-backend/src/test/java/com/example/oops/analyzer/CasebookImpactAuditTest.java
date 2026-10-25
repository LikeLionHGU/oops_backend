package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CasebookImpactAuditTest {
    @TempDir Path temp;

    @Test void rejectsUnpinnedRequestBeforeTryingToLoadRuntime() throws Exception {
        Path request = temp.resolve("request.json");
        Path baseline = temp.resolve("baseline.json");
        Path candidate = temp.resolve("candidate.json");
        Files.writeString(request, ReviewGuidelineLibrary.JSON.writeValueAsString(Map.of("schemaVersion", "wrong")));
        Files.writeString(baseline, "{}");
        Files.writeString(candidate, "{}");
        assertThatThrownBy(() -> CasebookImpactAudit.audit(request, baseline, candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_IMPACT_REQUEST");
    }
}
