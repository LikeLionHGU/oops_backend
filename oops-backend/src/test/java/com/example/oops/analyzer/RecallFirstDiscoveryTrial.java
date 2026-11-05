package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.TimelineEventType;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ConfrontationStageTrial.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/**
 * Opt-in OFF/ON comparison of recall-first discovery on user-approved development labels.
 * prepare makes no provider call; run needs the exact plan hash and consent. Not independent accuracy.
 */
public final class RecallFirstDiscoveryTrial {
    static final String CONSENT = "RECALL_FIRST_13X2_60_USD100";
    static final int LIMIT = 60, PER_RUN = 8;
    static final double BUDGET = 1.00;
    static final String CASE_142 = "video142-reviewed-window", ANCHOR_142 = "stt-index-147";
    static final String SCOPE = "DEVELOPMENT_LABELS_13_CASES_NOT_INDEPENDENT_R01_PAIR_SHARES_ONE_DIALOGUE";

    record Run(String caseId, boolean recall, Frozen initial) {}
    record Plan(String inputsPath, String inputsSha, String labelsPath, String labelsSha, String reviewPath, String reviewSha,
                String runtimePath, String runtimeSha, Map<String, String> implementation, List<Condition> cases,
                List<Run> runs, double maxReservation) {}

    static String mode(boolean recall) { return recall ? "RECALL_FIRST" : "BASE"; }

    record Step(Condition condition, String mode, boolean recall, ReviewGuidelineLibrary library, Frozen initial,
                java.util.function.UnaryOperator<String> rewrite) {}

    static Condition caseOf(List<Condition> cases, String id) {
        return cases.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    static final class TrialGuard {
        final int limit, perRun; final double budget;
        int attempts, runAttempts; double reserved; boolean blocked, sent; byte[] expected;
        TrialGuard(int limit, int perRun, double budget) { this.limit = limit; this.perRun = perRun; this.budget = budget; }
        void beginRun() { runAttempts = 0; expected = null; sent = false; }
        void begin(byte[] data) { check(!blocked, "Trial blocked"); expected = body(data, ENDPOINT); sent = false; }
        byte[] accept(byte[] data, String uri) {
            try {
                var accepted = body(data, uri);
                check(!blocked && expected != null && !sent && JSON.readTree(expected).equals(JSON.readTree(accepted)),
                        "Unplanned request or automatic retry");
                double next = reservation(accepted.length);
                check(attempts < limit && runAttempts < perRun && reserved + next <= budget, "Request or budget cap reached");
                sent = true; attempts++; runAttempts++; reserved += next;
                return accepted;
            } catch (RuntimeException e) { blocked = true; throw e; }
        }
    }

    static final class TrialClient extends OpenAiClient {
        final Condition condition; final String mode; final boolean offline; final TrialGuard guard; final Frozen frozen;
        final java.util.function.UnaryOperator<String> rewrite;
        final List<Frozen> captured = new ArrayList<>();
        String harnessError;
        TrialClient(RestClient rest, Condition c, String mode, boolean offline, TrialGuard guard, Frozen frozen, String key) {
            this(rest, c, mode, offline, guard, frozen, key, s -> s);
        }
        /** rewrite is a trial-only treatment applied to every system prompt before freezing and sending. */
        TrialClient(RestClient rest, Condition c, String mode, boolean offline, TrialGuard guard, Frozen frozen, String key,
                    java.util.function.UnaryOperator<String> rewrite) {
            super(rest, props(key), true);
            this.rewrite = rewrite; this.condition = c; this.mode = mode; this.offline = offline; this.guard = guard; this.frozen = frozen;
        }
        @Override public <T> Optional<T> completeAsJson(String system, String input, Class<T> type) {
            byte[] request;
            try {
                // Verification repair appends instructions after the reference payload.
                system = SyntheticReferenceComparison.trialSystem(rewrite.apply(system), false); input = canonical(input);
                request = body(JSON.writeValueAsBytes(replayRequestBody(system, input, type)), ENDPOINT);
            } catch (RuntimeException e) { harnessError = describe(e); if (guard != null) guard.blocked = true; throw e; }
            var capture = new Frozen(condition.id(), mode, system, input, type.getSimpleName(),
                    ReviewGuidelineLibrary.retrievalHash(new String(request, StandardCharsets.UTF_8)));
            captured.add(capture);
            if (offline) {
                if (type == CandidateReviewEngine.Discovery.class) return Optional.of(type.cast(new CandidateReviewEngine.Discovery(
                        condition.raw().stream().map(ReviewInput.Segment::id).toList(), List.of(), List.of(), false)));
                return Optional.empty();
            }
            try {
                if (captured.size() == 1) check(frozen.equals(capture), "Initial request differs from frozen preparation");
                guard.begin(request);
                Optional<T> response = super.completeAsJson(system, input, type);
                if (response.isEmpty() || failureCode().isPresent()) guard.blocked = true;
                return response;
            } catch (RuntimeException e) { harnessError = describe(e); guard.blocked = true; throw e; }
        }
    }

    static String describe(RuntimeException e) {
        String message = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
        return e.getClass().getSimpleName() + ": " + message.substring(0, Math.min(message.length(), 160));
    }

    static ReviewGuidelineLibrary library(Path runtime) {
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true, runtime.toUri().toString(),
                6000, 3, 3000, "", true, true);
        library.load();
        check(library.trace(TimelineEventType.SPEECH).state().equals("READY_WORKING_REFERENCE"), "Production runtime required");
        return library;
    }

