package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;

class ReferenceOnlyTrialTest {
    @Test void referencePairsKeepEveryInputFieldAndRejectOneArmMutation() {
        var f=new ReviewGuidelineLibraryTest();
        var archive=new ContextExampleSelectionTest().archive(2);
        var before=f.library(archive);
        var after=f.library(JSON.writeValueAsString(archive)
                .replace("생활조건을 열등하게 비교","생활조건을 하찮게 비교").getBytes(java.nio.charset.StandardCharsets.UTF_8),6000);
        var input=JSON.createObjectNode(); input.put("promptRevision",CandidateReviewEngine.REVISION);
        input.putArray("raw").addObject().put("id","stt-index-0").put("text","공공시설이 없는 생활조건을 열등하게 비교한다").put("startMs",0).put("endMs",1500);
        input.putArray("candidates").addObject().put("hypothesisNotEvidence","고정된 중립 가설");
        List<ReferenceOnlyTrial.Task> work=new ArrayList<>();
        for(int i=0;i<14;i++) work.addAll(ReferenceOnlyTrial.pair("synthetic-"+i,1,1,input,
                CandidateHypothesisTrial.raw(input),before,after,i<8?3:2,null));
        ReferenceOnlyTrial.validatePairs(work);
        assertThat(work.get(0).input()).isEqualTo(work.get(1).input());
        assertThat(work.get(0).system()).isNotEqualTo(work.get(1).system());
        ((ObjectNode)work.get(1).input().get("candidates").get(0)).put("hypothesisNotEvidence","바뀐 가설");
        assertThatThrownBy(()->ReferenceOnlyTrial.validatePairs(work)).isInstanceOf(IllegalArgumentException.class);
    }
    @org.junit.jupiter.api.Tag("dataset-backed")
    @Test void privatePlanReproducesSavedSystemsAndPreservesDGroupsWhenAvailable() throws Exception {
        Path root=Path.of("../datasets/controversy");
        Path source=root.resolve("experiments/text-boundary-37-20261014/results.json");
        Path baseline=root.resolve("legacy/runtime-history/aa8e90044981fe403aa4ddce1c04102794ac54b4d828dc7d93a3e20e8aeaccbc.json");
        Path current=root.resolve("guidelines/runtime.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(source)&&Files.exists(baseline)&&Files.exists(current));
        var work=ReferenceOnlyTrial.tasks(JSON.readTree(Files.readString(source)),
                TextBoundaryTrial.readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json")),
                ReferenceOnlyTrial.library(baseline),ReferenceOnlyTrial.library(current));
        ReferenceOnlyTrial.validatePairs(work);
        assertThat(work.stream().mapToInt(ReferenceOnlyTrial.Task::repeats).sum()).isEqualTo(72);
        assertThat(work.stream().mapToInt(t->t.repeats()*t.input().get("candidates").size()).sum()).isEqualTo(84);
        assertThat(work.stream().filter(t->t.caseId().startsWith("P-D")))
                .allSatisfy(t->assertThat(t.input().get("candidates").size()).isEqualTo(2));
        assertThat(work).allSatisfy(t->t.input().get("candidates").forEach(c->assertThat(c.has("hypothesisNotEvidence")).isTrue()));
        for(var task:work) assertThat(ReferenceOnlyTrial.sha(ReferenceOnlyTrial.prefix(task.system())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(ReferenceOnlyTrial.FROZEN_PREFIX_SHA);
        assertThatThrownBy(() -> ReferenceOnlyTrial.knownPrefix("changed frozen instruction\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Verification instruction changed");
    }
}
