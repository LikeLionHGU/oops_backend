package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Fresh USD0.50 stored-text discovery -> verification -> finding run. No media or database writes. */
public final class CurrentStoredTextPipelineTrial {
    static final String CONSENT="NEW_USD050_STORED_TEXT_PIPELINE_20261023";
    record Plan(String version,double cap,int maxAttempts,CurrentContextReferenceTrial.Plan source,
                String runtime,Map<String,String> implementation) {}
    static Plan prepare() throws Exception {
        Path root=Path.of("../datasets/controversy");
        var hashes=new TreeMap<>(CurrentContextReferenceTrial.implementation());
        for(var file:List.of(Path.of("src/main/java/com/example/oops/analyzer/ContextBoundarySupplement.java"),
                Path.of("src/test/java/com/example/oops/analyzer/CurrentStoredTextPipelineTrial.java")))
            hashes.put(file.getFileName().toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(file)));
        return new Plan("current-stored-text-pipeline-1",.50,24,CurrentContextReferenceTrial.prepare(root),
                Files.readString(root.resolve("guidelines/runtime.json")),hashes);
    }
    static void validate(Plan plan) throws Exception {
        check(plan.version().equals("current-stored-text-pipeline-1") && plan.cap()==.50 && plan.maxAttempts()==24,"Fixed pipeline cap required");
        CurrentContextReferenceTrial.validate(plan.source());
        check(ReferenceOnlyTrial.sha(plan.runtime().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(plan.source().hashes().get("data.runtime")),"Frozen runtime required");
    }
    public static void main(String[] args) throws Exception {
        check(args.length==2&&args[0].equals("prepare") || args.length==4&&args[0].equals("run"),"prepare NEW-directory OR run plan sha NEW-directory");
        var root=Path.of("../datasets/controversy/experiments").toRealPath();var output=Path.of(args[args.length-1]).toAbsolutePath();
        check(!Files.exists(output)&&output.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")) {
            var plan=prepare();validate(plan);byte[] bytes=JSON.writeValueAsBytes(plan);
            Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("PREPARED analyses=3 maxHttp=24 cap=.50 sha="+ReferenceOnlyTrial.sha(bytes));return;
        }
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));check(ReferenceOnlyTrial.sha(bytes).equals(args[2]),"Frozen hash mismatch");
        var plan=JSON.readValue(bytes,Plan.class);validate(plan);
        check(CONSENT.equals(System.getenv("STORED_PIPELINE_TRIAL_CONSENT")),"Fresh pipeline consent required");
        var fresh=prepare();
        check(fresh.implementation().equals(plan.implementation()) && fresh.source().hashes().equals(plan.source().hashes()),"Source or implementation drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),output,args[2],CONSENT,24);
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var props=new OpenAiProperties(key,null,null,"https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(60),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var budget=new TextBoundaryTrial.Budget(.50,.10,.50,output);
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(60));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution)-> {
                    byte[] bounded=V7RoutingVideoTrial.guardBody(body,request.getURI().toString());
                    try {check(budget.attempts<24,"24 HTTP attempts maximum");budget.reserve(bounded.length);}catch(Exception e){throw new IllegalArgumentException("Budget guard",e);}
                    request.getHeaders().setContentLength(bounded.length);return execution.execute(request,bounded);
                }).build();
        var client=new MechanismRoutingTrial.Client(rest,props);
        var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(plan.runtime()),true,"classpath:frozen.json",6000,3,3000,"",true);
        library.load();var rows=new ArrayList<Map<String,Object>>();double cost=0;
        save(output,rows,budget,cost,"RUNNING");
        try {
            for(var request:plan.source().requests()) {
                if(request.repeat()!=1) continue;
                var input=JSON.readTree(request.input());
                var review=CandidateHypothesisTrial.raw(input);
                var context=new AnalysisContext(Video.builder().filename("stored-context-"+request.caseId()).build(),ContentGenre.GENERAL,List.of(),List.of(),review);
                client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("stored-text-pipeline");
                long start=System.nanoTime();
                // No seeded candidate, hypothesis, expected label or forced finding is passed to the engine.
                var result=CandidateReviewEngine.run(client,context,new CandidateReviewEngine.RunServices(null,null,library,null,null),
                        new CandidateReviewEngine.RunOptions(24,0,false,new CandidateReviewEngine.VerificationFeatures(false,false,false),false));
                var usage=client.videoUsage();cost+=usage.costUsd();
                var row=new LinkedHashMap<String,Object>();row.put("caseId",request.caseId());row.put("expectedDecision",request.expectedDecision());
                row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);row.put("status",result.status());row.put("diagnostics",result.diagnostics());
                row.put("evaluations",result.evaluations());row.put("unassessedSegmentIds",result.unassessedSegmentIds());row.put("calls",List.copyOf(client.calls));row.put("usage",usage);
                row.put("findings",result.findings().stream().map(f->Map.of("category",f.getCategory(),"text",f.primaryText(),"reason",f.getReason(),
                        "startMs",f.getStartMs(),"endMs",f.getEndMs(),"target",f.getTarget()==null?"":f.getTarget(),"supports",f.validatedSupports())).toList());rows.add(row);
                boolean ok=!budget.exhausted&&cost<=.50&&client.failureCount()==0&&usage.calls()==client.calls.size();
                save(output,rows,budget,cost,ok?"RUNNING":"STOPPED");client.endVideo();
                System.out.println("STORED_PIPELINE "+rows.size()+"/3 "+request.caseId()+" findings="+result.findings().size()+" status="+result.status());
                check(ok,"Provider or budget failure; no retry");
            }
            save(output,rows,budget,cost,"COMPLETE");
        }catch(Exception e){save(output,rows,budget,cost,"STOPPED");throw e;}
    }
    static void save(Path output,List<Map<String,Object>> rows,TextBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"scope","STORED_EXCERPT_TEXT_PIPELINE_NOT_FULL_VIDEO_OR_HTTP_REPORT",
                "results",rows,"httpAttempts",budget.attempts,"reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"actualBillingConfirmed",false,"approvedCapUsd",.50)));
    }
}
