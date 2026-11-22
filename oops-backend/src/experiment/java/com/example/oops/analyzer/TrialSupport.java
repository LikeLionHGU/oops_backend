package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.springframework.core.io.*;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;

/** Shared helpers for opt-in trial runners on frozen STT excerpts. */
final class TrialSupport {
    private TrialSupport() {}

    record Condition(String id, String labelStatus, List<ReviewInput.Segment> raw) {}

    static ResourceLoader resources(String text) {
        return new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                check(location.equals("classpath:frozen.json"),"Only embedded frozen resource allowed");
                return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    static ReviewGuidelineLibrary readyLibrary(String text) {
        var lib=new ReviewGuidelineLibrary(resources(text),true,"classpath:frozen.json",6000,3,3000,"");
        lib.load(); check("READY_WORKING_REFERENCE".equals(lib.trace(TimelineEventType.SPEECH).state()),"Ready unexpired archive required"); return lib;
    }

    static AnalysisContext context(Condition c) {
        return new AnalysisContext(Video.builder().filename("private-frozen-stt-excerpt").build(),ContentGenre.GENERAL,
                List.of(),List.of(),new ReviewInput(c.raw()));
    }

    static Map<String,String> implementation() throws Exception {
        Map<String,String> hashes=new TreeMap<>();
        try(var files=Files.list(Path.of("src/main/java/com/example/oops/analyzer"))) {
            for(var path:files.filter(p -> p.toString().endsWith(".java")).sorted().toList())
                hashes.put(path.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
        }
        for(String name:List.of("src/main/java/com/example/oops/client/OpenAiClient.java",
                "src/experiment/java/com/example/oops/analyzer/TrialSupport.java",
                "src/experiment/java/com/example/oops/analyzer/TextBoundaryTrial.java"))
            hashes.put(name,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(name))));
        ExperimentSources.addImplementation(hashes);
        return Map.copyOf(hashes);
    }

    static byte[] boundedBody(byte[] body) {
        var tree = JSON.readTree(body);
        int limit = tree.path("max_completion_tokens").asInt(8000);
        ((tools.jackson.databind.node.ObjectNode)tree).put("max_completion_tokens",Math.min(limit,8000));
        return JSON.writeValueAsBytes(tree);
    }

    static byte[] guardBody(byte[] body,String uri) {
        check(uri.equals("https://api.openai.com/v1/chat/completions"),"Only approved OpenAI endpoint allowed");
        var json=JSON.readTree(body);
        check(json.path("model").asText().equals("gpt-6-luna") && json.path("reasoning_effort").asText().equals("none")
                && !json.has("tools") && json.path("messages").isArray(),"Frozen model/text request required");
        for(var message:json.path("messages")) check(message.path("content").isString(),"No image/audio input allowed");
        return boundedBody(body);
    }

    static final class Client extends OpenAiClient {
        final List<Map<String,Object>> calls = new ArrayList<>();
        private final boolean scopedIds;
        Client(RestClient rest, OpenAiProperties properties) { this(rest,properties,false); }
        Client(RestClient rest, OpenAiProperties properties, boolean strictDiscovery) { this(rest,properties,strictDiscovery,true); }
        Client(RestClient rest, OpenAiProperties properties, boolean strictDiscovery, boolean scopedIds) {
            super(rest,properties,strictDiscovery);this.scopedIds=scopedIds;
        }
        // Test-only control arm: identical DTO/schema, without dynamic enum binding. Never wired in Spring.
        @com.example.oops.client.JsonSchemaOutput(name="candidate_discovery_scoped_v2",resource="/candidate-discovery-output.schema.json")
        public record UnscopedDiscovery(List<String> reviewedSegmentIds,List<CandidateReviewEngine.Proposal> candidates,
                List<CandidateReviewEngine.UncertainSegment> uncertainSegments,Boolean truncated,
                List<CandidateReviewEngine.Proposal> visualCandidates) {}
        private <T> Optional<T> record(String kind, String system, String input, Optional<T> output) {
            var row = new LinkedHashMap<String,Object>();
            row.put("kind",kind); row.put("system",system); row.put("input",JSON.readTree(input)); row.put("output",output.orElse(null));
            row.put("failureCode",failureCode().orElse(null)); calls.add(row); return output;
        }
        @Override public <T> Optional<T> completeAsJson(String system,String input,Class<T> type) {
            Optional<T> parsed;
            if (!scopedIds && type==CandidateReviewEngine.Discovery.class) {
                parsed=super.completeAsJson(system,input,UnscopedDiscovery.class).map(d -> type.cast(
                        new CandidateReviewEngine.Discovery(d.reviewedSegmentIds(),d.candidates(),d.uncertainSegments(),d.truncated(),d.visualCandidates())));
            } else parsed=super.completeAsJson(system,input,type);
            var result=record(type.getSimpleName(),system,input,parsed);
            var format=JSON.readTree(JSON.writeValueAsString(replayRequestBody(system,input,type))).path("response_format");
            calls.get(calls.size()-1).put("responseFormatType",format.path("type").asText());
            calls.get(calls.size()-1).put("responseFormatSha256",ReviewGuidelineLibrary.retrievalHash(format.toString()));
            calls.get(calls.size()-1).put("discoveryIdScopeEnabled",format.at("/json_schema/schema/$defs/primaryId/enum").isArray());
            return result;
        }
        @Override public Map<String,Object> replayRequestBody(String system,String input,Class<?> type) {
            return super.replayRequestBody(system,input,!scopedIds && type==CandidateReviewEngine.Discovery.class?UnscopedDiscovery.class:type);
        }
    }
}
