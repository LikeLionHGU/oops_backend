package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** The A0-T context per label target, for deriving Near; offline, no provider calls. */
class A0ProvidedContextTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String[] TEXTS = {"요즘 신입들은 끈기가 없어", "그건 너무 일반화야", "점심 메뉴 이야기", "아까 신입 얘기는 내가 틀렸어"};
    private static final long[] STARTS = {0, 2_000, 60_000, 200_000};

    private Path manifest(Path dir, List<String> targets) throws Exception {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 0; i < TEXTS.length; i++)
            segments.add(Map.of("id", "stt-index-" + i, "startMs", STARTS[i], "endMs", STARTS[i] + 1500, "text", TEXTS[i]));
        Files.writeString(dir.resolve("video-1.json"), JSON.writeValueAsString(Map.of("segments", segments)));
        Path path = dir.resolve("manifest.json");
        Files.writeString(path, JSON.writeValueAsString(Map.of("labelVersion", "dev-v0", "videos", List.of(Map.of(
                "videoId", 1, "clusterId", "video-1", "transcriptPath", "video-1.json", "targets", targets)))));
        return path;
    }

    @Test void recordsWhatTheA0SelectorProvidesForEveryTargetWithInputHashes(@TempDir Path dir) throws Exception {
        Path manifest = manifest(dir, List.of("stt-index-0", "stt-index-3"));
        var output = A0ProvidedContext.compute(manifest);

        assertThat(output.format()).isEqualTo("a0-provided-v1");
        assertThat(output.selectorId()).isEqualTo(DialoguePageContextSelector.ID);
        assertThat(output.labelVersion()).isEqualTo("dev-v0");
        assertThat(output.manifestSha256()).isEqualTo(ReferenceOnlyTrial.sha(Files.readAllBytes(manifest)));
        assertThat(output.transcriptSha256()).containsEntry("1", ReferenceOnlyTrial.sha(Files.readAllBytes(dir.resolve("video-1.json"))));
        var provided = output.provided().get("1");
        assertThat(provided).containsOnlyKeys("stt-index-0", "stt-index-3");

        var transcript = CandidateReviewEngine.speechInput(ContextExperimentTrial.context(1L, dir.resolve("video-1.json")));
        for (String target : List.of("stt-index-0", "stt-index-3")) {
            var expected = new DialoguePageContextSelector().select(transcript.find(target).orElseThrow(), transcript);
            assertThat(provided.get(target).status()).isEqualTo("PROVIDED");
            assertThat(provided.get(target).contextSegmentIds()).isEqualTo(expected.contextSegmentIds()).doesNotContain(target);
        }
    }

    @Test void rejectsUnknownTargetsAndNeverOverwrites(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> A0ProvidedContext.compute(manifest(dir, List.of("stt-index-9"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stt-index-9");
        Path manifest = manifest(dir, List.of("stt-index-0"));
        Path out = dir.resolve("a0-provided.json");
        A0ProvidedContext.write(manifest, out);
        assertThat(JSON.readTree(Files.readAllBytes(out)).path("format").asText()).isEqualTo("a0-provided-v1");
        assertThatThrownBy(() -> A0ProvidedContext.write(manifest, out)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }
}
