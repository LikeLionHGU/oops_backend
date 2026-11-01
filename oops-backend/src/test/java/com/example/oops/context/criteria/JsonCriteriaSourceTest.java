package com.example.oops.context.criteria;

import com.example.oops.context.criteria.CriteriaModel.StopJudgment;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JsonCriteriaSourceTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String RULESET = "criteria/ruleset-oops-all-0.4-draft.json";
    public static final String STOPS = "criteria/stop-judgments-draft.json";

    public static JsonCriteriaSource real() {
        return new JsonCriteriaSource(new ClassPathResource(RULESET), new ClassPathResource(STOPS));
    }

    @Test void bundledCriteriaLoadCompletely() {
        var snapshot = real().load();
        assertThat(snapshot.rulesetId()).isEqualTo("ruleset:oops:all:0.4-draft");
        assertThat(snapshot.rulesetVersion()).isEqualTo("0.4-draft");
        assertThat(snapshot.reviewGateVersion()).isEqualTo("independent-review/1.0");
        assertThat(snapshot.criteria()).hasSize(21);
        assertThat(snapshot.criteria()).extracting(c -> c.id())
                .startsWith("G01-C01", "G02-C01").endsWith("G21-C01").doesNotHaveDuplicates();
        // 독립 점검 21개 · 본문 63필드 · 추가 기준 68개 (전달서 9절)
        assertThat(snapshot.criteria()).allSatisfy(c -> {
            assertThat(c.gate().minimumInformation()).isNotBlank();
            assertThat(c.gate().exclusionConditions()).isNotBlank();
            assertThat(c.gate().missingInformationScope()).isNotBlank();
        });
        assertThat(snapshot.criteria().stream().mapToInt(c -> c.additional().size()).sum()).isEqualTo(68);
        assertThat(snapshot.criteria().stream().mapToInt(c -> c.judgments().size()).sum()).isEqualTo(31);
    }

    @Test void g02StopCodesMatchTheAgreedExample() {
        var g02 = real().load().find("G02-C01").orElseThrow();
        assertThat(g02.stopIfMissingCodes()).containsExactlyInAnyOrder("MINOR_TARGET", "NO_CONSENT_DISCLOSURE", "DISCOMFORT");
        assertThat(g02.neverAssertCodes()).isEmpty();
        assertThat(g02.wholeStopIf()).isNotBlank();
        var g16 = real().load().find("G16-C01").orElseThrow();
        assertThat(g16.judgment("INTENTIONAL_DECEPTION")).get().extracting(StopJudgment::kind)
                .isEqualTo(StopJudgment.Kind.NEVER_ASSERT);
    }

    @Test void snapshotIsReusedAndImmutable() {
        var source = real();
        var first = source.load();
        assertThat(source.load()).isSameAs(first);
        assertThatThrownBy(() -> first.criteria().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void missingGateFieldFailsInsteadOfPassing() {
        assertLoadFails(ruleset -> ((ObjectNode) ruleset.get("groups").get(1).get("independent_review"))
                .put("missing_information_scope", " "), "missing_information_scope");
    }

    @Test void duplicateCommonIdFails() {
        assertLoadFails(ruleset -> ((ObjectNode) ruleset.get("groups").get(1).get("common")).put("id", "G01-C01"),
                "중복");
    }

    @Test void gateVersionMismatchFails() {
        assertLoadFails(ruleset -> ((ObjectNode) ruleset.get("groups").get(0).get("independent_review"))
                .put("version", "independent-review/0.9"), "독립 점검 버전");
    }

    @Test void criterionWithoutStopEntryFails() {
        var ruleset = tree(RULESET);
        var stops = tree(STOPS);
        ((ArrayNode) stops.get("criteria")).remove(20);
        assertThatThrownBy(() -> source(ruleset, stops).load())
                .isInstanceOf(CriteriaLoadException.class).hasMessageContaining("G21-C01");
    }

    @Test void stopIfMissingWithoutRequirementFails() {
        var ruleset = tree(RULESET);
        var stops = tree(STOPS);
        ((ObjectNode) stops.get("criteria").get(1).get("judgments").get(0)).remove("requires");
        assertThatThrownBy(() -> source(ruleset, stops).load())
                .isInstanceOf(CriteriaLoadException.class).hasMessageContaining("requires");
    }

    @Test void missingFileFails() {
        var source = new JsonCriteriaSource(new ClassPathResource("criteria/none.json"), new ClassPathResource(STOPS));
        assertThatThrownBy(source::load).isInstanceOf(CriteriaLoadException.class);
    }

    private static void assertLoadFails(Consumer<ObjectNode> breakRuleset, String message) {
        var ruleset = tree(RULESET);
        breakRuleset.accept(ruleset);
        assertThatThrownBy(() -> source(ruleset, tree(STOPS)).load())
                .isInstanceOf(CriteriaLoadException.class).hasMessageContaining(message);
    }

    private static ObjectNode tree(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return (ObjectNode) JSON.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonCriteriaSource source(ObjectNode ruleset, ObjectNode stops) {
        return new JsonCriteriaSource(
                new ByteArrayResource(JSON.writeValueAsString(ruleset).getBytes(StandardCharsets.UTF_8)),
                new ByteArrayResource(JSON.writeValueAsString(stops).getBytes(StandardCharsets.UTF_8)));
    }
}
