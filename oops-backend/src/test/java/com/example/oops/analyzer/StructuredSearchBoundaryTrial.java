package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static com.example.oops.domain.TimelineEventType.SPEECH;

/** Explicitly approved paired text-only trial. Normal labels never enter model input or runtime. */
public final class StructuredSearchBoundaryTrial {
    static final String CONSENT = "NEW_USD1_STRUCTURED_SEARCH_TEXT_EGRESS_APPROVED";
    static final String SCHEMA_CONSENT = "NEW_USD050_DISCOVERY_SCHEMA_TEXT_EGRESS_APPROVED";
    static final String SCOPE_CONSENT = "NEW_USD050_DISCOVERY_ID_SCOPE_TEXT_EGRESS_APPROVED";
    record Boundary(String id, String expectedDecision, String boundary, List<String> speech) {}
    record Suite(String schemaVersion, String labelStatus, List<Boundary> cases) {}
    record Condition(String id, String labelStatus, String expectedDecision, String boundary, List<ReviewInput.Segment> raw) {}
    record Plan(String schemaVersion, String model, int repeats, double estimatedCapUsd,
                String sourceSha256, String fixtureSha256, String runtimeText, String runtimeSha256,
                String searchCatalogSha256, String engineContractSha256, List<Condition> conditions,
                String comparisonFactor, String outputSchemaSha256) {
        public Plan { if (comparisonFactor == null) comparisonFactor = "SEARCH"; }
    }
    static byte[] outputSchemaBytes() throws Exception {
        try(var in=StructuredSearchBoundaryTrial.class.getResourceAsStream("/candidate-discovery-output.schema.json")) {
            byte[] bytes=Objects.requireNonNull(in).readNBytes(32769);
            check(bytes.length<=32768,"Bounded output schema required");return bytes;
        }
    }
    static boolean schemaComparison(Plan p) { return "OUTPUT_CONTRACT".equals(p.comparisonFactor()); }
    static boolean scopeComparison(Plan p) { return "ID_SCOPE".equals(p.comparisonFactor()); }
    static boolean outputComparison(Plan p) { return schemaComparison(p) || scopeComparison(p); }
    static Plan scopePlan(Plan p) throws Exception {
        var result=new Plan("discovery-id-scope-trial-1",p.model(),p.repeats(),.50,p.sourceSha256(),p.fixtureSha256(),
                p.runtimeText(),p.runtimeSha256(),p.searchCatalogSha256(),p.engineContractSha256(),p.conditions(),
                "ID_SCOPE",ReferenceOnlyTrial.sha(outputSchemaBytes()));
        validate(result);return result;
    }
    static Plan schemaPlan(Plan p) throws Exception {
        var result=new Plan("structured-discovery-boundary-trial-1",p.model(),p.repeats(),.50,p.sourceSha256(),p.fixtureSha256(),
                p.runtimeText(),p.runtimeSha256(),p.searchCatalogSha256(),p.engineContractSha256(),p.conditions(),
                "OUTPUT_CONTRACT",ReferenceOnlyTrial.sha(outputSchemaBytes()));
        validate(result);return result;
    }
    static String contractHash() {
        return ReviewGuidelineLibrary.retrievalHash(CandidateReviewEngine.REVISION + CandidateReviewEngine.DISCOVERY_PROMPT
                + CandidateReviewEngine.VERIFICATION_PROMPT + CandidateReviewEngine.DISCOVERY_REPAIR_PROMPT
                + CandidateReviewEngine.TARGET_REPAIR_PROMPT + CandidateReviewEngine.CONTRAST_REPAIR_PROMPT
                + CandidateReviewEngine.ID_REPAIR_PROMPT + CandidateReviewEngine.EVIDENCE_BUDGET_REPAIR_PROMPT
                + ReviewGuidelineLibrary.CONTRACT);
    }
    static byte[] catalogBytes() throws Exception {
        try (var in = StructuredSearchBoundaryTrial.class.getResourceAsStream("/context-search-profiles.json")) {
            byte[] bytes = Objects.requireNonNull(in).readNBytes(32769);
            check(bytes.length <= 32768, "Bounded catalog required"); return bytes;
        }
    }
    static Plan prepare(byte[] source, byte[] runtime, byte[] fixture) throws Exception {
        var tree = JSON.readTree(source);
        check("groq-pipeline-audit-input-1".equals(tree.path("schemaVersion").asText()), "Frozen Groq input required");
        var suite = JSON.readValue(fixture, Suite.class);
        check("structured-search-boundaries-1".equals(suite.schemaVersion())
                && "SYNTHETIC_DRAFT_NOT_HUMAN_GOLD_NOT_PROMOTED".equals(suite.labelStatus())
                && suite.cases() != null && suite.cases().size() == 6, "Six normal drafts required");
        List<Condition> cases = new ArrayList<>();
        for (String id : List.of("v4-intimate-question-groq", "v6-group-generalization-groq")) {
            List<GroqPipelineAudit.Case> matches = new ArrayList<>();
            for (var node : tree.path("cases")) if (id.equals(node.path("id").asText()))
                matches.add(JSON.readValue(node.toString(), GroqPipelineAudit.Case.class));
            check(matches.size() == 1, "Exactly one stored excerpt required");
            var c = matches.get(0);
            cases.add(new Condition(id, c.labelStatus(), "UNREVIEWED", "Actual excerpt, not independent gold", c.raw()));
        }
        for (var b : suite.cases()) {
            check(b.id() != null && b.id().startsWith("normal-") && "PASS".equals(b.expectedDecision())
                    && b.boundary() != null && !b.boundary().isBlank() && b.speech() != null && !b.speech().isEmpty()
                    && b.speech().size() <= 6 && b.speech().stream().allMatch(s -> s != null && !s.isBlank() && s.length() <= 350),
                    "Bounded normal draft required");
            List<ReviewInput.Segment> raw = new ArrayList<>();
            for (int i = 0; i < b.speech().size(); i++) raw.add(new ReviewInput.Segment("draft-"+i, SPEECH,
                    i*4000, i*4000+3500, b.speech().get(i), null));
            cases.add(new Condition(b.id(), suite.labelStatus(), b.expectedDecision(), b.boundary(), List.copyOf(raw)));
        }
        var p = new Plan("structured-search-boundary-trial-1", "gpt-6-luna", 2, 1,
                ReferenceOnlyTrial.sha(source), ReferenceOnlyTrial.sha(fixture), new String(runtime, StandardCharsets.UTF_8),
                ReferenceOnlyTrial.sha(runtime), ReferenceOnlyTrial.sha(catalogBytes()), contractHash(), List.copyOf(cases),"SEARCH",null);
        validate(p); return p;
    }
    static void validate(Plan p) throws Exception {
        boolean schema=outputComparison(p);
        check((schema ? (scopeComparison(p)?"discovery-id-scope-trial-1":"structured-discovery-boundary-trial-1").equals(p.schemaVersion()) && p.estimatedCapUsd()==.50
                : "SEARCH".equals(p.comparisonFactor()) && "structured-search-boundary-trial-1".equals(p.schemaVersion())
                        && p.estimatedCapUsd()==1 && p.outputSchemaSha256()==null)
                && "gpt-6-luna".equals(p.model()) && p.repeats() == 2 && p.conditions() != null && p.conditions().size() == 8
                && p.conditions().stream().map(Condition::id).distinct().count() == 8, "Known 32-slot plan required");
        check(ReferenceOnlyTrial.sha(p.runtimeText().getBytes(StandardCharsets.UTF_8)).equals(p.runtimeSha256())
                && ReferenceOnlyTrial.sha(catalogBytes()).equals(p.searchCatalogSha256())
                && contractHash().equals(p.engineContractSha256()), "Frozen inputs/contracts changed");
        if(schema) check(ReferenceOnlyTrial.sha(outputSchemaBytes()).equals(p.outputSchemaSha256()),"Output schema changed");
        for (var c : p.conditions()) {
            check(c.raw() != null && !c.raw().isEmpty() && c.raw().size() <= 80
                    && c.raw().stream().allMatch(s -> s.type() == SPEECH && s.text() != null && !s.text().isBlank()), "Speech only");
            new ReviewInput(c.raw());
        }
        for (boolean on : List.of(false,true)) check(library(p,on).relationArchive(), "Ready v7 required");
    }
    static ReviewGuidelineLibrary library(Plan p, boolean on) {
        var l = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(p.runtimeText()), true,
                "classpath:frozen.json", 6000, 3, 3000, "", on);
        l.load(); return l;
    }
    static List<String> arms(int repeat) { return repeat == 1 ? List.of("OFF","ON") : List.of("ON","OFF"); }
    static List<String> arms(Plan p,int repeat) {
        if(scopeComparison(p)) return repeat==1?List.of("UNSCOPED","SCOPED"):List.of("SCOPED","UNSCOPED");
        return !schemaComparison(p)?arms(repeat):repeat==1?List.of("JSON","STRICT"):List.of("STRICT","JSON");
    }
    static boolean searchEnabled(Plan p,String arm) { return outputComparison(p) || "ON".equals(arm); }
    static boolean strictEnabled(Plan p,String arm) { return scopeComparison(p) || schemaComparison(p) && "STRICT".equals(arm); }
    static String consent(Plan p) { return scopeComparison(p)?SCOPE_CONSENT:schemaComparison(p)?SCHEMA_CONSENT:CONSENT; }
    static String runMode(Plan p) { return scopeComparison(p)?"run-scope":schemaComparison(p)?"run-schema":"run"; }
    static final class Budget {
        final Path output; final double cap; int attempts; double reserved; boolean exhausted;
        Budget(Path output) { this(output,1); }
        Budget(Path output,double cap) { check(cap==1 || cap==.50,"Known fresh budget required");this.output=output;this.cap=cap; }
        void reserve(int bytes) throws Exception {
            double next = (bytes+1024)*.10/1_000_000 + 8000*.50/1_000_000;
            if (attempts >= 160 || reserved+next > cap) { exhausted=true; throw new IllegalArgumentException("Reservation exhausted"); }
            attempts++; reserved+=next;
            Files.writeString(output.resolve("reservation.json"), JSON.writeValueAsString(Map.of("newEstimatedCapUsd",cap,
                    "reservedEstimatedUsd",reserved,"httpAttempts",attempts,"maxHttpAttempts",160,
                    "inputPer1m",.10,"outputPer1m",.50,"actualBillingConfirmed",false)));
        }
    }
    static void save(Path output, Plan plan, String sha, List<Map<String,Object>> rows, Budget budget, double cost, String state) throws Exception {
        Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(Map.of("schemaVersion",scopeComparison(plan)
                        ?"discovery-id-scope-results-1":schemaComparison(plan)?"structured-discovery-results-1":"structured-search-results-1","comparisonFactor",plan.comparisonFactor(),
                "planSha256",sha,"state",state,"results",rows,"httpAttempts",budget.attempts,
                "reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"actualBillingConfirmed",false)));
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 5 && Set.of("prepare","run","prepare-schema","run-schema","prepare-scope","run-scope").contains(args[0]),
                "prepare[-schema/-scope] source runtime fixture NEW-directory OR run[-schema/-scope] plan approved-sha consent NEW-directory");
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root), "New private output directory required");
        if (args[0].startsWith("prepare")) {
            var p = prepare(OfflineDecisionContractAudit.bounded(Path.of(args[1])),
                    OfflineDecisionContractAudit.bounded(Path.of(args[2])), OfflineDecisionContractAudit.bounded(Path.of(args[3])));
            if(args[0].equals("prepare-schema")) p=schemaPlan(p);
            if(args[0].equals("prepare-scope")) p=scopePlan(p);
            Files.createDirectory(output);
            byte[] bytes = JSON.writeValueAsBytes(p); Files.write(output.resolve("plan.json"), bytes, StandardOpenOption.CREATE_NEW);
            List<Map<String,Object>> rows = new ArrayList<>();
            for (var c : p.conditions()) for (String arm:arms(p,1)) {
                var l = library(p,searchEnabled(p,arm)); var selections = new ArrayList<Object>();
                for (var batch : TextReviewBatchPlanner.plan(new ReviewInput(c.raw()), SPEECH, 3)) {
                    var raw = new ArrayList<>(batch.primary()); raw.addAll(batch.context());
                    var s = l.select(SPEECH, raw, "discovery-"+(selections.size()+1));
                    selections.add(Map.of("trace",s.trace(),"referencePrompt",s.prompt()));
                }
                rows.add(Map.of("caseId",c.id(),"arm",arm,"selections",selections));
            }
            Files.writeString(output.resolve("retrieval.json"), JSON.writeValueAsString(Map.of("providerCalls",0,"results",rows)));
            System.out.println("STRUCTURED_PREPARED conditions=8 slots=32 providerCalls=0 planSha256="+ReferenceOnlyTrial.sha(bytes)); return;
        }
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        check(ReferenceOnlyTrial.sha(bytes).equals(args[2]),"Frozen plan hash required");
        var p = JSON.readValue(bytes, Plan.class); validate(p);
        check(runMode(p).equals(args[0]) && consent(p).equals(args[3]),
                "Fresh factor-specific budget/text consent required");
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "OPENAI_API_KEY required; never print");
        Files.createDirectory(output); Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var props = new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",p.model(),"none",Duration.ofSeconds(90),
                new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var budget = new Budget(output,p.estimatedCapUsd());
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(90));
        var builder = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] guarded = V7RoutingVideoTrial.guardBody(body,request.getURI().toString());
                    try { budget.reserve(guarded.length); } catch (java.io.IOException ex) { throw ex; }
                    catch (Exception ex) { throw new IllegalArgumentException("Budget reservation failed",ex); }
                    request.getHeaders().setContentLength(guarded.length); return execution.execute(request,guarded);
                });
        if(props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        var rest=builder.build();
        var jsonClient=new MechanismRoutingTrial.Client(rest,props);
        var strictClient=new MechanismRoutingTrial.Client(rest,props,true);
        var unscopedClient=new MechanismRoutingTrial.Client(rest,props,true,false);
        var off = library(p,false); var on = library(p,true);
        List<Map<String,Object>> rows = new ArrayList<>(); double cost=0;
        save(output,p,args[2],rows,budget,cost,"RUNNING");
        try {
            if(outputComparison(p)) {
                var c=p.conditions().stream().filter(x -> x.id().equals("normal-underwear-product")).findFirst().orElseThrow();
                strictClient.beginVideo(0L);strictClient.beginAnalyzer("schema-preflight");
                var probe=CandidateReviewEngine.run(strictClient,MechanismRoutingTrial.context(
                        new MechanismRoutingTrial.Condition(c.id(),c.labelStatus(),c.raw())),24,null,null,on,null,0,false,null);
                var usage=strictClient.videoUsage();cost+=usage.costUsd();
                boolean ready=probe.status().name().equals("SUCCESS") && probe.findings().isEmpty() && probe.unassessedSegmentIds().isEmpty()
                        && strictClient.failureCount()==0 && usage.calls()==strictClient.calls.size() && !budget.exhausted;
                Files.writeString(output.resolve("preflight.json"),JSON.writeValueAsString(Map.of("state",ready?"PASSED":"FAILED",
                        "caseId",c.id(),"status",probe.status(),"calls",List.copyOf(strictClient.calls),"usage",usage,
                        "diagnostics",probe.diagnostics(),"unassessedSegmentIds",probe.unassessedSegmentIds(),"findings",probe.findings().size())));
                strictClient.endVideo();save(output,p,args[2],rows,budget,cost,"RUNNING");
                System.out.println("SCHEMA_PREFLIGHT "+(ready?"PASSED":"FAILED"));
                check(ready,"Schema preflight failed; do not start comparison or auto-fallback");
            }
            for(int repeat=1;repeat<=p.repeats();repeat++) for(var c:p.conditions()) for(String arm:arms(p,repeat)) {
                var client=scopeComparison(p) && "UNSCOPED".equals(arm)?unscopedClient:strictEnabled(p,arm)?strictClient:jsonClient;
                client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("structured-search-boundary");
                long started = System.nanoTime();
                var condition = new MechanismRoutingTrial.Condition(c.id(),c.labelStatus(),c.raw());
                var result = CandidateReviewEngine.run(client,MechanismRoutingTrial.context(condition),24,
                        null,null,searchEnabled(p,arm)?on:off,null,0,false,null);
                var usage = client.videoUsage(); cost+=usage.costUsd();
                var row = new LinkedHashMap<String,Object>(); row.put("caseId",c.id()); row.put("arm",arm); row.put("repeat",repeat);
                row.put("labelStatus",c.labelStatus()); row.put("expectedDecision",c.expectedDecision()); row.put("status",result.status());
                row.put("comparisonFactor",p.comparisonFactor());row.put("structuredSearchEnabled",searchEnabled(p,arm));
                row.put("strictDiscoveryOutputEnabled",strictEnabled(p,arm));
                row.put("discoveryIdScopeEnabled",strictEnabled(p,arm) && !"UNSCOPED".equals(arm));
                row.put("elapsedMs",(System.nanoTime()-started)/1_000_000); row.put("diagnostics",result.diagnostics());
                row.put("evaluations",result.evaluations()); row.put("unassessedSegmentIds",result.unassessedSegmentIds());
                row.put("calls",List.copyOf(client.calls)); row.put("usage",usage);
                row.put("findings",result.findings().stream().map(f -> Map.of("category",f.getCategory(),"text",f.primaryText(),
                        "reason",f.getReason(),"target",f.getTarget()==null?"":f.getTarget(),"supports",f.validatedSupports())).toList());
                rows.add(row); save(output,p,args[2],rows,budget,cost,"RUNNING");
                boolean ok = !budget.exhausted && client.failureCount()==0 && usage.calls()==client.calls.size();
                client.endVideo();
                System.out.println("STRUCTURED_TRIAL "+rows.size()+"/32 "+c.id()+" "+arm+" repeat="+repeat
                        +" findings="+result.findings().size()+" status="+result.status());
                check(ok,"Stop on provider/budget/usage failure; no experiment restart");
            }
            save(output,p,args[2],rows,budget,cost,"COMPLETE");
        } catch(Exception ex) { save(output,p,args[2],rows,budget,cost,"STOPPED"); throw ex; }
    }
}
