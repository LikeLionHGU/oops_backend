package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** One-use, text-only, user-reviewed excerpts absent verbatim from runtime casebook. */
public final class CasebookHoldoutCurrentPipelineTrial {
    static final String CONSENT = "CASEBOOK_HOLDOUT_VIDEO104_20261026_APPROVED";
    static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    static final String REVIEW_RELATIVE = "../datasets/controversy/evaluation/holdout-context-video104-20261026/review.json";
    static final String TRANSCRIPT_RELATIVE = "../datasets/controversy/experiments/longform-five-20261022-YU6AFm/QIruuFE4ZcE/transcript-104.json";
    static final String RUNTIME_RELATIVE = "../datasets/controversy/guidelines/runtime.json";
    static final int REPEATS = 2;
    static final int MAX_HTTP_TOTAL = 24;
    static final int MAX_HTTP_PER_ANALYSIS = 4;
    static final int MAX_BODY_BYTES = 120_000;
    static final double CAP_USD = .50;
    static final int OUTPUT_TOKENS = 8_000;
    static final int ANALYSIS_SPACING_MS = 15_000;

    record CaseInput(String caseId, String expectedDecisionLocalOnly, List<String> sourceSegmentIds,
                     ReviewInput review) {}
    record Plan(String schemaVersion, String scope, String reviewSha256, String transcriptSha256,
                String runtimeSha256, Map<String, String> implementation, String model,
                String reasoningEffort, int repeats, int maxHttpAttempts, int maxHttpPerAnalysis,
                int analysisSpacingMs, double approvedCapUsd, double worstCaseReservedEstimateUsd,
                List<CaseInput> cases) {}

