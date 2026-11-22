package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.TreeMap;
import static org.assertj.core.api.Assertions.*;

class ExperimentSourcesTest {
    @Test void resolvesToolsAndJUnitWithoutMakingJUnitAnExperimentDependency() {
        assertThat(ExperimentSources.analyzerFile("ContextExperimentTrial.java"))
                .isEqualTo(Path.of("src/experiment/java/com/example/oops/analyzer/ContextExperimentTrial.java"));
        assertThat(ExperimentSources.analyzerFile("ContextExperimentTrialTest.java"))
                .isEqualTo(Path.of("src/test/java/com/example/oops/analyzer/ContextExperimentTrialTest.java"));
    }

    @Test void rejectsMissingSourcesAndNonFilenames() {
        for (var name : new String[]{"../ContextExperimentTrial.java", "", "a/b.java", "a.txt"})
            assertThatThrownBy(() -> ExperimentSources.analyzerFile(name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExperimentSources.analyzerFile("MissingSourceForTest.java"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void newPlansFingerprintMovedHelpersAndBuildWiringDeterministically() throws Exception {
        var first = new TreeMap<String, String>(); var second = new TreeMap<String, String>();
        ExperimentSources.addImplementation(first); ExperimentSources.addImplementation(second);
        assertThat(first).isEqualTo(second).containsKeys("build.gradle", "gradle/experiments.gradle",
                "src/experiment/java/com/example/oops/analyzer/ExperimentSources.java",
                "src/experiment/java/com/example/oops/analyzer/CandidateReviewFixture.java",
                "src/experiment/java/com/example/oops/analyzer/ReportedContextFixture.java",
                "src/experiment/java/com/example/oops/analyzer/UtteranceDirectionFixtures.java",
                "src/experiment/java/com/example/oops/analyzer/ContextTokenBudget.java");
        assertThat(first.values()).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    }
}
