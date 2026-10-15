package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.ContextRetrievalContrastAudit.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static com.example.oops.domain.TimelineEventType.SPEECH;

/** Prepare only. Never reads credentials, constructs an HTTP client or executes a paid request. */
public final class ContextRoutingProbePlan {
    static final String SCHEMA="context-routing-probe-plan-1";
    static final String AUTH="PENDING_FRESH_COST_AND_TEXT_CONSENT";
    static final String SCOPE="ROUTING_ONLY_SYNTHETIC_TEXT_NOT_DISCOVERY_OR_VERDICTS";
    record Task(String requestKey,String probeId,int repeat,String system,String input,String inputSha256) {}
    record Plan(String schemaVersion,String authorizationState,String scope,String plannedModel,int repeats,
                String promptSha256,String probesText,String probesSha256,String runtimeText,String runtimeSha256,
                String registryText,String registrySha256,List<Task> tasks) {}
    record Outcome(ContextMechanismRouter.Trace router,ContextMechanismRouter.LinkTrace link,
                   boolean expectedKeyProposed,boolean expectedKeyPassed,int beforeSelectedRank,int afterSelectedRank,
                   SelectionTrace beforeSelection,SelectionTrace afterSelection) {}

    /** Injected response replay through the real router validator, not an automatic classification result. */
    static final class ScriptedClient extends OpenAiClient {
        private final ContextMechanismRouter.Response response;
        private String system,input; private int calls;
        ScriptedClient(ContextMechanismRouter.Response response) { super(null,null); this.response=response; }
        @Override public <T> Optional<T> completeRoutingAsJson(String system,String input,Class<T> type) {
            check(type==ContextMechanismRouter.Response.class,"Only routing response allowed");
            this.system=system; this.input=input; calls++;
            return Optional.ofNullable(type.cast(response));
        }
        @Override public <T> Optional<T> completeAsJson(String system,String input,Class<T> type) {
            throw new IllegalStateException("Discovery/verdict calls forbidden in preparation");
        }
    }
    static ContextMechanismRouter router(ScriptedClient client,String registry) {
        var router=new ContextMechanismRouter(client,MechanismRoutingTrial.resources(registry),true,"classpath:frozen.json",1);
        router.load(); return router;
    }
    static ReviewGuidelineLibrary library(String runtime) {
        var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime),true,"classpath:frozen.json",6000,2,3000,"");
        library.load(); check(library.relationArchive(),"Ready unexpired v7 preview required"); return library;
    }
    static Plan prepare(byte[] probes,byte[] runtime,byte[] registry) throws Exception {
        String probesText=new String(probes,StandardCharsets.UTF_8), runtimeText=new String(runtime,StandardCharsets.UTF_8),
                registryText=new String(registry,StandardCharsets.UTF_8);
        var source=JSON.readValue(probes,ContextRetrievalContrastAudit.Plan.class);
        ContextRetrievalContrastAudit.validate(source,JSON.readValue(runtime,Archive.class));
        var library=library(runtimeText); List<Task> tasks=new ArrayList<>();
        for(var q:source.probes()) {
            var client=new ScriptedClient(new ContextMechanismRouter.Response(List.of()));
            var trace=router(client,registryText).session().route(library,q.raw(),q.id()).trace();
            check(trace.attempted() && trace.state().equals("EMPTY") && client.calls==1
                    && trace.availableKeys().contains(q.manualKey()),"Exactly one capturable routing request with expected key required");
            var input=JSON.readTree(client.input);
            check(input.isObject() && input.size()==2 && input.has("keys") && input.has("raw"),"Only runtime keys/raw allowed");
            for(int repeat=1;repeat<=3;repeat++) tasks.add(new Task(q.id()+"-r"+repeat,q.id(),repeat,client.system,client.input,
                    retrievalHash(client.input)));
        }
        return new Plan(SCHEMA,AUTH,SCOPE,"gpt-6-luna",3,retrievalHash(ContextMechanismRouter.PROMPT),probesText,
                ReferenceOnlyTrial.sha(probes),runtimeText,ReferenceOnlyTrial.sha(runtime),registryText,ReferenceOnlyTrial.sha(registry),List.copyOf(tasks));
    }
    /** Fresh preparation must reproduce the frozen logical requests. Labels remain local metadata only. */
    static void validate(Plan p) throws Exception {
        check(p!=null && SCHEMA.equals(p.schemaVersion()) && AUTH.equals(p.authorizationState()) && SCOPE.equals(p.scope())
                && p.repeats()==3 && "gpt-6-luna".equals(p.plannedModel()),"Known unapproved preparation required");
        check(retrievalHash(p.probesText()).equals(p.probesSha256()) && retrievalHash(p.runtimeText()).equals(p.runtimeSha256())
                && retrievalHash(p.registryText()).equals(p.registrySha256())
                && retrievalHash(ContextMechanismRouter.PROMPT).equals(p.promptSha256()),"Frozen inputs or router prompt changed");
        var current=prepare(p.probesText().getBytes(StandardCharsets.UTF_8),p.runtimeText().getBytes(StandardCharsets.UTF_8),
                p.registryText().getBytes(StandardCharsets.UTF_8));
        check(p.tasks()!=null && p.tasks().size()==current.tasks().size(),"All planned repetitions required");
        for(int i=0;i<p.tasks().size();i++) {
            var t=p.tasks().get(i); var expected=current.tasks().get(i);
            check(t!=null && expected.requestKey().equals(t.requestKey()) && expected.probeId().equals(t.probeId())
                    && expected.repeat()==t.repeat() && expected.system().equals(t.system())
                    && retrievalHash(t.input()).equals(t.inputSha256())
                    && JSON.readTree(expected.input()).equals(JSON.readTree(t.input())),"Logical router request differs from frozen probe");
        }
    }
    static Outcome replay(Probe q,ReviewGuidelineLibrary library,String registry,ContextMechanismRouter.Response response) {
        var client=new ScriptedClient(response);
        var routed=router(client,registry).session().route(library,q.raw(),q.id());
        var proposal=new CandidateReviewEngine.Proposal(q.anchorId(),"TARGET_TREATMENT",null,
                q.raw().stream().filter(s -> q.roles().selectedIds().contains(s.id()))
                    .map(s -> new CandidateReviewEngine.Quote(s.id(),s.text())).toList(),q.roles());
        var query=ContextRelationRetrieval.from(new CandidateReviewEngine.Candidate(q.id(),proposal,q.raw(),false,false));
        var evidenceIds=new HashSet<>(q.roles().selectedIds());
        var focus=q.raw().stream().filter(s -> q.roles().evaluationIds().contains(s.id())).toList();
        var passed=routed.keysFor(evidenceIds);
        var before=library.select(SPEECH,q.raw(),q.id(),focus,Set.of(),query);
        var after=library.select(SPEECH,q.raw(),q.id(),focus,passed,query);
        return new Outcome(routed.trace(),routed.linkFor(evidenceIds),routed.keys().contains(q.manualKey()),passed.contains(q.manualKey()),
                before.trace().exampleIds().indexOf(q.expectedExampleId())+1,after.trace().exampleIds().indexOf(q.expectedExampleId())+1,
                before.trace(),after.trace());
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"Probe plan, v7 runtime, registry, new private output directory required (prepare only)");
        Path output=Path.of(args[3]).toAbsolutePath().normalize();
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private preparation directory required");
        var p=prepare(OfflineDecisionContractAudit.bounded(Path.of(args[0])),OfflineDecisionContractAudit.bounded(Path.of(args[1])),
                OfflineDecisionContractAudit.bounded(Path.of(args[2])));
        validate(p);
        var manifest=Map.of("schemaVersion",SCHEMA,"authorizationState",AUTH,"providerCalls",0,"plannedRequests",p.tasks().size(),
                "model",p.plannedModel(),"repeats",p.repeats(),"routingOutputCap",1024,"scope",SCOPE,
                "planSha256",retrievalHash(JSON.writeValueAsString(p)),"verdictAccuracyMeasured",false);
        Files.createDirectory(output); Files.writeString(output.resolve("plan.json"),JSON.writeValueAsString(p),StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("manifest.json"),JSON.writeValueAsString(manifest),StandardOpenOption.CREATE_NEW);
        System.out.println("ROUTING_PROBES_PREPARED requests="+p.tasks().size()+" providerCalls=0 authorization="+AUTH);
    }
}
