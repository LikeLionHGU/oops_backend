package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

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

    @Test void reportsRelativeRankingChangesAmongReferencesSelectedOnBothSides() {
        org.assertj.core.api.Assertions.assertThat(CasebookImpactAudit.rankingChanged(
                List.of("a", "b", "c"), List.of("b", "a", "new"))).isTrue();
        org.assertj.core.api.Assertions.assertThat(CasebookImpactAudit.rankingChanged(
                List.of("a", "b"), List.of("new", "a", "b"))).isFalse();
        org.assertj.core.api.Assertions.assertThat(CasebookImpactAudit.rankAffectedIds(
                List.of("a", "b", "c"), List.of("b", "a", "c"))).containsExactly("a", "b");
        org.assertj.core.api.Assertions.assertThat(CasebookImpactAudit.rankAffectedIds(
                List.of("a", "b", "c"), List.of("new", "a", "b", "c"))).isEmpty();
    }

    @Test void detectsEditedReferenceContentEvenWhenItsIdIsUnchanged() {
        org.assertj.core.api.Assertions.assertThat(CasebookImpactAudit.referenceContentChanged(
                List.of("same-id", "removed"), Map.of("same-id", "old", "removed", "x"),
                List.of("same-id", "added"), Map.of("same-id", "new", "added", "y"))).isTrue();
        org.assertj.core.api.Assertions.assertThat(CasebookImpactAudit.referenceContentChanged(
                List.of("same-id"), Map.of("same-id", "same"), List.of("same-id"),
                Map.of("same-id", "same"))).isFalse();
    }

    @Test void attributesChangedContentAndRelativeRankToSelectedReferenceMechanisms() {
        var beforeMechanisms = Map.of("same", List.of("local-choice"), "other", List.of("body-image"),
                "unchanged", List.of("tertiary"));
        var afterMechanisms = Map.of("same", List.of("local-choice"), "other", List.of("body-image"),
                "unchanged", List.of("tertiary"));
        org.assertj.core.api.Assertions.assertThat(CasebookImpactAudit.changedContentMechanisms(
                List.of("same", "other", "unchanged"), List.of("other", "same", "unchanged"),
                Map.of("same", "old", "other", "fixed", "unchanged", "stable"),
                Map.of("same", "new", "other", "fixed", "unchanged", "stable"),
                beforeMechanisms, afterMechanisms)).containsExactly("local-choice");
        var affected = CasebookImpactAudit.rankAffectedIds(
                List.of("same", "other", "unchanged"), List.of("other", "same", "unchanged"));
        org.assertj.core.api.Assertions.assertThat(affected).containsExactly("same", "other");
        org.assertj.core.api.Assertions.assertThat(CasebookImpactAudit.mechanismsFor(
                affected, beforeMechanisms, afterMechanisms)).containsExactly("body-image", "local-choice");
    }
}
