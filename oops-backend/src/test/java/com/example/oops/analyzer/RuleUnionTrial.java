package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** One approved plan, one execution, no production activation or media requests. */
public final class RuleUnionTrial {
    static final String SHA="fea93de012c9e42d62f5c072d322323fe3699cab68c55b05480e8cfb864c2941";
    static final String CONSENT="NEW_USD050_RULE_UNION_TEXT_EGRESS_APPROVED_20261020";
    static RuleUnionTrialPlan.Plan authorize(byte[] bytes,String sha,String consent) throws Exception {
        check(SHA.equals(sha) && SHA.equals(ReferenceOnlyTrial.sha(bytes)) && CONSENT.equals(consent),"Exact fresh plan consent required");
        var p=JSON.readValue(bytes,RuleUnionTrialPlan.Plan.class);
        check(p.repeats()==2 && p.engineExecutions()==16 && p.proposedEstimatedCapUsd()==.50
                && p.proposedMaxHttpAttempts()==80 && "gpt-6-luna".equals(p.model()),"Approved scope required");
        for(var entry:p.implementationHashes().entrySet())
            check(entry.getValue().equals(ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(entry.getKey())))),"Frozen implementation changed");
        check(p.sourceHashes().get("runtime").equals(ReferenceOnlyTrial.sha(p.runtimeText().getBytes(java.nio.charset.StandardCharsets.UTF_8))),"Runtime hash changed");
        return p;
    }
    static void write(Path path,Object value) throws Exception {
        if(!Files.exists(path)) Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        check(!Files.isSymbolicLink(path),"No symlink output");
        Files.write(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }
    static final class Budget {
        int attempts; double reserved; boolean exhausted;
        synchronized void reserve(int bytes) {
            double next=(bytes+1024)*.10/1_000_000+8000*.50/1_000_000;
            if(attempts>=80 || reserved+next>.50) { exhausted=true;throw new IllegalArgumentException("Trial reservation exhausted"); }
            attempts++; reserved+=next;
        }
    }
    static void save(Path output,List<Map<String,Object>> rows,Budget b,double cost,String state) throws Exception {
        write(output.resolve("results.json"),Map.of("schemaVersion","rule-union-results-1","planSha256",SHA,
                "state",state,"results",rows,"httpAttempts",b.attempts,"reservedEstimatedUsd",b.reserved,
                "usageEstimatedUsd",cost,"actualBillingConfirmed",false));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"plan approved-sha fresh-consent NEW-output-directory");
        Path planFile=Path.of(args[0]);
        byte[] bytes=OfflineDecisionContractAudit.bounded(planFile);
        var p=authorize(bytes,args[1],args[2]);
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        Path output=Path.of(args[3]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().equals(root),"New direct private directory required");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(planFile,output,SHA,CONSENT,80);
        Files.createDirectory(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        write(output.resolve("plan.json"),p);
        Map<String,String> executionHashes=new TreeMap<>();
        for(String name:List.of("RuleUnionTrial","DefaultVerificationTrial","ReferenceOnlyTrial")) {
            String path="src/test/java/com/example/oops/analyzer/"+name+".java";
            executionHashes.put(path,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(path))));
        }
        write(output.resolve("execution.json"),Map.of("planSha256",SHA,"consent",CONSENT,"executionHashes",executionHashes,
                "estimatedCapUsd",.50,"maxHttpAttempts",80,"maxOutputTokens",8000,"inputPer1m",.10,"outputPer1m",.50,
                "pricingIsConfiguredEstimateNotBillingGuarantee",true));
        var budget=new Budget();
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",p.model(),"none",Duration.ofSeconds(90),
                new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(90));
        var builder=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] guarded=V7RoutingVideoTrial.guardBody(body,request.getURI().toString());
                    budget.reserve(guarded.length);
                    try { write(output.resolve("reservation.json"),Map.of("httpAttempts",budget.attempts,"reservedEstimatedUsd",budget.reserved,
                            "estimatedCapUsd",.50,"maxHttpAttempts",80,"actualBillingConfirmed",false)); }
                    catch(Exception ex) { throw new IllegalArgumentException("Reservation persistence failed"); }
                    request.getHeaders().setContentLength(guarded.length);return execution.execute(request,guarded);
                });
        if(props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        var client=new MechanismRoutingTrial.Client(builder.build(),props,true);
        List<Map<String,Object>> rows=new ArrayList<>(); double cost=0;
        save(output,rows,budget,cost,"RUNNING");
        try {
            for(int repeat=1;repeat<=2;repeat++) for(var condition:p.conditions())
                for(String arm:StructuredSearchBoundaryTrial.arms(repeat)) {
                    var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(p.runtimeText()),true,
                            "classpath:frozen.json",6000,3,3000,"",true);library.load();
                    check(library.relationArchive(),"Ready runtime required");
                    client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("rule-union-comparison");
                    long started=System.nanoTime();
                    var result=CandidateReviewEngine.run(client,MechanismRoutingTrial.context(condition),24,null,null,library,
                            null,0,false,null,new CandidateReviewEngine.VerificationFeatures(false,false,false),"ON".equals(arm));
                    var usage=client.videoUsage();cost+=usage.costUsd();
                    var row=new LinkedHashMap<String,Object>();
                    row.put("caseId",condition.id());row.put("arm",arm);row.put("repeat",repeat);row.put("labelStatus",condition.labelStatus());
                    row.put("status",result.status());row.put("elapsedMs",(System.nanoTime()-started)/1_000_000);
                    row.put("diagnostics",result.diagnostics());row.put("evaluations",result.evaluations());
                    row.put("unassessedSegmentIds",result.unassessedSegmentIds());row.put("usage",usage);row.put("calls",List.copyOf(client.calls));
                    row.put("findings",result.findings().stream().map(f -> Map.of("category",f.getCategory(),"text",f.primaryText(),
                            "reason",f.getReason(),"target",f.getTarget()==null?"":f.getTarget(),"supports",f.validatedSupports())).toList());
                    rows.add(row);save(output,rows,budget,cost,"RUNNING");
                    boolean ok=!budget.exhausted && client.failureCount()==0 && usage.calls()==client.calls.size();client.endVideo();
                    System.out.println("RULE_UNION "+rows.size()+"/16 "+condition.id()+" "+arm+" repeat="+repeat
                            +" findings="+result.findings().size()+" status="+result.status());
                    check(ok,"Provider/budget/usage failure: stop without resuming");
                }
            save(output,rows,budget,cost,"COMPLETE");
        } catch(Exception ex) {save(output,rows,budget,cost,"STOPPED");throw ex;}
    }
}
