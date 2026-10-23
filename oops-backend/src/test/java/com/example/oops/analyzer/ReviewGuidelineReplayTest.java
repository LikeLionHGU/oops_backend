package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class ReviewGuidelineReplayTest {
    @TempDir Path directory;

    @Test void entryPointStillProcessesSyntheticSavedResponsesWithoutProvider() throws Exception {
        var input = directory.resolve("input.json");
        var report = directory.resolve("report.json");
        Files.writeString(input, "{\"transcript\":[{\"startMs\":0,\"endMs\":1000,\"text\":\"합성 입력\"}]}");
        Files.writeString(report, "{\"arms\":[]}");
        assertThatCode(() -> ReviewGuidelineReplay.main(new String[]{input.toString(),
                report.toString(), directory.resolve("absent-optional-guidelines.json").toString()}))
                .doesNotThrowAnyException();
    }

    @Test void entryPointRetainsArgumentValidation() {
        assertThatThrownBy(() -> ReviewGuidelineReplay.main(new String[]{}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Three local JSON paths required");
    }
}
