package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Opt-in paid experiment, never a JUnit test or server endpoint. Labels are not sent to the model. */
public final class TextBoundaryTrial {
    static final JsonMapper JSON = JsonMapper.builder().build();
    record Case(String id, String expected, List<String> speech) {}
    record Suite(String schemaVersion, int repeats, String scope, List<Case> cases) {}
    static void check(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    static Suite readSuite(Path path) throws Exception {
        check(Files.size(path) <= 65536, "Fixture too large");
        var suite = JSON.readValue(Files.readAllBytes(path), Suite.class);
        check("text-boundary-trial-1".equals(suite.schemaVersion()) && suite.repeats() == 2
                && suite.cases() != null && suite.cases().size() == 9, "Invalid suite");
        Set<String> ids = new HashSet<>();
        for (var c : suite.cases()) check(ids.add(c.id()) && Set.of("PASS", "REVIEW_REQUIRED").contains(c.expected())
                && c.speech() != null && !c.speech().isEmpty() && c.speech().size() <= 32
                && c.speech().stream().allMatch(s -> s != null && !s.isBlank() && s.length() <= 300), "Invalid fixture");
        return suite;
    }
    static final class Budget {
        final double cap, inputRate, outputRate;
        final Path directory;
        double reserved;
        int attempts;
        boolean exhausted;
        Budget(double cap, double inputRate, double outputRate, Path directory) {
            check(Double.isFinite(cap) && cap > 0 && cap <= 1 && Double.isFinite(inputRate) && inputRate > 0
                    && Double.isFinite(outputRate) && outputRate > 0, "Invalid budget/rates");
            this.cap=cap; this.inputRate=inputRate; this.outputRate=outputRate; this.directory=directory;
        }
        void reserve(int bytes) throws java.io.IOException {
            // Conservative configured-price estimate: UTF-8 byte count + framing, no cache discount.
            double next = (bytes + 1024) * inputRate / 1_000_000 + 8000 * outputRate / 1_000_000;
            if (attempts >= 80 || reserved + next > cap) {
                exhausted=true; throw new IllegalArgumentException("Trial reservation exhausted");
            }
            reserved += next; attempts++;
            Files.writeString(directory.resolve("reservation.json"), JSON.writeValueAsString(Map.of(
                    "approvedEstimatedUsd",cap,"reservedEstimatedUsd",reserved,"httpAttempts",attempts,
                    "inputPer1m",inputRate,"outputPer1m",outputRate,"maxCompletionTokens",8000,
                    "actualBillingConfirmed",false)));
        }
    }
    static final class RecordingClient extends OpenAiClient {
        final List<Map<String,Object>> calls = new ArrayList<>();
        RecordingClient(RestClient rest, OpenAiProperties properties) { super(rest, properties); }
        @Override public <T> Optional<T> completeAsJson(String system, String input, Class<T> type) {
            var output = super.completeAsJson(system,input,type);
            Map<String,Object> call = new LinkedHashMap<>();
            call.put("system",system); call.put("input",JSON.readTree(input));
            call.put("output",output.orElse(null)); call.put("failureCode",failureCode().orElse(null));
            calls.add(call); return output;
        }
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"Fixture, archive, new private output, budget required");
        var suite=readSuite(Path.of(args[0]));
        Path directory=Path.of(args[2]).toAbsolutePath().normalize();
        Path root=Path.of("../datasets/controversy/experiments").toAbsolutePath().normalize();
        check(directory.startsWith(root) && !directory.equals(root) && !Files.exists(directory),"New private directory required");
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required");
        double cap=Double.parseDouble(args[3]);
        double inputRate=Double.parseDouble(System.getenv().getOrDefault("OPENAI_INPUT_PER_1M","0.10"));
        double outputRate=Double.parseDouble(System.getenv().getOrDefault("OPENAI_OUTPUT_PER_1M","0.50"));
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,
                Path.of(args[1]).toAbsolutePath().toUri().toString(),6000);
        library.load(); check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()),"Reference not ready");
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(90),
                new OpenAiProperties.Pricing(inputRate,null,outputRate,null,null));
        Files.createDirectories(root); Files.createDirectory(directory);
        var budget=new Budget(cap,inputRate,outputRate,directory);
        var client=client(props,budget);
        Files.writeString(directory.resolve("fixture.json"),JSON.writeValueAsString(suite));
        List<Map<String,Object>> results=new ArrayList<>(); double usageCost=0;
        for(int repeat=1;repeat<=suite.repeats();repeat++) for(var c:suite.cases()) {
            client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("text-boundary-trial");
            var video=Video.builder().filename("text-only-fixture").build();
            List<TranscriptSegment> lines=new ArrayList<>();
            for(int i=0;i<c.speech().size();i++) lines.add(new TranscriptSegment(video,i*2000L,i*2000L+1500,c.speech().get(i)));
            var result=CandidateReviewEngine.run(client,new AnalysisContext(video,ContentGenre.GENERAL,lines,List.of()),
                    24,null,null,library,null,0);
            var usage=client.videoUsage(); usageCost+=usage.costUsd();
            Map<String,Object> row=new LinkedHashMap<>(); row.put("caseId",c.id()); row.put("repeat",repeat);
            row.put("expected",c.expected()); row.put("status",result.status()); row.put("diagnostics",result.diagnostics());
            row.put("findings",result.findings().stream().map(f->Map.of("category",f.getCategory(),"text",f.primaryText(),
                    "reason",f.getReason(),"target",f.getTarget()==null?"":f.getTarget(),"supports",f.validatedSupports())).toList());
            row.put("calls",List.copyOf(client.calls)); row.put("usage",usage);
            row.put("fullyEvaluated",result.unassessedSegmentIds().isEmpty());
            results.add(row);
            Files.writeString(directory.resolve("results.json"),JSON.writeValueAsString(Map.of(
                    "revision",CandidateReviewEngine.REVISION,"scope",suite.scope(),"maxCompletionTokens",8000,
                    "results",results,"usageEstimatedUsd",usageCost,"actualBillingConfirmed",false)));
            System.out.println("TRIAL "+c.id()+" repeat="+repeat+" findings="+result.findings().size()+" status="+result.status());
            int failures=client.failureCount(); client.endVideo();
            check(!budget.exhausted && failures==0 && usage.calls()==client.calls.size(),
                    "Budget/provider/usage failure; stop trial, retain partial results");
        }
        System.out.println("TRIAL_COMPLETE slots="+results.size()+" usageEstimatedUsd="+usageCost+" reservedEstimatedUsd="+budget.reserved);
    }
    static RecordingClient client(OpenAiProperties props, Budget budget) {
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(90));
        var builder=RestClient.builder().baseUrl(props.baseUrlOrDefault()).requestFactory(factory)
                .defaultHeader("Authorization","Bearer "+props.apiKey()).requestInterceptor((request,body,execution)-> {
                    var value=JSON.readTree(body);
                    ((tools.jackson.databind.node.ObjectNode)value).put("max_completion_tokens",8000);
                    byte[] bounded=JSON.writeValueAsBytes(value);
                    budget.reserve(bounded.length); request.getHeaders().setContentLength(bounded.length);
                    return execution.execute(request,bounded);
                });
        if(props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        return new RecordingClient(builder.build(),props);
    }
}