    private CasebookHoldoutCurrentPipelineTrial() {}

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] bounded(Path path) throws Exception {
        check(Files.size(path) <= 8_000_000, "Bounded local source required");
        return Files.readAllBytes(path);
    }

    static Map<String, String> implementation() throws Exception {
        var hashes = new TreeMap<>(SocialDefectCurrentModelTrial.implementation());
        hashes.put("src/test/java/com/example/oops/analyzer/CasebookHoldoutCurrentPipelineTrial.java",
                sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/CasebookHoldoutCurrentPipelineTrial.java"))));
        hashes.put("src/test/java/com/example/oops/analyzer/CasebookHoldoutCurrentPipelineTrialTest.java",
                sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/CasebookHoldoutCurrentPipelineTrialTest.java"))));
        return hashes;
    }

    static double reservationEstimate() {
        return SocialDefectCurrentModelTrial.reservation(MAX_HTTP_TOTAL, MAX_BODY_BYTES);
    }

    static Plan prepare() throws Exception {
        Path reviewPath = Path.of(REVIEW_RELATIVE).toRealPath();
        Path transcriptPath = Path.of(TRANSCRIPT_RELATIVE).toRealPath();
        Path runtimePath = Path.of(RUNTIME_RELATIVE).toRealPath();
        byte[] reviewBytes = bounded(reviewPath), transcriptBytes = bounded(transcriptPath), runtimeBytes = bounded(runtimePath);
        var review = JSON.readTree(reviewBytes);
        check(review.path("schemaVersion").asText().equals("user-reviewed-holdout-labels-1"), "Reviewed labels required");
        check(review.path("id").asText().equals("holdout-context-video104-20261026"), "Unexpected review packet");
        check(review.path("sourceVideoId").asText().equals("104")
                && review.path("sourceId").asText().equals("QIruuFE4ZcE"), "Unexpected source video");
        check(sha(transcriptBytes).equals(review.path("sourceTranscriptSha256").asText()), "Stored transcript changed");
        check(!review.path("runtimePromotion").asBoolean(true)
                && !review.path("trainingUseAuthorized").asBoolean(true), "Development-only labels required");
        String runtimeSha = sha(runtimeBytes);
        check(runtimeSha.equals(review.path("runtimeDictionarySha256AtReview").asText()), "Runtime changed since label review");

        var source = JSON.readTree(transcriptBytes);
        check(source.path("success").asBoolean(false) && source.path("data").isArray(), "Stored transcript data required");
        var sourceById = new HashMap<String, tools.jackson.databind.JsonNode>();
        int index = 0;
        for (var line : source.path("data")) sourceById.put("stt-index-" + index++, line);
        var cases = new ArrayList<CaseInput>();
        Set<String> seenCaseIds = new HashSet<>();
        for (var approved : review.path("cases")) {
            String caseId = approved.path("caseId").asText();
            String expected = approved.path("decision").asText();
            check(seenCaseIds.add(caseId), "Duplicate approved case");
            check(Set.of("PASS", "REVIEW_REQUIRED").contains(expected), "Approved decision required");
            var segments = new ArrayList<ReviewInput.Segment>();
            var ids = new ArrayList<String>();
            for (var idNode : approved.path("sourceSegmentIds")) {
                String id = idNode.asText();
                var line = sourceById.get(id);
                check(line != null, "Approved transcript segment missing: " + id);
                long start = line.path("startMs").asLong(), end = line.path("endMs").asLong();
                check(start >= approved.path("startMs").asLong()
                        && end <= approved.path("endMs").asLong(), "Segment outside approved window: " + id);
                segments.add(new ReviewInput.Segment(id, TimelineEventType.SPEECH,
                        start, end, line.path("text").asText(), null));
                ids.add(id);
            }
            check(!segments.isEmpty(), "Non-empty source window required");
            cases.add(new CaseInput(caseId, expected, List.copyOf(ids), new ReviewInput(List.copyOf(segments))));
        }
        check(cases.size() == 3 && seenCaseIds.containsAll(Set.of(
                "D02-lunch-skipping-study", "D05-taekwondo-belt-generalization", "D04-math-major-quoted-misconception")),
                "Exact three reviewed cases required");
        check(cases.stream().filter(c -> c.expectedDecisionLocalOnly().equals("REVIEW_REQUIRED")).count() == 1,
                "Exactly one positive and two PASS controls required");

        var runtime = JSON.readTree(runtimeBytes);
        String runtimeText = runtime.toString();
        for (String absentPhrase : List.of("2년 내내 점심을 안 먹고", "띠 인플레이션이 일어나서", "수학과에 가면은"))
            check(!runtimeText.contains(absentPhrase), "Exact holdout phrase already present in runtime: " + absentPhrase);

        var plan = new Plan("casebook-current-pipeline-holdout-1",
                "USER_REVIEWED_RUNTIME_UNLISTED_EXCERPTS_FROM_PREVIOUSLY_ANALYZED_VIDEO_NOT_VIDEO_LEVEL_BLIND_HOLDOUT",
                sha(reviewBytes), sha(transcriptBytes), runtimeSha, implementation(), "gpt-6-luna", "none",
                REPEATS, MAX_HTTP_TOTAL, MAX_HTTP_PER_ANALYSIS, ANALYSIS_SPACING_MS, CAP_USD,
                reservationEstimate(), List.copyOf(cases));
        validate(plan);
        return plan;
    }

    static void validate(Plan plan) {
        check(plan.schemaVersion().equals("casebook-current-pipeline-holdout-1")
                && plan.model().equals("gpt-6-luna") && plan.reasoningEffort().equals("none")
                && plan.repeats() == 2 && plan.maxHttpAttempts() == 24 && plan.maxHttpPerAnalysis() == 4
                && plan.analysisSpacingMs() == 15_000 && plan.approvedCapUsd() == .50
                && plan.worstCaseReservedEstimateUsd() <= plan.approvedCapUsd(), "Frozen bounded run plan required");
        check(plan.cases().size() == 3, "Three fixed cases required");
    }

    static tools.jackson.databind.JsonNode preflight(Plan plan) throws Exception {
        var library = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(
                Files.readString(Path.of(RUNTIME_RELATIVE))), true, "classpath:frozen.json", 6000, 3, 3000, "", true);
        library.load();
        check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()), "Runtime reference unavailable");
        var rows = JSON.createArrayNode();
        for (var c : plan.cases()) {
            var segments = c.review().segments();
            var selected = library.select(TimelineEventType.SPEECH, segments, "offline-" + c.caseId(), segments);
            var row = rows.addObject();
            row.put("caseId", c.caseId());
            row.put("expectedDecisionLocalOnly", c.expectedDecisionLocalOnly());
            row.set("sourceSegmentIds", JSON.valueToTree(c.sourceSegmentIds()));
            row.put("sourceInputSha256", sha(JSON.writeValueAsBytes(segments)));
            row.set("retrievedExampleIds", JSON.valueToTree(selected.trace().exampleIds()));
            row.put("retrievalMethod", selected.trace().retrievalMethod());
            row.put("referencePayloadCodePoints", selected.trace().payloadCodePoints());
        }
        var manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", "casebook-current-pipeline-holdout-preflight-1");
        manifest.put("scope", plan.scope());
        manifest.put("reviewSha256", plan.reviewSha256());
        manifest.put("transcriptSha256", plan.transcriptSha256());
        manifest.put("runtimeSha256", plan.runtimeSha256());
        manifest.put("implementationSha256", sha(JSON.writeValueAsBytes(plan.implementation())));
        manifest.set("implementation", JSON.valueToTree(plan.implementation()));
        manifest.set("cases", rows);
        manifest.put("analyses", plan.cases().size() * plan.repeats());
        manifest.put("maxHttpAttempts", plan.maxHttpAttempts());
        manifest.put("maxHttpPerAnalysis", plan.maxHttpPerAnalysis());
        manifest.put("hardTrialCapUsd", plan.approvedCapUsd());
        manifest.put("worstCaseReservedEstimateUsd", plan.worstCaseReservedEstimateUsd());
        manifest.put("providerCalls", 0);
        manifest.put("labelLeakCheck", "Expected decisions remain local and are excluded from CandidateReviewEngine input.");
        manifest.put("mediaEgress", false);
        return manifest;
    }

    static void save(Path output, List<Map<String, Object>> rows,
                     SocialDefectCurrentModelTrial.Guard guard, double usageUsd, String state,
                     String planSha, String manifestSha) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "casebook-current-pipeline-holdout-results-1");
        result.put("state", state); result.put("scope", "TEXT_ONLY_CURRENT_CANDIDATE_DISCOVERY_TO_FINDING_NOT_VIDEO_LEVEL_BLIND_HOLDOUT");
        result.put("planSha256", planSha); result.put("manifestSha256", manifestSha);
        result.put("results", rows); result.put("httpAttempts", guard.totalAttempts);
        result.put("reservedEstimatedUsd", guard.reservedUsd); result.put("usageEstimatedUsd", usageUsd);
        result.put("actualBillingConfirmed", false); result.put("approvedCapUsd", CAP_USD); result.put("mediaSent", false);
        Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(result));
    }

    public static void main(String[] args) throws Exception {
        check((args.length == 2 && args[0].equals("prepare"))
                        || (args.length == 6 && args[0].equals("run")),
                "prepare NEW_PLAN_DIR | run PLAN_PATH PLAN_SHA MANIFEST_PATH MANIFEST_SHA NEW_OUTPUT_DIR required");
        Path experiments = Path.of("../datasets/controversy/experiments").toRealPath();
        if (args[0].equals("prepare")) {
            Path output = Path.of(args[1]).toAbsolutePath().normalize();
            check(!Files.exists(output) && output.getParent().toRealPath().startsWith(experiments), "New private plan directory required");
            Files.createDirectory(output);
            var plan = prepare();
            byte[] planBytes = JSON.writeValueAsBytes(plan);
            Files.write(output.resolve("plan.json"), planBytes, StandardOpenOption.CREATE_NEW);
            var manifest = preflight(plan);
            byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
            Files.write(output.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
            System.out.println("HOLDOUT_PREFLIGHT_OK cases=3 analyses=6 maxHttp=24 reservedUsd="
                    + plan.worstCaseReservedEstimateUsd() + " providerCalls=0 planSha=" + sha(planBytes)
                    + " manifestSha=" + sha(manifestBytes));
            return;
        }

        Path planPath = Path.of(args[1]).toRealPath();
        byte[] planBytes = bounded(planPath);
        check(sha(planBytes).equals(args[2]), "Plan SHA mismatch");
        Path manifestPath = Path.of(args[3]).toRealPath();
        byte[] manifestBytes = bounded(manifestPath);
        check(sha(manifestBytes).equals(args[4]), "Manifest SHA mismatch");
        var plan = prepare();
        validate(plan);
        check(JSON.readTree(planBytes).equals(JSON.readTree(JSON.writeValueAsBytes(plan))), "Source, labels, runtime, or implementation drift");
        check(JSON.readTree(manifestBytes).equals(preflight(plan)), "Retrieval or preflight manifest drift");
        check(CONSENT.equals(System.getenv("CASEBOOK_HOLDOUT_VIDEO104_CONSENT")), "Fresh exact-plan consent required");
        check(Math.abs(plan.worstCaseReservedEstimateUsd() - .3864576) < 1e-9, "Budget calculation drift");

        Path output = Path.of(args[5]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(experiments), "New private result directory required; no resume");
        String key = System.getenv("OPENAI_API_KEY");
        check(key != null && !key.isBlank(), "OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(planPath, output, args[2], CONSENT, MAX_HTTP_TOTAL);
        Files.createDirectory(output);
        Files.write(output.resolve("plan.json"), planBytes, StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("authorization.json"), JSON.writeValueAsString(Map.of(
                "planSha256", args[2], "manifestSha256", args[4], "textEgressApproved", true,
                "mediaEgressApproved", false, "maxHttpAttempts", MAX_HTTP_TOTAL,
                "perAnalysisMaxHttpAttempts", MAX_HTTP_PER_ANALYSIS, "hardTrialCapUsd", CAP_USD,
                "previousBudgetReused", false)));

        var guard = new SocialDefectCurrentModelTrial.Guard(output);
        var props = new OpenAiProperties(key, System.getenv("OPENAI_ORG_ID"), System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1", "gpt-6-luna", "none", Duration.ofSeconds(60),
                new OpenAiProperties.Pricing(.10, .01, .50, null, null));
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        var builder = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    try {
                        byte[] approved = guard.accept(body, request.getURI().toString());
                        request.getHeaders().setContentLength(approved.length);
                        return execution.execute(request, approved);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Approved holdout egress guard blocked request", e);
                    }
                });
        if (props.hasOrganization()) builder.defaultHeader("OpenAI-Organization", props.organization());
        if (props.hasProject()) builder.defaultHeader("OpenAI-Project", props.project());
        var client = new MechanismRoutingTrial.Client(builder.build(), props);
        String runtime = Files.readString(Path.of(RUNTIME_RELATIVE));
        var library = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime), true,
                "classpath:frozen.json", 6000, 3, 3000, "", true);
        library.load();
        var rows = new ArrayList<Map<String, Object>>();
        double cost = 0;
        save(output, rows, guard, cost, "RUNNING", args[2], args[4]);
        try {
            int completed = 0;
            for (int repeat = 1; repeat <= REPEATS; repeat++) {
                for (var c : plan.cases()) {
                    guard.beginAnalysis();
                    client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("casebook-current-pipeline-holdout");
                    var context = new AnalysisContext(Video.builder().filename("private-holdout").build(),
                            ContentGenre.GENERAL, List.of(), List.of(), c.review());
                    long started = System.nanoTime();
                    var result = CandidateReviewEngine.run(client, context,
                            new CandidateReviewEngine.RunServices(null, null, library, null, null),
                            new CandidateReviewEngine.RunOptions(24, 0, false,
                                    new CandidateReviewEngine.VerificationFeatures(false, false, false), false));
                    var usage = client.videoUsage(); cost += usage.costUsd();
                    var row = new LinkedHashMap<String, Object>();
                    row.put("caseId", c.caseId()); row.put("expectedDecisionLocalOnly", c.expectedDecisionLocalOnly());
                    row.put("repeat", repeat); row.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                    row.put("status", result.status()); row.put("diagnostics", result.diagnostics());
                    row.put("evaluations", result.evaluations()); row.put("unassessedSegmentIds", result.unassessedSegmentIds());
                    row.put("calls", List.copyOf(client.calls)); row.put("usage", usage);
                    row.put("findings", result.findings().stream().map(SocialDefectCurrentModelTrial::finding).toList());
                    rows.add(row);
                    boolean ok = "SUCCESS".equals(String.valueOf(result.status()))
                            && !guard.exhausted && cost <= CAP_USD && client.failureCount() == 0
                            && usage.calls() == client.calls.size();
                    client.endVideo();
                    save(output, rows, guard, cost, ok ? "RUNNING" : "INCOMPLETE", args[2], args[4]);
                    completed++;
                    System.out.println("CASEBOOK_HOLDOUT " + completed + "/6 " + c.caseId()
                            + " repeat=" + repeat + " findings=" + result.findings().size()
                            + " status=" + result.status() + " calls=" + usage.calls());
                    check(ok, "Provider/schema/budget failure; stop without retry");
                    if (completed < plan.cases().size() * REPEATS) Thread.sleep(ANALYSIS_SPACING_MS);
                }
            }
            check(rows.size() == plan.cases().size() * REPEATS, "All planned analyses must complete");
            save(output, rows, guard, cost, "COMPLETE", args[2], args[4]);
            System.out.println("CASEBOOK_HOLDOUT_COMPLETE analyses=" + rows.size() + " http=" + guard.totalAttempts
                    + " usageEstimatedUsd=" + cost + " reserved=" + guard.reservedUsd);
        } catch (Exception e) {
            save(output, rows, guard, cost, "INCOMPLETE", args[2], args[4]);
            throw e;
        }
    }
}
