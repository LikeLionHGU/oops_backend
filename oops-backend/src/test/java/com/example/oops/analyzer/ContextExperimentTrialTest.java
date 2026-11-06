package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Offline prepare/authorize checks for the experiment A CLI. No provider calls. */
class ContextExperimentTrialTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Path transcript(Path dir, String name, String... texts) throws Exception {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 0; i < texts.length; i++)
            segments.add(Map.of("id", "stt-index-" + i, "startMs", i * 60_000L, "endMs", i * 60_000L + 1500, "text", texts[i]));
        Path path = dir.resolve(name);
        Files.writeString(path, JSON.writeValueAsString(Map.of("segments", segments)));
        return path;
    }

    private Path spec(Path dir, Map<String, Object> selector, double cap, int maxHttp) throws Exception {
        transcript(dir, "video-1.json", "그 집은 별로다", "다른 이야기", "그 집 사장 이야기");
        Files.writeString(dir.resolve("runtime.json"), "{}");
        var spec = new LinkedHashMap<String, Object>();
        spec.put("runId", "ctxa-dev-1");
        spec.put("consent", "CTXA_DEV1_USD100_TEXT_ONLY");
        spec.put("conditionId", "A1-T");
        spec.put("selector", selector);
        spec.put("transcripts", List.of(Map.of("videoId", 1, "path", "video-1.json")));
        spec.put("targets", List.of(Map.of("videoId", 1, "targetId", "stt-index-0"), Map.of("videoId", 1, "targetId", "stt-index-2")));
        spec.put("repeats", 2);
        spec.put("maxHttpAttempts", maxHttp);
        spec.put("capUsd", cap);
        spec.put("guidelinesRuntimePath", "runtime.json");
        spec.put("metadata", Map.of("model", "gpt-6-luna", "freeze", "none"));
        Path path = dir.resolve("spec.json");
        Files.writeString(path, JSON.writeValueAsString(spec));
        return path;
    }

    @Test void prepareFreezesSelectionsInputsAndReservationWithoutProviderCalls(@TempDir Path dir) throws Exception {
        var plan = ContextExperimentTrial.prepare(spec(dir, Map.of("type", "A1", "budget", 1_000_000), 1.0, 24));
        assertThat(plan.targets()).extracting(ContextExperimentTrial.PlannedTarget::targetId).containsExactly("stt-index-0", "stt-index-2");
        assertThat(plan.targets().get(0).contextIds()).containsExactly("stt-index-1", "stt-index-2");
        assertThat(plan.targets().get(0).selectorId()).startsWith("A1-T:adjacent-nearest-v1");
        assertThat(plan.targets().get(0).status()).isEqualTo("SELECTED");
        assertThat(plan.inputHashes()).containsKeys(dir.resolve("video-1.json").toRealPath().toString(),
                dir.resolve("runtime.json").toRealPath().toString());
        assertThat(plan.specSha256()).hasSize(64);
        assertThat(plan.implementation()).isNotEmpty();
        assertThat(plan.maxReservationUsd()).isEqualTo(24 * ConfrontationStageTrial.reservation(ConfrontationStageTrial.MAX_BYTES));
        assertThat(JSON.writeValueAsString(ContextExperimentTrial.prepare(dir.resolve("spec.json")))).isEqualTo(JSON.writeValueAsString(plan));
    }

    @Test void oracleSelectionsOverBudgetArePlannedAsInfeasible(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("gold.json"), JSON.writeValueAsString(Map.of("labelVersion", "gold-v0", "labels", Map.of("1", Map.of(
                "stt-index-0", Map.of("goldContextSegmentIds", List.of("stt-index-1"), "evidenceSegmentIds", List.of("stt-index-0")),
                "stt-index-2", Map.of("goldContextSegmentIds", List.of(), "evidenceSegmentIds", List.of("stt-index-2")))))));
        var plan = ContextExperimentTrial.prepare(spec(dir, Map.of("type", "A4", "budget", 1, "labelsPath", "gold.json"), 1.0, 24));
        assertThat(plan.targets()).extracting(ContextExperimentTrial.PlannedTarget::status).containsExactly("BUDGET_INFEASIBLE", "SELECTED");
        assertThat(plan.inputHashes()).containsKey(dir.resolve("gold.json").toRealPath().toString());
    }

    @Test void prepareRejectsUnsafeOrInconsistentSpecs(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> ContextExperimentTrial.prepare(spec(dir, Map.of("type", "A1", "budget", 64), 0.0001, 24)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cap");
        assertThatThrownBy(() -> ContextExperimentTrial.prepare(spec(dir, Map.of("type", "ZZ", "budget", 64), 1.0, 24)))
                .isInstanceOf(IllegalArgumentException.class);
        var path = spec(dir, Map.of("type", "A1", "budget", 64), 1.0, 24);
        var tree = (Map<String, Object>) JSON.readValue(Files.readString(path), Map.class);
        tree.put("consent", "yes");
        Files.writeString(path, JSON.writeValueAsString(tree));
        assertThatThrownBy(() -> ContextExperimentTrial.prepare(path)).isInstanceOf(IllegalArgumentException.class);
        List<Map<String, Object>> shuffled = List.of(Map.of("id", "stt-7", "startMs", 0, "endMs", 1500, "text", "다른 ID 체계"));
        spec(dir, Map.of("type", "A1", "budget", 64), 1.0, 24);
        Files.writeString(dir.resolve("video-1.json"), JSON.writeValueAsString(Map.of("segments", shuffled)));
        assertThatThrownBy(() -> ContextExperimentTrial.prepare(dir.resolve("spec.json")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void runRequiresTheExactPlanFreshConsentAndNoDrift(@TempDir Path dir) throws Exception {
        Path specPath = spec(dir, Map.of("type", "A1", "budget", 1_000_000), 1.0, 24);
        byte[] planBytes = JSON.writeValueAsBytes(ContextExperimentTrial.prepare(specPath));
        String sha = ReferenceOnlyTrial.sha(planBytes);
        String consent = "CTXA_DEV1_USD100_TEXT_ONLY";
        assertThat(ContextExperimentTrial.authorize(planBytes, sha, consent).spec().runId()).isEqualTo("ctxa-dev-1");
        assertThatThrownBy(() -> ContextExperimentTrial.authorize(planBytes, "0".repeat(64), consent))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextExperimentTrial.authorize(planBytes, sha, null)).isInstanceOf(IllegalArgumentException.class);
        transcript(dir, "video-1.json", "그 집은 별로다", "바뀐 이야기", "그 집 사장 이야기");
        assertThatThrownBy(() -> ContextExperimentTrial.authorize(planBytes, sha, consent))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("drift");
    }

    @Test void httpGuardStopsAtTheAttemptLimitAndTheCostCap() {
        var guard = new ContextExperimentTrial.HttpGuard(2, 1.0);
        guard.accept(100);
        guard.accept(100);
        assertThatThrownBy(() -> guard.accept(100)).isInstanceOf(IllegalStateException.class);
        assertThat(guard.blocked()).isTrue();
        var cheap = new ContextExperimentTrial.HttpGuard(100, ConfrontationStageTrial.reservation(100) * 1.5);
        cheap.accept(100);
        assertThatThrownBy(() -> cheap.accept(100)).isInstanceOf(IllegalStateException.class);
        assertThat(cheap.attempts()).isEqualTo(1);
    }
}
