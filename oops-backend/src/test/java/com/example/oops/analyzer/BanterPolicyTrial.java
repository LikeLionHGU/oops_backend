package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;

/** Explicit fixed-verification experiment. No discovery calls, audio, server or automatic promotion. */
public final class BanterPolicyTrial {
    private BanterPolicyTrial() {}
    static final List<String> REAL_IDS = List.of("64-region", "64-graphic", "65-product-review", "66-banter");
    static final List<String> SYNTHETIC_IDS = List.of("isolated", "behavior-criticism", "personal-degradation",
            "game-banter", "rejected-joke", "reported-insult", "endorsed-insult", "joke-claim-only");

    static List<ContextPolicyTrial.Task> prepare(JsonNode oldPlan, JsonNode packet, ReviewGuidelineLibrary library) {
        check(oldPlan.path("schemaVersion").asText().equals("context-policy-stored-stt-1"), "Known source plan required");
        check(packet.path("version").asText().equals("banter-boundary-review-20261015-draft"), "Known review packet required");
        List<ContextPolicyTrial.Task> tasks = new ArrayList<>();
        for (String id : REAL_IDS) {
            var matches = new ArrayList<JsonNode>();
            oldPlan.path("tasks").forEach(t -> { if (id.equals(t.path("spec").path("id").asText())) matches.add(t); });
            check(matches.size() == 1, "Exactly one source condition required");
            var previous = JSON.treeToValue(matches.get(0), ContextPolicyTrial.Task.class);
            var spec = previous.spec();
            // Video-level normal feedback is not a new approved text-only gold label.
            if (id.equals("66-banter")) spec = new ContextPolicyTrial.Spec(spec.id(), spec.video(), spec.anchor(),
                    spec.axis(), spec.evidence(), "USER_VIDEO_FLOW_PASS_TEXT_SUFFICIENCY_UNREVIEWED", null);
            tasks.add(pair(spec, previous.baselineSystem(), previous.input()));
        }
        for (String id : SYNTHETIC_IDS) {
            var matches = new ArrayList<JsonNode>();
            packet.path("cases").forEach(c -> { if (id.equals(c.path("id").asText())) matches.add(c); });
            check(matches.size() == 1, "Exactly one synthetic condition required");
            var source = matches.get(0);
            check(source.path("sourceId").asText().equals("synthetic-banter")
                    && source.path("status").asText().equals("DRAFT"), "Draft synthetic case required");
            var video = Video.builder().filename("synthetic-relative-time").build();
            List<TranscriptSegment> segments = new ArrayList<>();
            int anchor = -1;
            for (var line : source.path("segments")) {
                if (line.path("id").asText().equals(source.path("anchorSegmentId").asText())) anchor = segments.size();
                String text = line.path("rawText").asText();
                long start = line.path("startMs").asLong(-1), end = line.path("endMs").asLong(-1);
                check(!text.isBlank() && start >= 0 && end > start, "Valid authored raw and relative timing required");
                segments.add(new TranscriptSegment(video, start, end, text));
            }
            check(anchor >= 0 && segments.size() <= 8, "Bounded synthetic anchor required");
            var evidence = new ArrayList<Integer>();
            for (int i = 0; i < segments.size(); i++) evidence.add(i);
            var spec = new ContextPolicyTrial.Spec("synthetic-" + id, 0, anchor, "TARGET_TREATMENT",
                    evidence, "SYNTHETIC_PROPOSED_LABEL_NOT_GOLD", null);
            var captured = ContextPolicyTrial.capture(spec,
                    new AnalysisContext(video, ContentGenre.GENERAL, segments, List.of()), library);
            tasks.add(pair(spec, captured.baselineSystem(), captured.input()));
        }
        return List.copyOf(tasks);
    }

    static ContextPolicyTrial.Task pair(ContextPolicyTrial.Spec spec, String system, String input) {
        var pair = BanterBoundaryPolicy.pair(system, input);
        check(system.startsWith(SourceRelationPrompts.verification(0)), "Initial verification required");
        return new ContextPolicyTrial.Task(spec, pair.baselineSystem(), pair.comparisonSystem(), pair.input());
    }

