package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;

/** Offline export of paired initial verification requests. No API client and no Spring startup. */
public final class PrepareContextJudgmentComparison {
    private PrepareContextJudgmentComparison() {}
    static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    static Map<String,Object> prepare(JsonNode document) throws Exception {
        JsonNode snapshot=document.has("data")?document.path("data").path("snapshot"):document;
        TextBoundaryTrial.check(snapshot.isObject() && snapshot.path("videoId").canConvertToLong() && snapshot.path("videoId").asLong()>0
                && snapshot.path("scope").asText().equals("TEXT_INITIAL_VERIFICATION_ONLY_PARSED_RESPONSE")
                && snapshot.path("droppedRequests").isIntegralNumber() && snapshot.path("droppedRequests").asInt()==0
                && snapshot.has("truncated") && snapshot.path("truncated").isBoolean() && !snapshot.path("truncated").asBoolean(),
                "Complete initial verification snapshot required; do not reconstruct lost traces");
        var requests=snapshot.path("requests");
        TextBoundaryTrial.check(requests.isArray() && !requests.isEmpty() && requests.size()<=32,"1..32 saved requests required");
        var pairs=new ArrayList<Map<String,Object>>();
        int index=0;
        for (var request:requests) {
            String system=request.path("system").asText(),input=request.path("input").asText();
            TextBoundaryTrial.check(sha(system).equals(request.path("systemSha256").asText())
                    && sha(input).equals(request.path("inputSha256").asText()),"Saved request hash mismatch");
            TextBoundaryTrial.check(system.startsWith(SourceRelationPrompts.verification(0)),"Current relation verification contract required");
            var body=JSON.readTree(input);
            TextBoundaryTrial.check(body.path("promptRevision").asText().equals(CandidateReviewEngine.RELATION_REVISION)
                    && body.path("raw").isArray() && !body.path("raw").isEmpty()
                    && body.path("candidates").isArray() && !body.path("candidates").isEmpty() && !body.has("repair"),
                    "Initial relation verification input required");
            var pair=ContextJudgmentPolicy.pair(system,input);
            pairs.add(Map.of("requestIndex",++index,"input",pair.input(),"inputSha256",sha(pair.input()),
                    "baselineSystem",pair.baselineSystem(),"baselineSystemSha256",sha(pair.baselineSystem()),
                    "comparisonSystem",pair.comparisonSystem(),"comparisonSystemSha256",sha(pair.comparisonSystem())));
        }
        return Map.of("schemaVersion",ContextJudgmentPolicy.REVISION,"sourceVideoId",snapshot.path("videoId").asLong(),
                "sourcePromptRevision",CandidateReviewEngine.RELATION_REVISION,"scope","FIXED_INITIAL_VERIFICATION_ONLY",
                "providerCalls",0,"labelsIncluded",false,"pairs",pairs);
    }
    public static void main(String[] args) throws Exception {
        TextBoundaryTrial.check(args.length==2,"Saved trace JSON and new private output directory required");
        Path source=Path.of(args[0]);
        TextBoundaryTrial.check(Files.size(source)<=1_048_576,"Trace too large");
        var plan=prepare(JSON.readTree(Files.readString(source)));
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        Path output=Path.of(args[1]).toAbsolutePath().normalize();
        TextBoundaryTrial.check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory under experiments required");
        Files.createDirectory(output);
        Files.writeString(output.resolve("plan.json"),JSON.writeValueAsString(plan),StandardOpenOption.CREATE_NEW);
        System.out.println("Prepared frozen policy comparison. Provider calls: 0; labels and accuracy not evaluated.");
    }
}
