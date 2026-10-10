package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContextReferenceAuditTest {
    @TempDir Path directory;
    private String[] inputs(List<Long> times, long end) throws Exception {
        var json = ReviewGuidelineLibrary.JSON;
        Path snapshot = directory.resolve("snapshot.json"), archive = directory.resolve("archive.json");
        Files.writeString(snapshot, json.writeValueAsString(Map.of("transcript", List.of(Map.of(
                "startMs", 0, "endMs", 1500, "text", "공공시설이 없는 생활조건")), "candidateQueries", List.of(Map.of(
                "startMs", 0, "endMs", end, "focusStartMs", times)))));
        Files.writeString(archive, json.writeValueAsString(new ContextExampleSelectionTest().archive(2)));
        return new String[]{snapshot.toString(), archive.toString()};
    }
    @Test void fixedQueryUsesRawOnlyAndOutputsMetadataWithoutText() throws Exception {
        var args = inputs(List.of(0L), 1500);
        var output = new ByteArrayOutputStream(); var original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            ContextReferenceAudit.main(args);
        } finally { System.setOut(original); }
        String result = output.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("fixed-candidate", "\"providerCalls\":0", "\"modelAccuracyMeasured\":false")
                .doesNotContain("공공시설이 없는 생활조건");
    }
    @Test void duplicateOrMissingFocusAndOversizedWindowAreRejected() throws Exception {
        for (var times : List.of(List.of(0L, 0L), List.of(1000L))) {
            var args = inputs(times, 1500);
            assertThatThrownBy(() -> ContextReferenceAudit.main(args)).isInstanceOf(IllegalArgumentException.class);
        }
        var args = inputs(List.of(0L), 60001);
        assertThatThrownBy(() -> ContextReferenceAudit.main(args)).isInstanceOf(IllegalArgumentException.class);
    }
}
