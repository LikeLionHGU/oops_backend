package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Fixed existing approved development material, not new human labels or holdout. */
class ContextFitAdoptionGateTest {
    private static final Path ROOT=Path.of("../datasets/controversy");
    record Gate(int incrementalRecovery, int lostReferences, int irrelevantAdditions, int budgetViolations) {
        boolean adopt() { return incrementalRecovery>0 && lostReferences==0
                && irrelevantAdditions==0 && budgetViolations==0; }
    }
    @Test void gateRequiresBenefitAndNoKnownRegression() {
        assertThat(new Gate(0,0,0,0).adopt()).isFalse();
        assertThat(new Gate(1,0,0,0).adopt()).isTrue();
        assertThat(new Gate(1,1,0,0).adopt()).isFalse();
        assertThat(new Gate(1,0,1,0).adopt()).isFalse();
        assertThat(new Gate(1,0,0,1).adopt()).isFalse();
    }
    @Test void freezeCrossTopicGateOnActiveAssetsAndApprovedStoredSources() throws Exception {
        Path runtime=ROOT.resolve("guidelines/runtime.json");
        assumeTrue(Files.exists(runtime));
        var json=ReviewGuidelineLibrary.JSON;
        byte[] original=Files.readAllBytes(runtime);
        var archive=json.readTree(original);
        var assets=new ArrayList<ReviewGuidelineLibrary.ContextExample>();
        for(var e:archive.get("examples")) assets.add(json.treeToValue(e,ReviewGuidelineLibrary.ContextExample.class));
        var rules=new ArrayList<ReviewGuidelineLibrary.Rule>();
        for(var r:archive.get("guidelines")) rules.add(json.treeToValue(r,ReviewGuidelineLibrary.Rule.class));
        var selector=new ContextExampleSelector(assets,rules,StructuredContextSearch.load());
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,runtime.toAbsolutePath().toUri().toString(),6000,3,3000,"",true,false);
        library.load();
        var patterns=rules.stream().filter(r->r.channels().contains("SPEECH")).map(r->Map.of("id",r.id(),"axis",r.axis(),"condition",r.condition(),"normalContrast",r.normalContrast(),"requiredEvidence",r.requiredEvidence(),"missingContext",r.missingContext())).toList();
        var rows=new ArrayList<Map<String,Object>>();
        int gained=0,lost=0;
        var hashes=new TreeMap<String,String>();
        for(String name:List.of("mimiminu-victim-quotation","mimiminu-audience-devaluation","mimiminu-mt-preference")) {
            var unitPath=ROOT.resolve("context-units/"+name+".json");
            var unitBytes=Files.readAllBytes(unitPath);
            var unit=json.readTree(unitBytes);
            var sourcePath=ROOT.resolve(unit.at("/source/path").asText()).toRealPath();
            assertThat(sourcePath).startsWith(ROOT.toRealPath());
            var bytes=Files.readAllBytes(sourcePath);
            assertThat(ReferenceOnlyTrial.sha(bytes)).isEqualTo(unit.at("/source/sha256").asText());
            var source=json.readTree(bytes);
            assertThat(source.at("/developmentReview/basis").asText()).isEqualTo("USER_APPROVED_DEVELOPMENT_BOUNDARY");
            assertThat(source.at("/developmentReview/decision").asText()).isEqualTo(unit.path("developmentDecision").asText());
            var raw=new ArrayList<ReviewInput.Segment>();
            for(var s:source.get("segments")) raw.add(new ReviewInput.Segment(s.path("id").asText(),SPEECH,s.path("startMs").asLong(),s.path("endMs").asLong(),s.path("text").asText(),null));
            var focusIds=new HashSet<String>();
            unit.get("utteranceIds").forEach(id->focusIds.add(id.asText()));
            var focus=raw.stream().filter(s->focusIds.contains(s.id())).toList();
            assertThat(focus).hasSize(focusIds.size());
            var selected=library.select(SPEECH,raw,name,focus);
            var before=selected.trace().exampleIds().stream().map(id->assets.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow()).toList();
            var pool=selector.rank(SPEECH,raw,Set.of());
            var after=ContextFitRankingPreview.select(pool,before,raw,focus,3,
                    items->ContextFitRankingPreviewTest.exampleSize(items)<=3000
                            && ContextFitRankingPreviewTest.payloadSize(patterns,items)<=6000);
            String expected=unit.path("id").asText();
            boolean had=before.stream().anyMatch(e->e.id().equals(expected));
            boolean has=after.selected().stream().anyMatch(e->e.id().equals(expected));
            if(!had && has) gained++;
            if(had && !has) lost++;
            assertThat(had).isTrue();
            assertThat(after.selected()).containsExactlyElementsOf(before);
            hashes.put(name+".unit",ReferenceOnlyTrial.sha(unitBytes));
            hashes.put(name+".source",ReferenceOnlyTrial.sha(bytes));
            rows.add(Map.of("caseId",expected,"label",unit.path("developmentDecision").asText(),
                    "labelScope","EXISTING_USER_APPROVED_DEVELOPMENT_NOT_HOLDOUT",
                    "before",selected.trace().exampleIds(),"after",after.selected().stream().map(ReviewGuidelineLibrary.ContextExample::id).toList(),
                    "state",after.state(),"gained",!had&&has,"lost",had&&!has));
        }
        var gate=new Gate(gained,lost,0,0);
        assertThat(gate.adopt()).isFalse();
        assertThat(Files.readAllBytes(runtime)).isEqualTo(original);
        hashes.put("runtime",ReferenceOnlyTrial.sha(original));
        for(String file:List.of("ReviewGuidelineLibrary.java","ContextExampleSelector.java",
                "CandidateReviewEngine.java","ReviewJudgmentPolicy.java"))
            hashes.put("production."+file,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/main/java/com/example/oops/analyzer").resolve(file))));
        for(String file:List.of("ContextFitRankingPreview.java","ContextFitAdoptionGateTest.java"))
            hashes.put(file,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer").resolve(file))));
        var history=new ArrayList<Map<String,Object>>();
        for(var item:List.of(Map.entry("quotation-ocr-followup-20261022-cZy7vL",98),
                Map.entry("quotation-ocr-followup-20261022-cZy7vL",99),Map.entry("ocr-casebook-repeat-20261022-3nphCI",109))) {
            var reportPath=ROOT.resolve("experiments").resolve(item.getKey()).resolve("report-"+item.getValue()+".json");
            var bytes=Files.readAllBytes(reportPath);
            var saved=json.readTree(bytes);
            var data=saved.has("data")?saved.get("data"):saved;
            assertThat(data.path("videoId").asInt()).isEqualTo(item.getValue());
            var events=data.path("events");
            assertThat(events.isArray()).isTrue();
            hashes.put("historicalReport."+item.getValue(),ReferenceOnlyTrial.sha(bytes));
            history.add(Map.of("videoId",item.getValue(),"eventCount",events.size(),
                    "historicalOnly",true,"reanalysisPerformed",false));
        }
        var report=Map.of("gate",gate,"adopt",gate.adopt(),"rows",rows,"hashes",hashes,
                "providerCalls",0,"activeRuntimeChanged",false,"scope","RETRIEVAL_ONLY_NOT_FINAL_CARD_ACCURACY",
                "historicalReports",history,"freshPaidComparisonNeeded",false,
                "reason","NO_SELECTION_CHANGE_ON_ACTIVE_ASSETS; HISTORICAL_ERRORS_NOT_FIXED");
        String output=System.getenv("CONTEXT_FIT_GATE_OUTPUT");
        if(output!=null) {
            var path=Path.of(output).toAbsolutePath();
            assertThat(path.getParent().toRealPath()).startsWith(ROOT.resolve("experiments").toRealPath());
            Files.writeString(path,json.writeValueAsString(report),StandardOpenOption.CREATE_NEW);
        }
        System.out.println("CONTEXT_FIT_GATE "+json.writeValueAsString(report));
    }
}
