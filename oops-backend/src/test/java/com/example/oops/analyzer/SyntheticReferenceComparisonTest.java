package com.example.oops.analyzer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class SyntheticReferenceComparisonTest {
    @Test void preparedPairsOnlyAddTheApprovedSyntheticReference() throws Exception {
        Path source=ResearchCaseAliases.datasetFile("contextControlsPath");
        Assumptions.assumeTrue(Files.isRegularFile(source));
        var plan=SyntheticReferenceComparison.prepare(source);
        assertThat(plan.runs()).hasSize(4);
        for(int i=0;i<4;i+=2){
            var without=plan.runs().get(i);var with=plan.runs().get(i+1);
            assertThat(without.caseId()).isEqualTo(with.caseId());
            assertThat(without.reference()).isFalse();assertThat(with.reference()).isTrue();
            assertThat(with.initial().input()).isEqualTo(without.initial().input());
            assertThat(with.initial().system()).isEqualTo(without.initial().system()+SyntheticReferenceComparison.ADDITION);
        }
        assertThat(plan.maxReservation()).isLessThanOrEqualTo(.50);
    }
}
