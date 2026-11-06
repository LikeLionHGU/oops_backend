package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.TranscriptSegment;
import com.example.oops.domain.Video;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Opt-in experiment A CLI. {@code prepare SPEC NEW_OUTPUT} runs every selector offline, freezes
 * selections, input and code hashes, and the worst-case reservation (provider calls 0).
 * {@code run PLAN PLAN_SHA NEW_OUTPUT} needs CONTEXT_EXPERIMENT_CONSENT equal to the spec's consent,
 * OPENAI_API_KEY, and a fresh prepare identical to the plan; it stops at the HTTP or cost cap.
 * Outputs must be new directories under datasets/controversy/experiments.
 */
public final class ContextExperimentTrial {
    static final JsonMapper JSON = JsonMapper.builder().build();

    record SelectorSpec(String type, int budget, Integer topK, String labelsPath) {}

    record TranscriptSpec(Long videoId, String path, String vectorsPath, String scoresPath) {}

    record TargetSpec(Long videoId, String targetId) {}

    record Spec(String runId, String consent, String conditionId, SelectorSpec selector, List<TranscriptSpec> transcripts,
                List<TargetSpec> targets, int repeats, int maxHttpAttempts, double capUsd, String guidelinesRuntimePath,
                Map<String, String> metadata) {}

    record PlannedTarget(Long videoId, String targetId, String selectorId, String status, List<String> contextIds) {}

    record Plan(String specPath, String specSha256, Spec spec, Map<String, String> inputHashes, Map<String, String> implementation,
                List<PlannedTarget> targets, double maxReservationUsd) {}

    record GoldFile(String labelVersion, Map<String, Map<String, OracleContextSelector.OracleLabel>> labels) {}

    record SpeakerFile(String labelVersion, Map<String, Map<String, SpeakerTurnContextSelector.SpeakerLabel>> labels) {}

    private record Inputs(Map<Long, AnalysisContext> contexts, Map<Long, ContextSelector> selectors, Map<String, String> hashes) {}

    private ContextExperimentTrial() {
    }