    static TextReviewEngine.Result pipeline(OpenAiClient client, Condition c, ReviewGuidelineLibrary library, boolean recall) {
        var context = new AnalysisContext(com.example.oops.domain.Video.builder().filename("recall-first-trial").build(),
                com.example.oops.domain.ContentGenre.TALK_PODCAST, List.of(), List.of(), new ReviewInput(c.raw()));
        return CandidateReviewEngine.run(client, context, new CandidateReviewEngine.RunServices(null, null, library, null, null),
                new CandidateReviewEngine.RunOptions(24, 0, false, new CandidateReviewEngine.VerificationFeatures(false, false, false),
                        false, recall));
    }

    static Condition reviewed142(byte[] raw) {
        var review = JSON.readTree(raw);
        check(review.path("status").asText().equals("USER_REVIEWED_DEVELOPMENT_ONLY"), "User-reviewed development record required");
        var item = review.path("reviews").get(2);
        check(item.path("decision").asText().equals("REVIEW_REQUIRED"), "Reviewed REVIEW_REQUIRED window required");
        var segments = new ArrayList<ReviewInput.Segment>();
        for (var s : item.path("transcriptSegments")) segments.add(new ReviewInput.Segment(s.path("id").asText(),
                TimelineEventType.SPEECH, s.path("startMs").asLong(), s.path("endMs").asLong(), s.path("text").asText(), null));
        check(!segments.isEmpty() && segments.size() <= 64 && segments.stream().anyMatch(s -> s.id().equals(ANCHOR_142)),
                "Bounded window with reviewed anchor required");
        return new Condition(CASE_142, "USER_REVIEWED_WINDOW_DEVELOPMENT_ONLY", "REVIEW_REQUIRED", List.copyOf(segments),
                new Seed(ANCHOR_142, "TARGET_TREATMENT", "diagnostic only", List.of(ANCHOR_142)));
    }

    static Plan prepare(Path inputs, Path labels, Path review) throws Exception {
        var fixed = FixedBoundaryBaselineTrial.prepare(inputs, labels);
        byte[] reviewRaw = OfflineDecisionContractAudit.bounded(review);
        var cases = new ArrayList<>(fixed.cases());
        cases.add(reviewed142(reviewRaw));
        Path runtime = Path.of(fixed.runtimePath());
        var library = library(runtime);
        var runs = new ArrayList<Run>();
        for (var c : cases) for (boolean recall : List.of(false, true)) {
            var client = new TrialClient(RestClient.create(), c, mode(recall), true, null, null, "offline-no-key");
            pipeline(client, c, library, recall);
            check(client.captured.size() == 1, "One discovery page per condition required");
            runs.add(new Run(c.id(), recall, client.captured.get(0)));
        }
        var implementation = new TreeMap<>(fixed.implementation());
        var self = Path.of("src/test/java/com/example/oops/analyzer/RecallFirstDiscoveryTrial.java");
        implementation.put(self.toString(), ReferenceOnlyTrial.sha(Files.readAllBytes(self)));
        double worst = LIMIT * reservation(MAX_BYTES);
        check(worst <= BUDGET, "Reservation exceeds budget");
        return new Plan(fixed.inputsPath(), fixed.inputsSha(), fixed.labelsPath(), fixed.labelsSha(),
                review.toRealPath().toString(), ReferenceOnlyTrial.sha(reviewRaw), fixed.runtimePath(), fixed.runtimeSha(),
                implementation, List.copyOf(cases), List.copyOf(runs), worst);
    }

