package com.example.oops.analyzer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.example.oops.domain.TimelineEventType;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Isolated synthetic retrieval experiment. Never publishes, sends data, or measures model accuracy. */
class ResearchConstraintSelectorImpactTest {
    @Test void compareSyntheticBoundaryWithCurrentRuntimeAndFixedInputs(@TempDir Path tmp) throws Exception {
        Path root=Path.of("../datasets/controversy");
        Path runtime=root.resolve("guidelines/runtime.json"), controls=ResearchCaseAliases.datasetFile("contextControlsPath"),
                suite=root.resolve("evaluation/active-context-regression-20261018/inputs.json");
        Assumptions.assumeTrue(Files.isRegularFile(runtime)&&Files.isRegularFile(controls)&&Files.isRegularFile(suite));
        byte[] before=Files.readAllBytes(runtime);var base=JSON.readValue(before,Archive.class);
        var packet=JSON.readTree(Files.readAllBytes(controls));
        assertThat(packet.path("status").asString()).isEqualTo("USER_APPROVED_DEVELOPMENT_ONLY");
        String id="synthetic-constraint#s1",family="synthetic-constraint-research";
        String retrieval="월세 교통비 생활비 빠듯 부담 고정비 절약 지출 선택 탓 경제적 제약 조언 개인 책임";
        var context=new Comparison("SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT",
                List.of("작성한 합성 상황: 경제적 제약을 설명하는 대화와 개인 책임을 묻는 반응을 구분한다."),
                List.of("제약을 무시하고 개인의 선택만 원인으로 단정한다면 상대를 낮춰 보는 것으로 읽힐 수 있다."),
                List.of("합성 연구 참고이며 특정 영상 발언이나 실제 사건의 확정 근거가 아니다."),List.of(),
                List.of("생활 제약을 이해하고 지출 선택지를 함께 검토하는 상담은 그 자체로 경고 근거가 아니다."));
        var example=new ContextExample(id,family,List.of("insult-mockery"),List.of("SPEECH"),context,
                new RetrievalView("context-unit-retrieval-1","AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",retrieval,retrievalHash(retrieval)),List.of());
        var examples=new ArrayList<>(base.examples());examples.add(example);
        var sources=new ArrayList<>(base.sourceCases());sources.add(new SourceCase(id,family,"UNREVIEWED_DRAFT"));
        var rules=new ArrayList<Rule>();for(var rule:base.guidelines()){
            var ids=new ArrayList<>(rule.sourceCaseIds());if(rule.id().equals("insult-mockery"))ids.add(id);
            rules.add(new Rule(rule.id(),rule.axis(),rule.channels(),rule.condition(),rule.normalContrast(),
                    rule.requiredEvidence(),rule.missingContext(),List.copyOf(ids),rule.referenceContexts()));
        }
        var experimental=new Archive(base.schemaVersion(),base.version(),base.status(),base.humanValidated(),base.usage(),
                base.sourceBundleSha256(),base.refreshOrDeleteBy(),sources,rules,base.contextDictionarySha256(),examples);
        assertThat(ReviewGuidelineLibrary.valid(experimental)).isTrue();
        byte[] after=JSON.writeValueAsBytes(experimental);
        var evaluation=new ArrayList<Map<String,Object>>();
        var fixed=JSON.readTree(Files.readAllBytes(suite));
        for(var item:fixed) {
            var row=new LinkedHashMap<String,Object>();row.put("caseId",item.path("caseId").asString());
            row.put("anchorId",item.path("anchorId").asString());row.put("raw",item.path("raw"));evaluation.add(row);
        }
        for(var item:packet.path("controls")) {
            assertThat(item.path("humanApproved").asBoolean()).isTrue();
            var dialogue=item.path("dialogue");var raw=List.of(Map.of("id","a","startMs",0,"endMs",1000,"text",dialogue.get(0).asString()),
                    Map.of("id","b","startMs",2000,"endMs",3000,"text",dialogue.get(1).asString()));
            var overlap=new TreeSet<>(ContextExampleSelector.terms(dialogue.get(0).asString()+" "+dialogue.get(1).asString()));
            overlap.retainAll(ContextExampleSelector.terms(retrieval));
            System.out.println("SYNTHETIC_OVERLAP="+item.path("id").asString()+" "+overlap.size());
            evaluation.add(Map.of("caseId",item.path("id").asString(),"anchorId","b","raw",raw));
        }
        var request=new LinkedHashMap<String,Object>();request.put("schemaVersion","incident-selector-impact-request-1");
        request.put("auditBindingSha256","a".repeat(64));request.put("evaluationFingerprint",sha(Files.readAllBytes(suite)));
        request.put("baselineRuntimeSha256",sha(before));request.put("candidateRuntimeSha256",sha(after));
        request.put("sourceCaseIdsAdded",List.of(id));request.put("sourceCaseIdsRemoved",List.of());request.put("evaluation",evaluation);
        Path bp=tmp.resolve("before.json"),ap=tmp.resolve("synthetic-after.json"),rp=tmp.resolve("request.json");
        Files.write(bp,before);Files.write(ap,after);Files.write(rp,JSON.writeValueAsBytes(request));
        var result=IncidentSelectorImpactAudit.audit(rp,bp,ap);
        assertThat(result).containsEntry("providerCalls",0).containsEntry("modelJudgmentExecuted",false)
                .containsEntry("accuracyMeasured",false).containsEntry("sharedPatternUnchanged",true);
        System.out.println("SYNTHETIC_IMPACT_SUMMARY="+JSON.writeValueAsString(result));
        // Exploratory second condition only: an authored, bounded retrieval profile, not a production change.
        var original=StructuredContextSearch.load();
        var catalog=JSON.readValue(Files.readAllBytes(Path.of("src/main/resources/context-search-profiles.json")),StructuredContextSearch.Catalog.class);
        var definitions=new ArrayList<>(catalog.profiles());
        definitions.add(new StructuredContextSearch.Definition("synthetic-constraint-diagnostic",List.of("insult-mockery"),
                List.of("월세|집세|생활비|고정비|생계|소득|수입|가계|경제", "탓|네가|낭비|책임|선택|절약|아끼"),
                List.of("제약|생활|생계|경제", "책임|선택|탓|낭비")));
        var altered=new StructuredContextSearch.Catalog("diagnostic-only-not-for-release",List.copyOf(definitions));
        var ctor=StructuredContextSearch.class.getDeclaredConstructor(StructuredContextSearch.Catalog.class,String.class);
        ctor.setAccessible(true);var proposed=ctor.newInstance(altered,retrievalHash(JSON.writeValueAsString(altered)));
        var probeLines=List.of(new ReviewInput.Segment("a",TimelineEventType.SPEECH,0,1000,packet.path("controls").get(1).path("dialogue").get(0).asString(),null),
                new ReviewInput.Segment("b",TimelineEventType.SPEECH,2000,3000,packet.path("controls").get(1).path("dialogue").get(1).asString(),null));
        var probeProfiles=proposed.queryProfiles(TimelineEventType.SPEECH,probeLines);
        assertThat(probeProfiles).contains("synthetic-constraint-diagnostic");
        assertThat(proposed.match(example,probeProfiles)).contains("synthetic-constraint-diagnostic");
        System.out.println("DIAGNOSTIC_PROFILE_MATCH="+probeProfiles.contains("synthetic-constraint-diagnostic")+" REFERENCE_MATCH="+proposed.match(example,probeProfiles));
        try(var mock=mockStatic(StructuredContextSearch.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mock.when(StructuredContextSearch::load).thenReturn(original,proposed);
            var profileResult=IncidentSelectorImpactAudit.audit(rp,bp,ap);
            assertThat(profileResult).containsEntry("providerCalls",0).containsEntry("accuracyMeasured",false)
                    .containsEntry("sharedPatternUnchanged",true).containsEntry("queriesSelectingNewIncidentReference",1)
                    .containsEntry("changedQueryCount",1).containsEntry("removedReferenceCountAcrossQueries",0);
            System.out.println("EXPLORATORY_PROFILE_IMPACT="+JSON.writeValueAsString(profileResult));
        }
        // Hold the archive fixed to separate catalog-only effects from adding the synthetic reference.
        var profileOnlyRequest=new LinkedHashMap<>(request);
        profileOnlyRequest.put("candidateRuntimeSha256",sha(before));
        profileOnlyRequest.put("sourceCaseIdsAdded",List.of());
        Path profileOnlyPath=tmp.resolve("profile-only-request.json");
        Files.write(profileOnlyPath,JSON.writeValueAsBytes(profileOnlyRequest));
        try(var mock=mockStatic(StructuredContextSearch.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mock.when(StructuredContextSearch::load).thenReturn(original,proposed);
            var profileOnly=IncidentSelectorImpactAudit.audit(profileOnlyPath,bp,bp);
            assertThat(profileOnly).containsEntry("providerCalls",0).containsEntry("accuracyMeasured",false)
                    .containsEntry("sharedPatternUnchanged",true);
            System.out.println("PROFILE_ONLY_IMPACT="+JSON.writeValueAsString(profileOnly));
        }
        var dialogue=packet.path("controls").get(1).path("dialogue");
        var baselineWiring=wiring(bp,original,dialogue.get(0).asString(),dialogue.get(1).asString());
        var candidateWiring=wiring(ap,proposed,dialogue.get(0).asString(),dialogue.get(1).asString());
        assertThat(baselineWiring).hasSize(2).allSatisfy(s->assertThat(s).doesNotContain("합성 연구 참고이며"));
        assertThat(candidateWiring).hasSize(2).allSatisfy(s->assertThat(s).contains("합성 연구 참고이며"));
        System.out.println("AUTOMATIC_WIRING providerCalls=0 selectedSyntheticAtDiscovery=true selectedSyntheticAtVerification=true mockedCandidate=true modelAccuracyMeasured=false");
        assertThat(Files.readAllBytes(runtime)).isEqualTo(before);
    }
    static List<String> wiring(Path runtime,StructuredContextSearch catalog,String first,String second) {
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,runtime.toAbsolutePath().toUri().toString(),6000,3,3000,"",true);
        try(var mock=mockStatic(StructuredContextSearch.class,org.mockito.Mockito.CALLS_REAL_METHODS)){
            mock.when(StructuredContextSearch::load).thenReturn(catalog);library.load();
        }
        var fixture=new CandidateReviewEngineTest();var systems=new ArrayList<String>();
        when(fixture.client.completeAsJson(anyString(),anyString(),eq(CandidateReviewEngine.Discovery.class))).thenAnswer(inv->{
            systems.add(inv.getArgument(0));var input=JSON.readTree((String)inv.getArgument(1));var ids=new ArrayList<String>();
            input.path("primaryIds").forEach(n->ids.add(n.asString()));
            var proposal=new CandidateReviewEngine.Proposal("stt-index-1","TARGET_TREATMENT","배선 확인용 가설",
                    List.of(new CandidateReviewEngine.Quote("stt-index-1",second)));
            return Optional.of(new CandidateReviewEngine.Discovery(ids,ids.contains("stt-index-1")?List.of(proposal):List.of(),List.of(),false));
        });
        when(fixture.client.completeAsJson(anyString(),anyString(),eq(CandidateReviewEngine.VerificationResult.class))).thenAnswer(inv->{
            systems.add(inv.getArgument(0));return Optional.empty();
        });
        CandidateReviewEngine.run(fixture.client,fixture.context(first,second),24,null,null,library);
        return systems;
    }
    static String sha(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
}
