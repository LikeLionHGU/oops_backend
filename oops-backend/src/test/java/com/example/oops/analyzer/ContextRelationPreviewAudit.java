package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;
import static com.example.oops.domain.TimelineEventType.SPEECH;

/** Frozen candidates/roles only, no discovery rerun, verdicts, manual routing hints or provider calls. */
public final class ContextRelationPreviewAudit {
    public static void main(String[] args) throws Exception {
        check(args.length==4,"Saved complete results, v6 runtime, v7 preview, new private report required");
        Path output=Path.of(args[3]).toAbsolutePath().normalize();
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private report required");
        byte[] savedBytes=OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        var saved=JSON.readTree(savedBytes);
        check(saved.path("state").asText().equals("COMPLETE") && saved.path("results").isArray(),"Complete saved run required");
        var base=ReferenceOnlyTrial.library(Path.of(args[1]));
        var preview=ReferenceOnlyTrial.library(Path.of(args[2]));
        check(base.contextUnitArchive() && !base.relationArchive() && preview.relationArchive(),"Ready v6/v7 archives required");
        List<Map<String,Object>> rows=new ArrayList<>();
        int changed=0, scored=0, missingRoles=0, discovery=0;
        for(var run:saved.path("results")) for(var call:run.path("calls")) {
            String kind=call.path("kind").asText();
            if(!Set.of("Discovery","VerificationResult").contains(kind)) continue;
            var input=call.path("input"); var raw=CandidateHypothesisTrial.raw(input).segments();
            if(kind.equals("Discovery")) {
                check(base.select(SPEECH,raw,"audit").prompt().equals(preview.select(SPEECH,raw,"audit").prompt()),
                        "Profiles must not change discovery without role query");
                discovery++; continue;
            }
            for(var c:input.path("candidates")) {
                Set<String> allowed=new HashSet<>(); c.path("segmentIds").forEach(id -> allowed.add(id.asText()));
                var candidateRaw=raw.stream().filter(s -> allowed.contains(s.id())).toList();
                check(candidateRaw.size()==allowed.size(),"Candidate raw must remain linked");
                List<CandidateReviewEngine.Quote> quotes=new ArrayList<>();
                c.path("proposedEvidence").forEach(q -> quotes.add(JSON.treeToValue(q,CandidateReviewEngine.Quote.class)));
                var roles=c.hasNonNull("sourceRelations")?JSON.treeToValue(c.get("sourceRelations"),SourceRelations.class):null;
                var proposal=new CandidateReviewEngine.Proposal(c.path("anchorId").asText(),c.path("axis").asText(),null,quotes,roles);
                var candidate=new CandidateReviewEngine.Candidate(c.path("candidateId").asText(),proposal,candidateRaw,false,false);
                var query=ContextRelationRetrieval.from(candidate);
                if(!query.present()) missingRoles++;
                // Same quote construction as the engine; no inferred correction or generated summary.
                var focus=quotes.stream().distinct().map(q -> {
                    var s=candidateRaw.stream().filter(r -> r.id().equals(q.segmentId())).findFirst().orElseThrow();
                    check(q.quote()!=null && s.text().contains(q.quote()),"Source-linked quote required");
                    return new ReviewInput.Segment(s.id(),s.type(),s.startMs(),s.endMs(),q.quote(),s.confidence());
                }).sorted(Comparator.comparingLong(ReviewInput.Segment::startMs).thenComparingLong(ReviewInput.Segment::endMs)
                        .thenComparing(ReviewInput.Segment::id).thenComparing(ReviewInput.Segment::text)).toList();
                var before=base.select(SPEECH,candidateRaw,"audit",focus);
                var ablated=preview.select(SPEECH,candidateRaw,"audit",focus);
                check(before.prompt().equals(ablated.prompt()),"No-role payload parity required");
                var after=preview.select(SPEECH,candidateRaw,"audit",focus,Set.of(),query);
                boolean same=before.prompt().equals(after.prompt());
                if(!same) changed++;
                if(after.trace().relation()!=null && after.trace().relation().selectedScores().stream().anyMatch(x -> x.relationComponent()>0)) scored++;
                var row=new LinkedHashMap<String,Object>();
                row.put("caseId",run.path("caseId").asText()); row.put("arm",run.path("arm").asText());
                row.put("repeat",run.path("repeat").asInt()); row.put("candidateId",candidate.candidateId());
                row.put("inputSha256",ReferenceOnlyTrial.sha(JSON.writeValueAsString(input).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                row.put("beforeTrace",before.trace()); row.put("afterTrace",after.trace()); row.put("payloadUnchanged",same);
                rows.add(row);
            }
        }
        check(!rows.isEmpty(),"Stored candidate inputs required");
        var report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion","context-relation-preview-audit-1"); report.put("providerCalls",0);
        report.put("semanticDetectionMeasured",false); report.put("finalDecisionsRecomputed",false);
        report.put("routingHints","DISABLED_IN_BOTH_ARMS_TO_ISOLATE_ROLE_SEARCH");
        report.put("savedResultsSha256",ReferenceOnlyTrial.sha(savedBytes));
        report.put("baselineSha256",ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(args[1]))));
        report.put("previewSha256",ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(args[2]))));
        report.put("unchangedDiscoveryInputs",discovery); report.put("candidateRequestRows",rows.size());
        report.put("rowsWithSelectedRelationBonus",scored); report.put("rowsWithoutValidRoles",missingRoles);
        report.put("changedReferencePayloads",changed); report.put("rows",rows);
        Files.writeString(output,JSON.writeValueAsString(report),StandardOpenOption.CREATE_NEW);
        System.out.println("RELATION_AUDIT rows="+rows.size()+" selectedBonus="+scored+" changed="+changed
                +" discoveryUnchanged="+discovery+" missingRoles="+missingRoles+" providerCalls=0");
    }
}