    static Map<String, Map<String, Object>> summarize(List<Map<String, Object>> rows) {
        var summary = new LinkedHashMap<String, Map<String, Object>>();
        for (String mode : new LinkedHashSet<>(rows.stream().map(r -> String.valueOf(r.get("mode"))).toList())) {
            var mine = rows.stream().filter(r -> mode.equals(r.get("mode"))).toList();
            var positives = mine.stream().filter(r -> "REVIEW_REQUIRED".equals(r.get("expectedDecision"))).toList();
            var negatives = mine.stream().filter(r -> "PASS".equals(r.get("expectedDecision"))).toList();
            var stats = new LinkedHashMap<String, Object>();
            stats.put("runs", mine.size());
            stats.put("positives", positives.size());
            stats.put("positivesFlagged", (int) positives.stream().filter(r -> "REVIEW_REQUIRED".equals(r.get("outcome"))).count());
            stats.put("positivesWithAnchorCandidate", (int) positives.stream().filter(r -> Boolean.TRUE.equals(r.get("anchorCandidate"))).count());
            stats.put("negatives", negatives.size());
            stats.put("negativesFlagged", (int) negatives.stream().filter(r -> "REVIEW_REQUIRED".equals(r.get("outcome"))).count());
            stats.put("incomplete", (int) mine.stream().filter(r -> "INCOMPLETE".equals(r.get("outcome"))).count());
            stats.put("partialRuns", (int) mine.stream().filter(r -> "PARTIAL".equals(String.valueOf(r.get("status")))).count());
            stats.put("candidates", mine.stream().mapToInt(r -> ((Number) r.getOrDefault("candidateCount", 0)).intValue()).sum());
            summary.put(mode, stats);
        }
        return summary;
    }

    /** Verification-contract PARTIAL is a measured outcome; provider, transport, budget, or harness failure stops. */
    static boolean continueAfter(String status, boolean blocked, int providerFailures, String harnessError) {
        return !blocked && providerFailures == 0 && harnessError == null && Set.of("SUCCESS", "PARTIAL").contains(status);
    }

    static Map<String, Object> resultRow(Condition c, String mode, TextReviewEngine.Result result, TrialClient client) {
        var traces = result.diagnostics() == null || result.diagnostics().candidatePipeline() == null ? List.<CandidateReviewDiagnostics.Trace>of()
                : result.diagnostics().candidatePipeline().candidates();
        String outcome = !result.findings().isEmpty() ? "REVIEW_REQUIRED"
                : "SUCCESS".equals(result.status().toString()) ? "PASS" : "INCOMPLETE";
        var row = new LinkedHashMap<String, Object>();
        row.put("caseId", c.id()); row.put("mode", mode); row.put("expectedDecision", c.expectedDecision());
        row.put("status", result.status()); row.put("outcome", outcome);
        row.put("candidateCount", traces.size());
        row.put("anchorCandidate", traces.stream().anyMatch(t -> c.seed().evidenceIds().contains(t.anchorId())));
        row.put("candidates", traces.stream().map(t -> Arrays.asList(t.anchorId(), t.axis(), t.state(), t.failureCode())).toList());
        row.put("findings", result.findings().stream().map(f -> Map.of("text", f.primaryText(), "reason", f.getReason())).toList());
        row.put("usage", client.videoUsage());
        if (client.harnessError != null) row.put("harnessError", client.harnessError);
        return row;
    }

