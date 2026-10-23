package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.dto.TimelineEventDto;
import com.example.oops.fusion.FindingFusionService;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exact JSON-content request replay of this run, not a new model evaluation. */
class CurrentStoredPipelineReplayTest {
    static void sameSystem(String expected,String actual) {
        String marker="contextReference=";int a=expected.indexOf(marker),b=actual.indexOf(marker);
        if(a<0) {assertThat(actual).isEqualTo(expected);return;}
        assertThat(b).isPositive();assertThat(actual.substring(0,b)).isEqualTo(expected.substring(0,a));
        assertThat(JSON.readTree(actual.substring(b+marker.length()))).isEqualTo(JSON.readTree(expected.substring(a+marker.length())));
    }
    @Test void recordedResponsesTraverseEngineFusionAndReportCardMappingWithoutNewCalls() throws Exception {
        var root=Path.of("../datasets/controversy/experiments");
        var results=root.resolve("current-stored-pipeline-20261023-results/results.json");
        assumeTrue(Files.exists(results));
        var plan=JSON.readValue(Files.readAllBytes(root.resolve("current-stored-pipeline-20261023-plan-v2/plan.json")),CurrentStoredTextPipelineTrial.Plan.class);
        var saved=JSON.readTree(Files.readAllBytes(results));
        var rows=new ArrayList<Map<String,Object>>();
        for(var row:saved.get("results")) {
            String id=row.path("caseId").asText();
            var request=plan.source().requests().stream().filter(r->r.caseId().equals(id)).findFirst().orElseThrow();
            var review=CandidateHypothesisTrial.raw(JSON.readTree(request.input()));
            var context=new AnalysisContext(Video.builder().filename("stored-context-"+id).build(),ContentGenre.GENERAL,List.of(),List.of(),review);
            var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(plan.runtime()),true,"classpath:frozen.json",6000,3,3000,"",true);library.load();
            var client=mock(OpenAiClient.class);var index=new AtomicInteger();
            when(client.completeAsJson(anyString(),anyString(),any(Class.class))).thenAnswer(call->{
                int i=index.getAndIncrement();assertThat(i).isLessThan(row.get("calls").size());
                var recorded=row.get("calls").get(i);
                sameSystem(recorded.path("system").asText(),call.getArgument(0));
                assertThat(JSON.readTree((String)call.getArgument(1))).isEqualTo(recorded.get("input"));
                return Optional.of(JSON.treeToValue(recorded.get("output"),(Class<?>)call.getArgument(2)));
            });
            var result=CandidateReviewEngine.run(client,context,new CandidateReviewEngine.RunServices(null,null,library,null,null),
                    new CandidateReviewEngine.RunOptions(24,0,false,new CandidateReviewEngine.VerificationFeatures(false,false,false),false));
            assertThat(index.get()).isEqualTo(row.get("calls").size());
            // Historical PARTIAL included bounded-context caveats. Replay the exact same
            // requests/responses, but now only actual missing work may make processing partial.
            assertThat(row.path("status").asText()).isIn("PARTIAL", "SUCCESS");
            assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
            assertThat(result.unassessedSegmentIds()).isEmpty();
            if (row.path("status").asText().equals("PARTIAL")) assertThat(result.notice()).contains("문맥 제한");
            assertThat(result.findings()).hasSize(row.get("findings").size());
            var fused=new FindingFusionService().fuse(result.findings());
            var cards=fused.stream().map(f->TimelineEventDto.from(f,null,null,null)).toList();
            assertThat(cards).hasSize(id.equals("audience-devaluation")?1:0);
            rows.add(Map.of("caseId",id,"status",result.status(),"cards",cards,"unassessed",result.unassessedSegmentIds()));
        }
        String output=System.getenv("CURRENT_CARD_REPLAY_OUTPUT");
        if(output!=null) {
            Path path=Path.of(output).toAbsolutePath();assertThat(path.getParent().toRealPath()).startsWith(root.toRealPath());
            var hashes=new TreeMap<String,String>();
            for(String file:List.of("analyzer/ReviewGuidelineLibrary.java","analyzer/ContextBoundarySupplement.java",
                    "analyzer/CandidateReviewEngine.java","analyzer/ReviewJudgmentPolicy.java",
                    "fusion/FindingFusionService.java","dto/TimelineEventDto.java"))
                hashes.put(file,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/main/java/com/example/oops").resolve(file))));
            hashes.put("livePlan",ReferenceOnlyTrial.sha(Files.readAllBytes(root.resolve("current-stored-pipeline-20261023-plan-v2/plan.json"))));
            hashes.put("liveResults",ReferenceOnlyTrial.sha(Files.readAllBytes(results)));
            hashes.put("runtime",ReferenceOnlyTrial.sha(plan.runtime().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Files.writeString(path,JSON.writeValueAsString(Map.of("providerCalls",0,"scope","SAVED_RESPONSE_ENGINE_FUSION_DTO_REPLAY_NOT_HTTP_OR_DB",
                    "rows",rows,"currentSourceAndArtifactHashes",hashes)),StandardOpenOption.CREATE_NEW);
        }
    }
}
