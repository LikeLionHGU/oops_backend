package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;

/** Re-select references for saved real discovery inputs/routes. No provider, discovery or verdict calls. */
public final class RoutingPriorityAudit {
    public static void main(String[] args) throws Exception {
        check(args.length==3,"Frozen routing plan, saved complete results, new private audit file required");
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private audit required");
        byte[] planBytes = OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        byte[] resultBytes = Files.readAllBytes(Path.of(args[1]));
        check(resultBytes.length <= 8_388_608,"Results too large");
        var p = JSON.readValue(planBytes,MechanismRoutingTrial.Plan.class);
        MechanismRoutingTrial.validate(p);
        var saved = JSON.readTree(resultBytes);
        check(saved.path("schemaVersion").asText().equals(MechanismRoutingTrial.SCHEMA)
                && saved.path("state").asText().equals("COMPLETE")
                && saved.path("planSha256").asText().equals(ReferenceOnlyTrial.sha(planBytes))
                && saved.path("results").size()==16,"Known complete frozen comparison required");
        var library = MechanismRoutingTrial.library(p);
        List<Map<String,Object>> rows = new ArrayList<>();
        int changed=0, offChanged=0;
        for (var row : saved.path("results")) {
            var diagnostics = row.path("diagnostics").path("candidatePipeline");
            int discovery=0;
            for (var call : row.path("calls")) if (call.path("kind").asText().equals("Discovery")) {
                String key = "discovery-" + ++discovery;
                var raw = CandidateHypothesisTrial.raw(call.path("input"));
                Set<String> hints = new LinkedHashSet<>();
                for (var route : diagnostics.path("mechanismRouting")) if (route.path("requestKey").asText().equals(key))
                    route.path("routes").forEach(r -> hints.add(r.path("key").asText()));
                List<String> before = new ArrayList<>(); int found=0;
                for (var s : diagnostics.path("contextSelections")) if (s.path("requestKey").asText().equals(key)) {
                    found++; s.path("exampleIds").forEach(id -> before.add(id.asText()));
                }
                check(found==1,"Unique saved discovery selection required");
                var after = library.select(TimelineEventType.SPEECH,raw.segments(),key,List.of(),hints);
                String oldPayload = call.path("system").asText();
                String marker="contextReference=";
                check(oldPayload.lastIndexOf(marker)>=0,"Saved reference payload required");
                boolean samePayload = JSON.readTree(oldPayload.substring(oldPayload.lastIndexOf(marker)+marker.length()))
                        .equals(JSON.readTree(after.prompt().substring(after.prompt().lastIndexOf(marker)+marker.length())));
                check(!hints.isEmpty() || samePayload,"Empty routing must preserve the saved lexical payload");
                if (!samePayload) { changed++; if (row.path("arm").asText().equals("OFF")) offChanged++; }
                rows.add(Map.of("caseId",row.path("caseId").asText(),"arm",row.path("arm").asText(),
                        "repeat",row.path("repeat").asInt(),"requestKey",key,"savedRoutingKeys",hints,
                        "beforeExampleIds",before,"afterTrace",after.trace(),"payloadUnchanged",samePayload));
                System.out.println("ROUTING_PRIORITY "+row.path("caseId").asText()+" "+row.path("arm").asText()
                        +" repeat="+row.path("repeat").asInt()+" "+key+" before="+before+" after="+after.trace().exampleIds());
            }
        }
        check(rows.size()==24 && offChanged==0,"All saved discovery calls and unchanged OFF required");
        var report = Map.of("schemaVersion","routing-priority-audit-1","providerCalls",0,"rows",rows,
                "changedPayloads",changed,"offChangedPayloads",offChanged,
                "planSha256",ReferenceOnlyTrial.sha(planBytes),"resultsSha256",ReferenceOnlyTrial.sha(resultBytes),
                "finalDecisionsRecomputed",false);
        Files.writeString(output,JSON.writeValueAsString(report),StandardOpenOption.CREATE_NEW);
        System.out.println("ROUTING_PRIORITY_COMPLETE rows="+rows.size()+" changed="+changed+" OFFchanged="+offChanged+" providerCalls=0");
    }
}