    static void save(Path out, String sha, String state, TrialGuard guard, List<Map<String, Object>> rows, String scope) throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("state", state); report.put("planSha256", sha); report.put("attempts", guard.attempts);
        report.put("reservedEstimatedUsd", guard.reserved); report.put("budgetUsd", guard.budget); report.put("actualBillingConfirmed", false);
        report.put("scope", scope);
        report.put("summary", summarize(rows)); report.put("results", rows);
        Files.writeString(out.resolve("results.json"), JSON.writeValueAsString(report));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        check((args.length == 5 && args[0].equals("prepare")) || (args.length == 4 && args[0].equals("run")), "Invalid mode");
        Path out = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(root), "New private output required");
        if (args[0].equals("prepare")) {
            var plan = prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            byte[] bytes = JSON.writeValueAsBytes(plan);
            Files.createDirectory(out); Files.write(out.resolve("plan.json"), bytes, StandardOpenOption.CREATE_NEW);
            System.out.println("RECALL_FIRST_PREPARED providerCalls=0 runs=" + plan.runs().size() + " maxHttp=" + LIMIT
                    + " maxReservation=" + plan.maxReservation() + " sha=" + ReferenceOnlyTrial.sha(bytes));
            return;
        }
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[1])); String sha = ReferenceOnlyTrial.sha(bytes);
        check(sha.equals(args[2]) && CONSENT.equals(System.getenv("RECALL_FIRST_CONSENT")), "Fresh exact-plan consent required");
        var plan = JSON.readValue(bytes, Plan.class);
        var fresh = prepare(Path.of(plan.inputsPath()), Path.of(plan.labelsPath()), Path.of(plan.reviewPath()));
        check(JSON.valueToTree(plan).equals(JSON.valueToTree(fresh)), "Source, label, runtime, or code drift");
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]), out, sha, CONSENT, LIMIT);
        Files.createDirectory(out);
        var library = library(Path.of(plan.runtimePath()));
        var steps = plan.runs().stream().map(run -> new Step(caseOf(plan.cases(), run.caseId()), mode(run.recall()),
                run.recall(), library, run.initial(), s -> s)).toList();
        execute(out, sha, SCOPE, steps, new TrialGuard(LIMIT, PER_RUN, BUDGET), key);
    }

    /** Sends only frozen first requests through the guard; stops on provider, transport, budget, or harness failure. */
    static void execute(Path out, String sha, String scope, List<Step> steps, TrialGuard guard, String key) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        save(out, sha, "RUNNING", guard, rows, scope);
        try {
            for (var step : steps) {
                var c = step.condition();
                guard.beginRun();
                var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
                factory.setReadTimeout(Duration.ofSeconds(60));
                var rest = RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory)
                        .defaultHeader("Authorization", "Bearer " + key).requestInterceptor((request, data, execution) -> {
                            byte[] accepted = guard.accept(data, request.getURI().toString());
                            try { save(out, sha, "RUNNING", guard, rows, scope); }
                            catch (Exception e) { guard.blocked = true; throw new IllegalStateException("Persistence", e); }
                            request.getHeaders().setContentLength(accepted.length);
                            return execution.execute(request, accepted);
                        }).build();
                var client = new TrialClient(rest, c, step.mode(), false, guard, step.initial(), key, step.rewrite());
                client.beginVideo(0L); client.beginAnalyzer("recall-first-trial");
                var result = pipeline(client, c, step.library(), step.recall());
                rows.add(resultRow(c, step.mode(), result, client));
                boolean ok = continueAfter(result.status().toString(), guard.blocked, client.failureCount(), client.harnessError);
                if (!ok) guard.blocked = true;
                save(out, sha, ok ? "RUNNING" : "STOPPED", guard, rows, scope); client.endVideo();
                System.out.println("TRIAL_RESULT " + c.id() + " mode=" + step.mode() + " status=" + result.status()
                        + " findings=" + result.findings().size());
                check(ok, "Provider, transport, harness, or budget failure");
            }
            save(out, sha, "COMPLETE", guard, rows, scope);
        } catch (Exception e) { save(out, sha, "STOPPED", guard, rows, scope); throw e; }
    }
}