    static Plan prepare(Path specPath) throws Exception {
        byte[] specBytes = Files.readAllBytes(specPath);
        var spec = JSON.readValue(specBytes, Spec.class);
        check(spec.runId() != null && spec.runId().matches("[A-Za-z0-9._-]{1,64}"), "Safe runId required");
        check(spec.consent() != null && spec.consent().matches("CTXA_[A-Z0-9_]{8,64}"), "Specific CTXA_ consent token required");
        check(spec.conditionId() != null && !spec.conditionId().isBlank(), "conditionId required");
        check(spec.repeats() >= 1 && spec.maxHttpAttempts() >= 1, "Positive repeats and HTTP limit required");
        check(!spec.targets().isEmpty(), "Targets required");
        double worst = spec.maxHttpAttempts() * ConfrontationStageTrial.reservation(ConfrontationStageTrial.MAX_BYTES);
        check(worst <= spec.capUsd(), "Worst-case reservation exceeds cap");
        Path base = specPath.toRealPath().getParent();
        var inputs = inputs(spec, base);
        List<PlannedTarget> planned = new ArrayList<>();
        Set<TargetSpec> seen = new HashSet<>();
        for (var t : spec.targets()) {
            check(seen.add(t), "Duplicate target: " + t);
            var context = inputs.contexts().get(t.videoId());
            check(context != null, "No transcript for video " + t.videoId());
            var transcript = CandidateReviewEngine.speechInput(context);
            var target = transcript.find(t.targetId()).orElseThrow(() -> new IllegalArgumentException("Unknown target: " + t));
            var selection = inputs.selectors().get(t.videoId()).select(target, transcript);
            selection.toBatch(target, transcript);
            planned.add(new PlannedTarget(t.videoId(), t.targetId(), selection.selectorId(), selection.status().name(),
                    selection.contextSegmentIds()));
        }
        var implementation = new TreeMap<>(ConfrontationStageTrial.implementation());
        for (String f : List.of("ContextExperimentTrial.java", "ConfrontationStageTrial.java")) {
            var path = Path.of("src/test/java/com/example/oops/analyzer/" + f);
            implementation.put(path.toString(), ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
        }
        return new Plan(specPath.toRealPath().toString(), ReferenceOnlyTrial.sha(specBytes), spec, inputs.hashes(),
                implementation, List.copyOf(planned), worst);
    }

    static Plan authorize(byte[] planBytes, String sha, String consent) throws Exception {
        check(ReferenceOnlyTrial.sha(planBytes).equals(sha), "Exact plan SHA required");
        var plan = JSON.readValue(planBytes, Plan.class);
        check(consent != null && consent.equals(plan.spec().consent()), "Fresh exact consent required");
        var fresh = prepare(Path.of(plan.specPath()));
        check(JSON.valueToTree(plan).equals(JSON.valueToTree(fresh)), "Spec, input or code drift since prepare");
        return plan;
    }

    private static Inputs inputs(Spec spec, Path base) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        Map<Long, AnalysisContext> contexts = new HashMap<>();
        Map<Long, ContextSelector> selectors = new HashMap<>();
        var budget = new ContextTokenBudget(spec.selector().budget());
        resolve(base, spec.guidelinesRuntimePath(), hashes);
        GoldFile gold = null;
        SpeakerFile speakers = null;
        String type = spec.selector().type();
        check(Set.of("A0", "A1", "A2", "A3", "A4", "A5", "A6H").contains(type), "Unknown selector type: " + type);
        if (type.equals("A4")) gold = JSON.readValue(Files.readAllBytes(resolve(base, spec.selector().labelsPath(), hashes)), GoldFile.class);
        if (type.equals("A6H"))
            speakers = JSON.readValue(Files.readAllBytes(resolve(base, spec.selector().labelsPath(), hashes)), SpeakerFile.class);
        for (var t : spec.transcripts()) {
            check(!contexts.containsKey(t.videoId()), "Duplicate transcript for video " + t.videoId());
            contexts.put(t.videoId(), context(t.videoId(), resolve(base, t.path(), hashes)));
            String video = String.valueOf(t.videoId());
            ContextSelector selector = switch (type) {
                case "A0" -> new DialoguePageContextSelector();
                case "A1" -> new AdjacentContextSelector(budget);
                case "A5" -> new Bm25ContextSelector(budget);
                case "A2" -> new EmbeddingContextSelector(EmbeddingContextSelector.load(resolve(base, t.vectorsPath(), hashes)), budget);
                case "A3" -> {
                    check(spec.selector().topK() != null, "topK required for A3");
                    var retrieval = new EmbeddingContextSelector(EmbeddingContextSelector.load(resolve(base, t.vectorsPath(), hashes)), budget);
                    yield new RerankContextSelector(retrieval, RerankContextSelector.load(resolve(base, t.scoresPath(), hashes)),
                            spec.selector().topK(), budget);
                }
                case "A4" -> new OracleContextSelector(gold.labels().getOrDefault(video, Map.of()), gold.labelVersion(), budget);
                default -> new SpeakerTurnContextSelector(speakers.labels().getOrDefault(video, Map.of()), speakers.labelVersion(), budget);
            };
            selectors.put(t.videoId(), selector);
        }
        return new Inputs(contexts, selectors, hashes);
    }