    static void validatePlan(JsonNode plan, List<ContextPolicyTrial.Task> tasks) {
        check(plan.path("schemaVersion").asText().equals(BanterBoundaryPolicy.REVISION)
                && plan.path("plannedCalls").asInt() == 72 && plan.path("repeats").asInt() == 3
                && plan.path("approvedEstimatedUsd").asDouble() == 1 && tasks.size() == 12, "Unknown plan");
        var ids = new ArrayList<>(REAL_IDS);
        SYNTHETIC_IDS.forEach(id -> ids.add("synthetic-" + id));
        check(tasks.stream().map(t -> t.spec().id()).toList().equals(ids), "Unexpected task identity/order");
        for (var task : tasks) {
            check(task.comparisonSystem().equals(BanterBoundaryPolicy.replacePolicy(task.baselineSystem()))
                    && task.baselineSystem().startsWith(SourceRelationPrompts.verification(0)), "Changed policy pair");
            var body = JSON.readTree(task.input());
            check(body.path("promptRevision").asText().equals(CandidateReviewEngine.RELATION_REVISION)
                    && body.path("raw").isArray() && !body.path("raw").isEmpty()
                    && body.path("candidates").size() == 1 && !body.has("expected")
                    && !body.has("reviewPacket") && !body.has("labelStatus") && !body.has("contextSummary"), "Unexpected model input");
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 4 && Set.of("prepare", "run").contains(args[0]),
                "prepare old-plan review-packet output OR run plan approved-sha output");
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root), "New private output required");
        byte[] bytes = Files.readAllBytes(Path.of(args[1]));
        check(bytes.length <= 2_097_152, "Plan too large");
        if (args[0].equals("prepare")) {
            var library = ReferenceOnlyTrial.library(Path.of("../datasets/controversy/guidelines/runtime.json"));
            byte[] packet = Files.readAllBytes(Path.of(args[2]));
            check(packet.length <= 262_144, "Review packet too large");
            var tasks = prepare(JSON.readTree(bytes), JSON.readTree(packet), library);
            double reserve = 0;
            for (var t : tasks) for (var system : List.of(t.baselineSystem(), t.comparisonSystem()))
                reserve += 3 * ((JSON.writeValueAsBytes(Map.of("system", system, "input", t.input())).length + 4096)
                        * .10 / 1_000_000 + 8000 * .50 / 1_000_000);
            check(reserve < 1, "Preflight exceeds approved estimate");
            var plan = Map.of("schemaVersion", BanterBoundaryPolicy.REVISION, "plannedCalls", 72, "repeats", 3,
                    "approvedEstimatedUsd", 1, "scope", "FIXED_VERIFICATION_MANUAL_CANDIDATES_NOT_FULL_VIDEO",
                    "preflightReservationEstimatedUsd", reserve, "sourcePlanSha256", ReferenceOnlyTrial.sha(bytes),
                    "reviewPacketSha256", ReferenceOnlyTrial.sha(packet), "tasks", tasks);
            validatePlan(JSON.valueToTree(plan), tasks);
            Files.createDirectory(output);
            Files.writeString(output.resolve("plan.json"), JSON.writeValueAsString(plan), StandardOpenOption.CREATE_NEW);
            System.out.println("BANTER_PREPARED tasks=12 calls=72 reserve=" + reserve + " providerCalls=0");
            return;
        }
        check(ReferenceOnlyTrial.sha(bytes).equals(args[2]), "Approved frozen plan hash required");
        var plan = JSON.readTree(bytes);
        List<ContextPolicyTrial.Task> tasks = new ArrayList<>();
        plan.path("tasks").forEach(t -> tasks.add(JSON.treeToValue(t, ContextPolicyTrial.Task.class)));
        validatePlan(plan, tasks);
        String key = System.getenv("OPENAI_API_KEY");
        check(key != null && !key.isBlank(), "OPENAI_API_KEY required");
        Files.createDirectory(output);
        Files.write(output.resolve("plan.json"), bytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("plan-sha256.txt"), args[2]);
        var budget = new Budget(1, .10, .50, output);
        var props = new OpenAiProperties(key, System.getenv("OPENAI_ORG_ID"), System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1", "gpt-6-luna", "none", Duration.ofSeconds(90),
                new OpenAiProperties.Pricing(.10, .01, .50, null, null));
        var client = client(props, budget);
        List<Map<String,Object>> results = new ArrayList<>();
        double cost = 0;
        for (int repeat = 1; repeat <= 3; repeat++) for (var task : tasks)
            for (String arm : repeat % 2 == 1 ? List.of("BASELINE", "COMPARISON") : List.of("COMPARISON", "BASELINE")) {
                client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("banter-policy-trial-41");
                var response = client.completeAsJson(arm.equals("BASELINE") ? task.baselineSystem() : task.comparisonSystem(),
                        task.input(), CandidateReviewEngine.VerificationResult.class).orElse(null);
                var usage = client.videoUsage(); cost += usage.costUsd();
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("caseId", task.spec().id()); row.put("labelStatus", task.spec().labelStatus());
                row.put("expected", task.spec().expected()); row.put("arm", arm); row.put("repeat", repeat);
                row.put("assessments", ContextPolicyTrial.assess(task, response));
                row.put("calls", List.copyOf(client.calls)); row.put("usage", usage); results.add(row);
                Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(Map.of("results", results,
                        "usageEstimatedUsd", cost, "reservedEstimatedUsd", budget.reserved,
                        "approvedEstimatedUsd", 1, "actualBillingConfirmed", false)));
                System.out.println("BANTER_POLICY " + results.size() + "/72 " + task.spec().id() + " " + arm + " repeat=" + repeat);
                int failures = client.failureCount(); client.endVideo();
                check(!budget.exhausted && failures == 0 && usage.calls() == 1, "Provider/budget failure; stop and retain partial results");
            }
        System.out.println("BANTER_COMPLETE calls=" + results.size() + " estimatedUsd=" + cost + " reserved=" + budget.reserved);
    }
}
