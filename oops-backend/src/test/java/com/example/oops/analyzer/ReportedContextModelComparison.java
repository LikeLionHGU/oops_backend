package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ConfrontationStageTrial.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Explicit single-experiment permission; research draft is not promoted or relabelled. */
public final class ReportedContextModelComparison {
    static final int LIMIT=8;static final double APPROVED_CAP=.25;
    static final String CONSENT="REPORTED_REFERENCE_REVIEW_TWO_USD025_SINGLE_EXPERIMENT";
    static final String FAMILY=ResearchCaseAliases.value("familyId"),ID=ResearchCaseAliases.value("caseId");
    record Run(boolean corrected,Frozen initial,String allowedReference) {}
    record Plan(String runtimePath,String runtimeSha,String proposalPath,String proposalSha,Map<String,String> implementation,
                Condition input,List<Run> runs,double maximumReservation) {}
    static ReviewGuidelineLibrary library(Archive a) {
        String excluded=a.examples().stream().map(ContextExample::familyId).filter(x->!x.equals(FAMILY)).distinct().sorted().collect(java.util.stream.Collectors.joining(","));
        var lib=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(JSON.writeValueAsString(a)),true,"classpath:frozen.json",6000,1,3000,excluded,true,true);
        lib.load();check(lib.trace(TimelineEventType.SPEECH).state().equals("READY_WORKING_REFERENCE"),"Archive unavailable");return lib;
    }
    static String jsonObject(String system){
        String mark="contextReference=";int start=system.indexOf(mark);check(start>=0,"Reference payload required");start+=mark.length();
        boolean quoted=false,escaped=false;int depth=0;
        for(int i=start;i<system.length();i++){char c=system.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;else if(c=='{'||c=='[')depth++;
            else if(c=='}'||c==']'){depth--;if(depth==0)return system.substring(start,i+1);}}
        throw new IllegalArgumentException("Incomplete reference payload");
    }
    static void restrictRequest(byte[] body,String expectedReference){
        var tree=JSON.readTree(body);var system=tree.path("messages").get(0).path("content").asText();
        var refs=JSON.readTree(jsonObject(system)).path("referenceContexts");
        check(refs.isArray()&&refs.size()<=1,"More than one reference forbidden");
        if(!refs.isEmpty())check(refs.get(0).equals(JSON.readTree(expectedReference)),"Unapproved reference forbidden");
    }
    static Plan prepare()throws Exception{
        Path root=Path.of("../datasets/controversy"),rp=root.resolve("guidelines/runtime.json").toRealPath(),
                pp=ResearchCaseAliases.datasetFile("correctionProposalPath").toRealPath();
        byte[] rb=Files.readAllBytes(rp),pb=Files.readAllBytes(pp);var archive=JSON.readValue(rb,Archive.class);var proposal=JSON.readTree(pb);
        check(proposal.path("status").asText().equals("RESEARCH_ONLY_NOT_APPLIED")&&!proposal.path("runtimeEligible").asBoolean()
                &&!proposal.path("externalModelAuthorized").asBoolean(),"Research-only source required");
        var candidate=ReportedContextCorrectionImpactTest.corrected(archive,proposal);
        var c=new Condition("reported-context-synthetic-review","USER_APPROVED_DEVELOPMENT_ONLY","REVIEW_REQUIRED",List.of(
                new ReviewInput.Segment("stt-index-0",TimelineEventType.SPEECH,0,1500,"경제적 이유로 부모와 같이 살고 있어요.",null),
                new ReviewInput.Segment("stt-index-1",TimelineEventType.SPEECH,2000,3500,"그건 네가 쓸데없이 돈을 쓰는 탓이야. 생활이 어려운 건 네 선택 때문이야.",null)),
                new Seed("stt-index-1","EXPRESSION_CONTENT","diagnostic",List.of("stt-index-1")));
        var runs=new ArrayList<Run>();for(boolean corrected:List.of(false,true)){
            var lib=library(corrected?candidate:archive);var client=new SyntheticReferenceComparison.TrialClient(RestClient.create(),c,false,true,new Guard(),null,"offline-no-key");
            pipeline(client,c,lib);check(client.captured.size()==1,"Single discovery expected");var initial=client.captured.get(0);
            var refs=JSON.readTree(jsonObject(initial.system())).path("referenceContexts");check(refs.isArray()&&refs.size()==1,"Target reference not selected");
            var expected=(corrected?candidate:archive).examples().stream().filter(x->x.id().equals(ID)).findFirst().orElseThrow();
            check(refs.get(0).equals(JSON.valueToTree(new SelectedExample(expected.mechanismIds(),expected.context()))),"Unexpected selected reference");
            runs.add(new Run(corrected,initial,JSON.writeValueAsString(refs.get(0))));
        }
        check(runs.get(0).initial().input().equals(runs.get(1).initial().input()),"Inputs differ");
        var impl=new TreeMap<>(implementation());for(String f:List.of("SyntheticReferenceComparison.java","ReportedContextCorrectionImpactTest.java","ReportedContextModelComparison.java")){
            var path=Path.of("src/test/java/com/example/oops/analyzer",f);impl.put(path.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(path)));}
        double max=LIMIT*reservation(MAX_BYTES);check(max<=APPROVED_CAP,"Reservation cap");
        return new Plan(rp.toString(),ReferenceOnlyTrial.sha(rb),pp.toString(),ReferenceOnlyTrial.sha(pb),impl,c,runs,max);
    }
    static void save(Path out,String sha,String state,Guard guard,List<Map<String,Object>> rows)throws Exception{
        Files.writeString(out.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,"attempts",guard.attempts,
                "reservedEstimatedUsd",guard.reserved,"actualBillingConfirmed",false,"scope","ONE_APPROVED_RESEARCH_EXPERIMENT_NOT_RUNTIME_OR_ORIGINAL_VIDEO","results",rows)));
    }
    public static void main(String[] args)throws Exception{
        check(args.length==4,"Invalid arguments");Path root=Path.of("../datasets/controversy/experiments").toRealPath(),out=Path.of(args[3]).toAbsolutePath().normalize();
        check(!Files.exists(out)&&out.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")){var p=prepare();byte[] bytes=JSON.writeValueAsBytes(p);Files.createDirectory(out);
            Files.write(out.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);System.out.println("REPORTED_PLAN calls=0 reserve="+p.maximumReservation()+" sha="+ReferenceOnlyTrial.sha(bytes));return;}
        check(args[0].equals("run"),"Invalid mode");byte[] b=OfflineDecisionContractAudit.bounded(Path.of(args[1]));String sha=ReferenceOnlyTrial.sha(b);
        check(sha.equals(args[2])&&CONSENT.equals(System.getenv("REPORTED_REFERENCE_CONSENT")),"Explicit plan consent required");var p=JSON.readValue(b,Plan.class);
        check(JSON.valueToTree(p).equals(JSON.valueToTree(prepare())),"Plan drift");String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),out,sha,CONSENT,LIMIT);Files.createDirectory(out);var guard=new Guard();var rows=new ArrayList<Map<String,Object>>();
        var archive=JSON.readValue(Files.readAllBytes(Path.of(p.runtimePath())),Archive.class);
        var candidate=ReportedContextCorrectionImpactTest.corrected(archive,JSON.readTree(Files.readAllBytes(Path.of(p.proposalPath()))));save(out,sha,"RUNNING",guard,rows);
        try{for(var run:p.runs()){
            guard.beginCase();var lib=library(run.corrected()?candidate:archive);
            var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(60));
            var rest=RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                    .requestInterceptor((req,data,execution)->{try{if(guard.attempts>=LIMIT)throw new IllegalStateException("HTTP cap");
                        restrictRequest(data,run.allowedReference());byte[] accepted=guard.accept(data,req.getURI().toString());
                        check(guard.reserved<=APPROVED_CAP,"Budget cap");save(out,sha,"RUNNING",guard,rows);
                        req.getHeaders().setContentLength(accepted.length);return execution.execute(req,accepted);
                    }catch(Exception e){guard.blocked=true;throw new IllegalStateException("Request scope or execution failed",e);}}).build();
            var frozen=new SyntheticReferenceComparison.Run(p.input().id(),false,run.initial());
            var client=new SyntheticReferenceComparison.TrialClient(rest,p.input(),false,false,guard,frozen,key);client.beginVideo(0L);client.beginAnalyzer("reported-reference");
            var result=pipeline(client,p.input(),lib);var row=new LinkedHashMap<String,Object>();row.put("corrected",run.corrected());row.put("expectedDecision",p.input().expectedDecision());
            row.put("status",result.status());row.put("diagnostics",result.diagnostics());row.put("evaluations",result.evaluations());row.put("usage",client.videoUsage());
            row.put("findings",result.findings().stream().map(f->Map.of("text",f.primaryText(),"reason",f.getReason())).toList());rows.add(row);
            boolean ok=!guard.blocked&&client.failureCount()==0&&"SUCCESS".equals(result.status().toString());if(!ok)guard.blocked=true;
            save(out,sha,ok?"RUNNING":"STOPPED",guard,rows);client.endVideo();System.out.println("REPORTED_RESULT corrected="+run.corrected()+" status="+result.status()+" findings="+result.findings().size());
            check(ok,"Provider, transport, PARTIAL, or budget failure");
        }save(out,sha,"COMPLETE",guard,rows);}catch(Exception e){save(out,sha,"STOPPED",guard,rows);throw e;}
    }
}