    private static Path resolve(Path base, String relative, Map<String, String> hashes) throws Exception {
        check(relative != null && !relative.isBlank(), "Input path required");
        Path path = base.resolve(relative).toRealPath();
        hashes.put(path.toString(), ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
        return path;
    }

    /** Builds the analysis context and checks that regenerated speech IDs equal the frozen file IDs. */
    private static AnalysisContext context(Long videoId, Path path) throws Exception {
        var video = Video.builder().filename("experiment-" + videoId).build();
        ReflectionTestUtils.setField(video, "id", videoId);
        var rows = JSON.readTree(Files.readAllBytes(path)).path("segments");
        List<TranscriptSegment> segments = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (var row : rows) {
            String text = row.path("text").asText();
            check(!text.isBlank(), "Blank segment text in " + path);
            segments.add(new TranscriptSegment(video, row.path("startMs").asLong(), row.path("endMs").asLong(), text));
            expected.put(row.path("id").asText(), text);
        }
        var context = new AnalysisContext(video, ContentGenre.GENERAL, segments, List.of());
        Map<String, String> actual = new LinkedHashMap<>();
        CandidateReviewEngine.speechInput(context).segments().forEach(s -> actual.put(s.id(), s.text()));
        check(actual.equals(expected), "Transcript IDs must be the frozen stt-index IDs: " + path);
        return context;
    }

    static final class HttpGuard {
        private final int maxAttempts;
        private final double capUsd;
        private int attempts;
        private double reserved;
        private boolean blocked;

        HttpGuard(int maxAttempts, double capUsd) {
            this.maxAttempts = maxAttempts;
            this.capUsd = capUsd;
        }

        synchronized void accept(int bytes) {
            double next = ConfrontationStageTrial.reservation(bytes);
            if (blocked || attempts >= maxAttempts || reserved + next > capUsd) {
                blocked = true;
                throw new IllegalStateException("Experiment HTTP or cost cap reached");
            }
            attempts++;
            reserved += next;
        }

        synchronized boolean blocked() {
            return blocked;
        }

        synchronized int attempts() {
            return attempts;
        }

        synchronized double reserved() {
            return reserved;
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 3 && args[0].equals("prepare") || args.length == 4 && args[0].equals("run"), "Invalid mode");
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path out = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(root), "New private output required");
        if (args[0].equals("prepare")) {
            var plan = prepare(Path.of(args[1]));
            byte[] bytes = JSON.writeValueAsBytes(plan);
            Files.createDirectory(out);
            Files.write(out.resolve("plan.json"), bytes, StandardOpenOption.CREATE_NEW);
            System.out.println("CTXA_PREPARED providerCalls=0 targets=" + plan.targets().size() + " repeats=" + plan.spec().repeats()
                    + " maxHttp=" + plan.spec().maxHttpAttempts() + " maxReservationUsd=" + plan.maxReservationUsd()
                    + " sha=" + ReferenceOnlyTrial.sha(bytes));
            return;
        }
        byte[] planBytes = Files.readAllBytes(Path.of(args[1]));
        var plan = authorize(planBytes, args[2], System.getenv("CONTEXT_EXPERIMENT_CONSENT"));
        String key = System.getenv("OPENAI_API_KEY");
        check(key != null && !key.isBlank(), "OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]), out, args[2], plan.spec().consent(), plan.spec().maxHttpAttempts());
        var guard = new HttpGuard(plan.spec().maxHttpAttempts(), plan.spec().capUsd());
        var client = new OpenAiClient(guardedRest(key, guard), ConfrontationStageTrial.props(key), true);
        var inputs = inputs(plan.spec(), Path.of(plan.specPath()).getParent());
        var guidelines = ConstraintBaselineTrial.baseOnly(Files.readString(Path.of(plan.specPath()).getParent()
                .resolve(plan.spec().guidelinesRuntimePath())));
        var metadata = new TreeMap<>(plan.spec().metadata());
        metadata.put("planSha256", args[2]);
        metadata.put("consent", plan.spec().consent());
        var options = new CandidateReviewEngine.RunOptions(24, 8, false, new CandidateReviewEngine.VerificationFeatures(false, false, false), false);
        var store = ExperimentResultStore.create(out, plan.spec().runId(), metadata);
        var runner = new ContextExperimentRunner(client, guidelines, options, store);
        for (int repeat = 0; repeat < plan.spec().repeats(); repeat++) {
            for (var t : plan.targets()) {
                runner.run(inputs.contexts().get(t.videoId()), plan.spec().conditionId(), inputs.selectors().get(t.videoId()),
                        List.of(t.targetId()), repeat);
                System.out.println("CTXA_RESULT repeat=" + repeat + " video=" + t.videoId() + " target=" + t.targetId()
                        + " http=" + guard.attempts() + " reservedUsd=" + guard.reserved());
                check(!guard.blocked(), "Stopped at HTTP or cost cap; the last record may be incomplete");
            }
        }
        System.out.println("CTXA_COMPLETE http=" + guard.attempts() + " reservedUsd=" + guard.reserved() + " actualBillingConfirmed=false");
    }

    private static RestClient guardedRest(String key, HttpGuard guard) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    check(request.getURI().toString().equals(ConfrontationStageTrial.ENDPOINT), "Unexpected endpoint");
                    guard.accept(body.length);
                    return execution.execute(request, body);
                }).build();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
