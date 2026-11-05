package com.example.oops.analyzer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Local draft impact only; never publishes or sends provider requests. */
class Video142DraftImpactTest {
    @Test void compareApprovedDevelopmentBoundaryAgainstFrozenInputs(@TempDir Path temp)throws Exception{
        Path root=Path.of("../datasets/controversy"),runtime=root.resolve("guidelines/runtime.json"),
                draftPath=root.resolve("research/video-142-casebook-draft-20261102.json"),
                reviewPath=root.resolve("research/video-142-human-review-20261102.json"),
                suite=root.resolve("evaluation/active-context-regression-20261018/inputs.json");
        Assumptions.assumeTrue(Files.isRegularFile(runtime)&&Files.isRegularFile(draftPath)&&Files.isRegularFile(reviewPath)&&Files.isRegularFile(suite));
        byte[] baseline=Files.readAllBytes(runtime),reviewBytes=Files.readAllBytes(reviewPath);var a=JSON.readValue(baseline,Archive.class);
        var d=JSON.readTree(Files.readAllBytes(draftPath));var review=JSON.readTree(reviewBytes);
        assertThat(d.path("status").asString()).isEqualTo("USER_APPROVED_DEVELOPMENT_BOUNDARIES_NOT_RUNTIME_APPROVED");
        assertThat(d.path("originReview").path("sha256").asString()).isEqualTo(ResearchConstraintSelectorImpactTest.sha(reviewBytes));
        assertThat(d.path("promotion").path("runtime").asBoolean()).isFalse();
        var p=d.path("patternDraft");var contrasts=new ArrayList<String>();
        for(var item:d.path("contrasts")){assertThat(item.path("humanApproved").asBoolean()).isTrue();
            var lines=new ArrayList<String>();item.path("dialogue").forEach(x->lines.add(x.asString()));contrasts.add("합성 개발 정상 대조: "+String.join(" → ",lines));}
        String id="video142-dev#eating-banter",family="video142-eating-banter-development";
        String search="애칭 꼬맹이 먹는 양 식사량 많이 먹다 사람 놀림 신체 체격 농담";
        var context=new Comparison("SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT",List.of(p.path("situation").asString()),
                List.of(p.path("potentialConcern").asString()),
                List.of("사용자 승인 개발 경계이며 논란 발생이나 원음·화자·동의를 확정하지 않는다."),List.of(),contrasts);
        var example=new ContextExample(id,family,List.of("insult-mockery"),List.of("SPEECH"),context,
                new RetrievalView("context-unit-retrieval-1","AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",search,retrievalHash(search)),List.of());
        var examples=new ArrayList<>(a.examples());examples.add(example);var sources=new ArrayList<>(a.sourceCases());sources.add(new SourceCase(id,family,"UNREVIEWED_DRAFT"));
        var rules=new ArrayList<Rule>();for(var rule:a.guidelines()){
            var ids=new ArrayList<>(rule.sourceCaseIds());if(rule.id().equals("insult-mockery"))ids.add(id);
            rules.add(new Rule(rule.id(),rule.axis(),rule.channels(),rule.condition(),rule.normalContrast(),rule.requiredEvidence(),rule.missingContext(),ids,rule.referenceContexts()));
        }
        var candidate=new Archive(a.schemaVersion(),a.version(),a.status(),a.humanValidated(),a.usage(),a.sourceBundleSha256(),
                a.refreshOrDeleteBy(),sources,rules,a.contextDictionarySha256(),examples);
        assertThat(ReviewGuidelineLibrary.valid(candidate)).isTrue();byte[] proposed=JSON.writeValueAsBytes(candidate);
        var rows=new ArrayList<Map<String,Object>>();for(var row:JSON.readTree(Files.readAllBytes(suite)))rows.add(Map.of(
                "caseId",row.path("caseId").asString(),"anchorId",row.path("anchorId").asString(),"raw",row.path("raw")));
        var original=review.path("reviews").get(2);assertThat(original.path("decision").asString()).isEqualTo("REVIEW_REQUIRED");
        rows.add(Map.of("caseId","video142-reviewed-window","anchorId","stt-index-147","raw",original.path("transcriptSegments")));
        for(var c:d.path("contrasts")){var lines=c.path("dialogue");rows.add(Map.of("caseId",c.path("id").asString(),"anchorId","b","raw",List.of(
                Map.of("id","a","startMs",0,"endMs",1000,"text",lines.get(0).asString()),
                Map.of("id","b","startMs",2000,"endMs",3000,"text",lines.get(1).asString()))));}
        var req=new LinkedHashMap<String,Object>();req.put("schemaVersion","incident-selector-impact-request-1");req.put("auditBindingSha256","a".repeat(64));
        req.put("evaluationFingerprint",ResearchConstraintSelectorImpactTest.sha(Files.readAllBytes(suite)));
        req.put("baselineRuntimeSha256",ResearchConstraintSelectorImpactTest.sha(baseline));req.put("candidateRuntimeSha256",ResearchConstraintSelectorImpactTest.sha(proposed));
        req.put("sourceCaseIdsAdded",List.of(id));req.put("sourceCaseIdsRemoved",List.of());req.put("evaluation",rows);
        Path b=temp.resolve("before.json"),c=temp.resolve("draft.json"),r=temp.resolve("request.json");
        Files.write(b,baseline);Files.write(c,proposed);Files.write(r,JSON.writeValueAsBytes(req));var report=IncidentSelectorImpactAudit.audit(r,b,c);
        assertThat(report).containsEntry("providerCalls",0).containsEntry("accuracyMeasured",false).containsEntry("sharedPatternUnchanged",true);
        System.out.println("VIDEO142_DRAFT_IMPACT="+JSON.writeValueAsString(report));assertThat(Files.readAllBytes(runtime)).isEqualTo(baseline);
    }
}
