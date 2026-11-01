package com.example.oops.analyzer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Research-only local comparison. Never modifies source/runtime or sends data to a provider. */
class ReportedContextCorrectionImpactTest {
    static Archive corrected(Archive archive,tools.jackson.databind.JsonNode p){
        var details=p.path("proposal");String id=p.path("statementId").asString();
        var examples=new ArrayList<ContextExample>();int changed=0;
        for(var ex:archive.examples()){
            if(!id.equals(ex.id())){examples.add(ex);continue;}
            var context=new Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT",
                    List.of(details.path("reportedContext").path("summary").asString()),
                    List.of(details.path("reviewHypothesis").asString()),
                    java.util.stream.StreamSupport.stream(details.path("unknowns").spliterator(),false).map(x->x.asString()).toList(),
                    List.of(),List.of(details.path("normalContrast").asString()));
            examples.add(new ContextExample(ex.id(),ex.familyId(),ex.mechanismIds(),ex.channels(),context,ex.retrievalView(),ex.routingKeys(),ex.relationProfile(),ex.sourceGroupId()));changed++;
        }
        assertThat(changed).isOne();
        var candidate=new Archive(archive.schemaVersion(),archive.version(),archive.status(),archive.humanValidated(),archive.usage(),
                archive.sourceBundleSha256(),archive.refreshOrDeleteBy(),archive.sourceCases(),archive.guidelines(),archive.contextDictionarySha256(),examples);
        assertThat(ReviewGuidelineLibrary.valid(candidate)).isTrue();return candidate;
    }
    @Test void replayFrozenRetrievalWithReportedContextCorrection(@TempDir Path tmp)throws Exception {
        Path root=Path.of("../datasets/controversy"),runtime=root.resolve("guidelines/runtime.json"),
                proposal=ResearchCaseAliases.datasetFile("correctionProposalPath"),
                controls=ResearchCaseAliases.datasetFile("contextControlsPath"),
                suite=root.resolve("evaluation/active-context-regression-20261018/inputs.json");
        Assumptions.assumeTrue(Files.isRegularFile(runtime)&&Files.isRegularFile(proposal)&&Files.isRegularFile(controls)&&Files.isRegularFile(suite));
        byte[] before=Files.readAllBytes(runtime);var archive=JSON.readValue(before,Archive.class);var p=JSON.readTree(Files.readAllBytes(proposal));
        assertThat(p.path("status").asString()).isEqualTo("RESEARCH_ONLY_NOT_APPLIED");
        assertThat(p.path("externalModelAuthorized").asBoolean()).isFalse();
        assertThat(p.path("runtimeEligible").asBoolean()).isFalse();
        var details=p.path("proposal");var candidate=corrected(archive,p);byte[] after=JSON.writeValueAsBytes(candidate);
        var evaluation=new ArrayList<Map<String,Object>>();
        for(var row:JSON.readTree(Files.readAllBytes(suite)))evaluation.add(Map.of("caseId",row.path("caseId").asString(),
                "anchorId",row.path("anchorId").asString(),"raw",row.path("raw")));
        var packet=JSON.readTree(Files.readAllBytes(controls));assertThat(packet.path("status").asString()).isEqualTo("USER_APPROVED_DEVELOPMENT_ONLY");
        for(var control:packet.path("controls")){
            assertThat(control.path("humanApproved").asBoolean()).isTrue();var d=control.path("dialogue");
            evaluation.add(Map.of("caseId",control.path("id").asString(),"anchorId","b","raw",List.of(
                    Map.of("id","a","startMs",0,"endMs",1000,"text",d.get(0).asString()),
                    Map.of("id","b","startMs",2000,"endMs",3000,"text",d.get(1).asString()))));
        }
        // Retrieval diagnostic only: newly written paraphrase, not an original transcript or approved label.
        evaluation.add(Map.of("caseId","reported-context-synthetic-probe","anchorId","b","raw",List.of(
                Map.of("id","a","startMs",0,"endMs",1000,"text","경제적 이유로 부모와 같이 살고 있어요."),
                Map.of("id","b","startMs",2000,"endMs",3000,"text","돈이 부족하면 지출을 줄여보는 게 어떨까요?"))));
        var request=new LinkedHashMap<String,Object>();request.put("schemaVersion","incident-selector-impact-request-1");
        request.put("auditBindingSha256","a".repeat(64));request.put("evaluationFingerprint",ResearchConstraintSelectorImpactTest.sha(Files.readAllBytes(suite)));
        request.put("baselineRuntimeSha256",ResearchConstraintSelectorImpactTest.sha(before));request.put("candidateRuntimeSha256",ResearchConstraintSelectorImpactTest.sha(after));
        request.put("sourceCaseIdsAdded",List.of());request.put("sourceCaseIdsRemoved",List.of());request.put("evaluation",evaluation);
        Path bp=tmp.resolve("before.json"),ap=tmp.resolve("research-candidate.json"),rp=tmp.resolve("request.json");
        Files.write(bp,before);Files.write(ap,after);Files.write(rp,JSON.writeValueAsBytes(request));
        var result=IncidentSelectorImpactAudit.audit(rp,bp,ap);
        assertThat(result).containsEntry("providerCalls",0).containsEntry("accuracyMeasured",false).containsEntry("sharedPatternUnchanged",true);
        System.out.println("REPORTED_CORRECTION_IMPACT="+JSON.writeValueAsString(result));
        var search=StructuredContextSearch.load();String first="경제적 이유로 부모와 같이 살고 있어요.",second="돈이 부족하면 지출을 줄여보는 게 어떨까요?";
        var oldRequests=ResearchConstraintSelectorImpactTest.wiring(bp,search,first,second);
        var newRequests=ResearchConstraintSelectorImpactTest.wiring(ap,search,first,second);
        assertThat(oldRequests).hasSize(2);assertThat(newRequests).hasSize(2);
        for(int i=0;i<2;i++){
            assertThat(newRequests.get(i)).contains(details.path("reviewHypothesis").asString());
            assertThat(oldRequests.get(i)).doesNotContain(details.path("reviewHypothesis").asString());
        }
        System.out.println("REPORTED_CORRECTION_WIRING discoveryAndVerificationChanged=true mockedCandidate=true providerCalls=0 accuracyMeasured=false");
        assertThat(Files.readAllBytes(runtime)).isEqualTo(before);
    }
}
