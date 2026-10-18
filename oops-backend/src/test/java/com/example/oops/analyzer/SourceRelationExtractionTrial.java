package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Text-only, test-only source-relation extraction. Never wired into runtime analysis. */
public final class SourceRelationExtractionTrial {
    static final String SOURCE_SHA = "b463b3658b44f9fdceab65f3b13bbfc8054991a1e4710bfc2ddf3fb1f13f5afa";
    static final String LABEL_SHA = "8ca23b42f717e27c2b524912f09f87a734e0cd4d1d261aab262a743e9c02d8e9";
    static final String CONSENT = "USER_APPROVED_15_CALL_GPT6LUNA_SOURCE_RELATIONS_TEXT_USD050_20261018";
    static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    static final String SYSTEM = "주어진 한국어 대화에서 초점 발화의 대화상 기능과 다른 발화와의 관계를 원문 근거로 추출한다. "
            + "질문 문구가 단어 뜻·약어를 설명하는 내용인지, 현재 듣는 사람에게 실제로 한 질문/요청인지 구별한다. "
            + "화자의 자기 제안과 듣는 사람에게 한 요청, 과거 인용·보고와 현재 발화를 구별한다. "
            + "초점 발화와 직접 연결되는 발화만 실제 입력 ID로 연결하고 근거 ID를 제시한다. "
            + "논란·위험·정상 여부, 도덕 평가, 의도, 신원, 동의, 억양, 표정, 실제 행동을 추정하지 않는다. "
            + "제공된 전사만으로 기능을 정할 수 없으면 UNCLEAR를 사용한다. "
            + "출력은 정확히 다음 모양의 JSON 객체다: {\"observations\":[{\"anchorId\":\"초점 ID 복사\","
            + "\"function\":\"OTHER\",\"contentUse\":\"UNCLEAR\",\"links\":[],"
            + "\"evidenceIds\":[\"초점 ID 복사\"]}]}. 이 값은 모양 예시다. "
            + "function은 DEFINITION_OR_EXPANSION, DIRECT_REQUEST_TO_LISTENER, SELF_OFFER, REPORT_OR_QUOTE, SELF_DESCRIPTION, OTHER, UNCLEAR 중 하나다. "
            + "contentUse는 CURRENT_ACT, MENTIONED_PHRASE, REPORTED_CONTENT, UNCLEAR 중 하나다. "
            + "relation은 ANSWERS_MEANING_QUESTION, CONTINUES_REQUEST, CLARIFIES_NOT_DIRECT_QUESTION, CLARIFIES_PURPOSE, "
            + "RESPONDS_TO_REQUEST, TOPIC_CHANGE_AFTER_REFUSAL, OTHER, UNCLEAR 중 하나다. "
            + "관찰은 초점 ID당 하나, 모든 ID는 입력에서 복사한다. 필드는 모두 포함하고 JSON 외 설명은 붙이지 않는다.";
    static final int MAX_COMPLETION_TOKENS = 1024;
    static final double CAP_USD = .50;
    static final double DEFAULT_INPUT_RATE = .10;
    static final double DEFAULT_OUTPUT_RATE = .50;

    private SourceRelationExtractionTrial() {}

    record Segment(String id, long startMs, long endMs, String text) {}
    record Link(String segmentId, String relation) {}
    record Observation(String anchorId, String function, String contentUse, List<Link> links, List<String> evidenceIds) {}
    record RelationResult(List<Observation> observations) {}
    record Expected(String function, String contentUse, List<Link> links) {}
    record Condition(String conditionId, String labelStatus, String anchorId, List<Segment> segments, Expected expected) {}
    record Request(String conditionId, int repeat, String system, String input, Expected expected) {}
    record Plan(String schemaVersion, String model, String reasoningEffort, String inputSha256, String labelSha256,
                String scope, boolean expectedLabelsSentToProvider, boolean mediaSentToProvider,
                int calls, int maxCompletionTokens, double estimatedCapUsd, double inputPer1m,
                double outputPer1m, String system, List<Condition> conditions, List<Request> requests) {}

    static Plan prepare(byte[] source, byte[] labels) throws Exception {
        check(SOURCE_SHA.equals(ReferenceOnlyTrial.sha(source)), "Frozen approved input bundle changed");
        check(LABEL_SHA.equals(ReferenceOnlyTrial.sha(labels)), "Frozen human-review labels changed");
        JsonNode sourceRows = JSON.readTree(source), labelRows = JSON.readTree(labels);
        var expected = expected();
        var conditions = new ArrayList<Condition>();
        for (String id : List.of("R01-166", "R04", "R05", "R06", "R08")) {
            JsonNode row = exactlyOne(sourceRows, "caseId", id);
            JsonNode label = exactlyOne(labelRows, "caseId", id);
            String decision = switch (id) {
                case "R04", "R08" -> "REVIEW_REQUIRED";
                default -> "PASS";
            };
            check(decision.equals(label.path("expectedDecision").asText()), "Approved label drift: " + id);
            String scope = label.path("labelScope").asText();
            check(!scope.isBlank() && !scope.contains("NOT_HUMAN_GOLD"), "Human-approved development boundary required");
            String anchor = row.path("anchorId").asText();
            if (id.equals("R01-166")) check("groq-v4-166".equals(anchor) && row.path("raw").size() == 32,
                    "R01-166 full 32-line window required");
            else check(anchor.startsWith("stt-index-"), "Synthetic anchor changed: " + id);
            var segments = new ArrayList<Segment>();
            for (JsonNode raw : row.path("raw")) {
                String sid = raw.path("id").asText();
                long start = raw.path("startMs").asLong(-1), end = raw.path("endMs").asLong(-1);
                String text = raw.path("text").asText();
                check(!sid.isBlank() && start >= 0 && end >= start && !text.isBlank() && text.length() <= 2000,
                        "Invalid source segment: " + id);
                segments.add(new Segment(sid, start, end, text));
            }
            check(segments.stream().anyMatch(s -> s.id().equals(anchor)), "Anchor missing from source: " + id);
            conditions.add(new Condition(id, scope, anchor, List.copyOf(segments), expected.get(id)));
        }
        var byId = new HashMap<String, Condition>();
        conditions.forEach(c -> byId.put(c.conditionId(), c));
        List<List<String>> orders = List.of(
                List.of("R01-166", "R04", "R05", "R06", "R08"),
                List.of("R08", "R06", "R05", "R04", "R01-166"),
                List.of("R05", "R06", "R08", "R01-166", "R04"));
        var requests = new ArrayList<Request>();
        for (int repeat = 1; repeat <= 3; repeat++) for (String id : orders.get(repeat - 1)) {
            Condition c = byId.get(id);
            // The provider input contains only the focus ID and source segments; labels stay in the offline plan.
            String input = JSON.writeValueAsString(Map.of("focusId", c.anchorId(), "segments", c.segments()));
            requests.add(new Request(c.conditionId(), repeat, SYSTEM, input, c.expected()));
        }
        double inputRate = rate("OPENAI_INPUT_PER_1M", DEFAULT_INPUT_RATE);
        double outputRate = rate("OPENAI_OUTPUT_PER_1M", DEFAULT_OUTPUT_RATE);
        var plan = new Plan("source-relation-extraction-2", "gpt-6-luna", "none", SOURCE_SHA, LABEL_SHA,
                "5_FIXED_CONDITIONS_X_3_REPEATS_TEXT_ONLY_EXTRACTION_NOT_RISK_JUDGMENT", false, false,
                15, MAX_COMPLETION_TOKENS, reservation(requests, inputRate, outputRate), inputRate, outputRate,
                SYSTEM, List.copyOf(conditions), List.copyOf(requests));
        validate(plan);
        check(plan.estimatedCapUsd() <= CAP_USD, "Reserved estimate exceeds newly approved cap");
        return plan;
    }

    static Map<String, Expected> expected() {
        var map = new LinkedHashMap<String, Expected>();
        map.put("R01-166", new Expected("DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE",
                List.of(new Link("groq-v4-165", "ANSWERS_MEANING_QUESTION"))));
        map.put("R04", new Expected("DIRECT_REQUEST_TO_LISTENER", "CURRENT_ACT",
                List.of(new Link("stt-index-0", "CONTINUES_REQUEST"))));
        map.put("R05", new Expected("DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE",
                List.of(new Link("stt-index-0", "ANSWERS_MEANING_QUESTION"),
                        new Link("stt-index-2", "CLARIFIES_NOT_DIRECT_QUESTION"))));
        map.put("R06", new Expected("REPORT_OR_QUOTE", "REPORTED_CONTENT",
                List.of(new Link("stt-index-1", "CLARIFIES_PURPOSE"))));
        map.put("R08", new Expected("DIRECT_REQUEST_TO_LISTENER", "CURRENT_ACT",
                List.of(new Link("stt-index-1", "RESPONDS_TO_REQUEST"),
                        new Link("stt-index-2", "TOPIC_CHANGE_AFTER_REFUSAL"))));
        return Collections.unmodifiableMap(map);
    }

    static JsonNode exactlyOne(JsonNode rows, String field, String value) {
        List<JsonNode> matches = new ArrayList<>();
        rows.forEach(row -> { if (value.equals(row.path(field).asText())) matches.add(row); });
        check(matches.size() == 1, "Exactly one frozen row required: " + value);
        return matches.get(0);
    }

    static double rate(String key, double fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        double parsed = Double.parseDouble(value);
        check(parsed >= 0 && parsed <= 1000, "Invalid configured unit estimate: " + key);
        return parsed;
    }

    static double reservation(List<Request> requests, double inputRate, double outputRate) {
        double amount = 0;
        for (Request r : requests) {
            int bytes = JSON.writeValueAsBytes(requestBody(r)).length;
            // UTF-8 bytes conservatively reserve input; output is bounded by max_completion_tokens.
            amount += (bytes + 1024d) * inputRate / 1_000_000d
                    + MAX_COMPLETION_TOKENS * outputRate / 1_000_000d;
        }
        return amount;
    }

    static Map<String, Object> requestBody(Request request) {
        var props = new OpenAiProperties("offline-test-key", null, null, "https://api.openai.com/v1",
                "gpt-6-luna", "none", Duration.ofSeconds(2), null);
        var builder = new OpenAiClient(RestClient.create(), props);
        var body = new LinkedHashMap<>(builder.replayRequestBody(request.system(), request.input()));
        body.put("max_completion_tokens", MAX_COMPLETION_TOKENS);
        return body;
    }

    static void validate(Plan p) {
        check("source-relation-extraction-2".equals(p.schemaVersion()) && "gpt-6-luna".equals(p.model())
                && "none".equals(p.reasoningEffort()) && p.calls() == 15 && p.maxCompletionTokens() == 1024
                && !p.expectedLabelsSentToProvider() && !p.mediaSentToProvider()
                && SOURCE_SHA.equals(p.inputSha256()) && LABEL_SHA.equals(p.labelSha256())
                && p.estimatedCapUsd() <= CAP_USD && p.conditions().size() == 5 && p.requests().size() == 15,
                "Fixed approved trial contract required");
        check(p.conditions().stream().map(Condition::conditionId).toList()
                .equals(List.of("R01-166", "R04", "R05", "R06", "R08")), "Condition set/order changed");
        check(p.requests().stream().map(Request::conditionId).toList()
                .equals(List.of("R01-166", "R04", "R05", "R06", "R08",
                        "R08", "R06", "R05", "R04", "R01-166",
                        "R05", "R06", "R08", "R01-166", "R04")), "Counterbalanced repeat order changed");
        Set<String> functions = Set.of("DEFINITION_OR_EXPANSION", "DIRECT_REQUEST_TO_LISTENER", "SELF_OFFER",
                "REPORT_OR_QUOTE", "SELF_DESCRIPTION", "OTHER", "UNCLEAR");
        Set<String> contentUses = Set.of("CURRENT_ACT", "MENTIONED_PHRASE", "REPORTED_CONTENT", "UNCLEAR");
        Set<String> relations = Set.of("ANSWERS_MEANING_QUESTION", "CONTINUES_REQUEST",
                "CLARIFIES_NOT_DIRECT_QUESTION", "CLARIFIES_PURPOSE", "RESPONDS_TO_REQUEST",
                "TOPIC_CHANGE_AFTER_REFUSAL", "OTHER", "UNCLEAR");
        Set<String> ids = new HashSet<>();
        for (Condition c : p.conditions()) c.segments().forEach(s -> ids.add(s.id()));
        for (Condition c : p.conditions()) {
            check(functions.contains(c.expected().function()) && contentUses.contains(c.expected().contentUse()), "Unknown offline label");
            check(c.segments().stream().anyMatch(s -> s.id().equals(c.anchorId())), "Anchor missing from condition");
            for (Link link : c.expected().links()) check(relations.contains(link.relation())
                    && c.segments().stream().anyMatch(s -> s.id().equals(link.segmentId())), "Invalid offline relation label");
        }
        Map<String, Condition> byId = new HashMap<>();
        p.conditions().forEach(c -> byId.put(c.conditionId(), c));
        for (Request r : p.requests()) {
            check(r.system().equals(SYSTEM), "Prompt drift");
            JsonNode input = JSON.readTree(r.input());
            check(input.size() == 2 && input.has("focusId") && input.has("segments")
                    && !input.toString().contains("expected") && !input.toString().contains("labelStatus"), "Leaking evaluator metadata");
            Condition c = byId.get(r.conditionId());
            check(c != null && r.expected().equals(c.expected()), "Request label differs from approved offline label");
            check(c.anchorId().equals(input.path("focusId").asText()), "Request focus differs from approved anchor");
            check(JSON.readTree(JSON.writeValueAsString(c.segments())).equals(input.path("segments")),
                    "Request segments differ from approved source snapshot");
            check(JSON.readTree(JSON.writeValueAsString(requestBody(r))).path("max_completion_tokens").asInt() == 1024,
                    "Output cap missing");
        }
        check(p.estimatedCapUsd() <= CAP_USD && p.estimatedCapUsd() >= 0, "Budget out of range");
    }

    static Map<String, Object> assess(Request request, RelationResult result) {
        if (result == null || result.observations() == null || result.observations().size() != 1)
            return Map.of("accepted", false, "failure", "ONE_OBSERVATION_REQUIRED");
        Observation o = result.observations().get(0);
        // The expected label is joined offline only after the response has been parsed.
        if (!o.anchorId().equals(focusId(request.input()))) return Map.of("accepted", false, "failure", "ANCHOR_MISMATCH");
        Set<String> allowedFunctions = Set.of("DEFINITION_OR_EXPANSION", "DIRECT_REQUEST_TO_LISTENER", "SELF_OFFER",
                "REPORT_OR_QUOTE", "SELF_DESCRIPTION", "OTHER", "UNCLEAR");
        Set<String> allowedContent = Set.of("CURRENT_ACT", "MENTIONED_PHRASE", "REPORTED_CONTENT", "UNCLEAR");
        Set<String> allowedRelations = Set.of("ANSWERS_MEANING_QUESTION", "CONTINUES_REQUEST", "CLARIFIES_NOT_DIRECT_QUESTION",
                "CLARIFIES_PURPOSE", "RESPONDS_TO_REQUEST", "TOPIC_CHANGE_AFTER_REFUSAL", "OTHER", "UNCLEAR");
        JsonNode input = JSON.readTree(request.input());
        Set<String> ids = new HashSet<>(); input.path("segments").forEach(s -> ids.add(s.path("id").asText()));
        if (!allowedFunctions.contains(o.function()) || !allowedContent.contains(o.contentUse())
                || o.links() == null || o.links().size() > 8 || o.evidenceIds() == null || o.evidenceIds().isEmpty())
            return Map.of("accepted", false, "failure", "OUTPUT_ENUM_OR_SHAPE");
        if (!o.evidenceIds().contains(o.anchorId()) || o.evidenceIds().stream().anyMatch(id -> !ids.contains(id))
                || o.evidenceIds().size() > 12 || new HashSet<>(o.evidenceIds()).size() != o.evidenceIds().size())
            return Map.of("accepted", false, "failure", "EVIDENCE_ID_SCOPE");
        if (o.links().stream().anyMatch(link -> link == null || !ids.contains(link.segmentId())
                || !allowedRelations.contains(link.relation()))
                || new HashSet<>(o.links()).size() != o.links().size())
            return Map.of("accepted", false, "failure", "LINK_ID_SCOPE");
        Expected c = expected().get(request.conditionId());
        boolean functionMatch = c.function().equals(o.function());
        boolean contentMatch = c.contentUse().equals(o.contentUse());
        boolean linksMatch = new HashSet<>(c.links()).equals(new HashSet<>(o.links()));
        return Map.of("accepted", true, "functionMatch", functionMatch, "contentUseMatch", contentMatch,
                "exactLinksMatch", linksMatch, "allExpectedRelationsMatch", functionMatch && contentMatch && linksMatch);
    }

    static String focusId(String input) { return JSON.readTree(input).path("focusId").asText(); }

    static Map<String, Object> audit(Plan plan, JsonNode saved) {
        String state = saved.path("state").asText();
        check(Set.of("COMPLETE", "STOPPED").contains(state) && saved.path("httpAttempts").asInt() == 15
                && saved.path("results").size() == 15, "Complete exact 15-call result required");
        int exact = 0, valid = 0;
        var perCondition = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < plan.requests().size(); i++) {
            Request req = plan.requests().get(i); JsonNode row = saved.path("results").get(i);
            check(req.conditionId().equals(row.path("conditionId").asText()) && req.repeat() == row.path("repeat").asInt()
                    && row.path("httpAttempts").asInt() == 1 && req.system().equals(row.path("system").asText())
                    && JSON.readTree(req.input()).equals(row.path("input")), "Request/result mismatch");
            RelationResult output = JSON.treeToValue(row.path("output"), RelationResult.class);
            Map<String, Object> assessment = assess(req, output);
            check(JSON.valueToTree(assessment).equals(row.path("assessment")), "Assessment replay mismatch");
            if (Boolean.TRUE.equals(assessment.get("accepted"))) valid++;
            if (Boolean.TRUE.equals(assessment.get("allExpectedRelationsMatch"))) exact++;
            String signature = Boolean.TRUE.equals(assessment.get("accepted"))
                    ? output.observations().get(0).function() + "/" + output.observations().get(0).contentUse()
                        + "/" + JSON.writeValueAsString(output.observations().get(0).links())
                    : "INVALID/" + assessment.getOrDefault("failure", "UNKNOWN");
            perCondition.computeIfAbsent(req.conditionId(), ignored -> new ArrayList<>()).add(signature);
        }
        var repeat = new LinkedHashMap<String, Object>();
        perCondition.forEach((id, values) -> repeat.put(id, Map.of("stableAcross3", values.stream().distinct().count() == 1,
                "distinctOutputs", values.stream().distinct().count())));
        return Map.of("state", state, "calls", 15, "providerRetries", 0, "structurallyAccepted", valid,
                "exactExpectedRelationMatches", exact, "repeatStability", repeat,
                "rawProviderResponsePreserved", saved.path("results").get(0).has("providerResponse"),
                "independentGold", false, "runtimePromotion", false);
    }

    static void save(Path output, Plan plan, List<Map<String, Object>> rows, int attempts,
                     double reserved, double usage, String state) throws Exception {
        Path resultPath = output.resolve("results.json");
        Files.write(resultPath, JSON.writeValueAsBytes(Map.of("schemaVersion", plan.schemaVersion(),
                "planSha256", ReferenceOnlyTrial.sha(JSON.writeValueAsBytes(plan)), "state", state,
                "results", rows, "httpAttempts", attempts, "reservedEstimatedUsd", reserved,
                "usageEstimatedUsd", usage, "actualBillingConfirmed", false, "maxHttpAttempts", plan.calls())),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.setPosixFilePermissions(resultPath, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
    }

    static void writePrivate(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { /* best effort on non-POSIX filesystems */ }
    }

    static final class Guard {
        private JsonNode expected;
        private boolean sent;
        void begin(byte[] body) { expected = JSON.readTree(body); sent = false; }
        byte[] accept(byte[] body, String uri) {
            check(ENDPOINT.equals(uri) && expected != null && !sent, "Unexpected endpoint or duplicate HTTP attempt");
            JsonNode actual = JSON.readTree(body);
            check(expected.equals(actual), "Only the frozen text-only request may leave");
            check(!actual.toString().contains("OPENAI_API_KEY") && !actual.toString().contains("expectedDecision"),
                    "Credential or label in request body");
            sent = true;
            return body;
        }
    }

    static void run(Plan plan, byte[] frozenPlan, Path output) throws Exception {
        validate(plan);
        check(ReferenceOnlyTrial.sha(frozenPlan).equals(ReferenceOnlyTrial.sha(JSON.writeValueAsBytes(plan))), "Frozen plan changed");
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path destination = output.toAbsolutePath().normalize();
        check(!Files.exists(destination) && destination.getParent().toRealPath().startsWith(root), "New private output directory required");
        String key = System.getenv("OPENAI_API_KEY");
        check(key != null && !key.isBlank(), "OPENAI_API_KEY is unavailable to this execution environment");
        double inputRate = plan.inputPer1m(), outputRate = plan.outputPer1m();
        try { Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        catch (UnsupportedOperationException e) { Files.createDirectory(destination); }
        writePrivate(destination.resolve("plan.json"), frozenPlan);
        writePrivate(destination.resolve("authorization.json"), JSON.writeValueAsBytes(Map.of("capUsd", CAP_USD,
                "consent", CONSENT, "planSha256", ReferenceOnlyTrial.sha(frozenPlan),
                "textEgressApproved", true, "videoAudioEgressApproved", false, "priorBudgetReused", false)));
        writePrivate(destination.resolve("execution-claim.json"), JSON.writeValueAsBytes(Map.of(
                "planSha256", ReferenceOnlyTrial.sha(frozenPlan), "consent", CONSENT,
                "maxHttpAttempts", 15, "resumable", false)));
        var budget = new TrialBudget(CAP_USD, inputRate, outputRate, output);
        Guard guard = new Guard();
        var props = new OpenAiProperties(key, System.getenv("OPENAI_ORG_ID"), System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1", plan.model(), plan.reasoningEffort(), Duration.ofSeconds(90),
                new OpenAiProperties.Pricing(inputRate, .01, outputRate, null, null));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        var builder = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    byte[] guarded = guard.accept(body, request.getURI().toString());
                    budget.reserve(guarded.length, plan.maxCompletionTokens());
                    request.getHeaders().setContentLength(guarded.length);
                    return execution.execute(request, guarded);
                });
        if (props.hasOrganization()) builder.defaultHeader("OpenAI-Organization", props.organization());
        if (props.hasProject()) builder.defaultHeader("OpenAI-Project", props.project());
        RestClient client = builder.build();
        List<Map<String, Object>> rows = new ArrayList<>();
        double usageCost = 0;
        save(destination, plan, rows, budget.attempts, budget.reserved, usageCost, "RUNNING");
        try {
            for (Request request : plan.requests()) {
                var expectedBody = requestBody(request);
                guard.begin(JSON.writeValueAsBytes(expectedBody));
                int before = budget.attempts;
                long started = System.nanoTime();
                JsonNode providerResponse = client.post().uri("/chat/completions").body(expectedBody)
                        .retrieve().body(JsonNode.class);
                check(providerResponse != null, "Empty provider response; stop without retrying the trial");
                JsonNode usage = providerResponse.path("usage");
                long promptTokens = usage.path("prompt_tokens").asLong(-1);
                long cachedTokens = usage.at("/prompt_tokens_details/cached_tokens").asLong(0);
                long completionTokens = usage.path("completion_tokens").asLong(-1);
                check(promptTokens >= 0 && completionTokens >= 0 && cachedTokens >= 0 && cachedTokens <= promptTokens,
                        "Missing/invalid usage; stop without retrying the trial");
                double requestCost = new OpenAiClient.TokenUsage(1, promptTokens, cachedTokens, completionTokens,
                        plan.model(), new OpenAiProperties.Pricing(inputRate, .01, outputRate, null, null)).costUsd();
                usageCost += requestCost;
                JsonNode choice = providerResponse.at("/choices/0");
                String finishReason = choice.path("finish_reason").asText("");
                String refusal = choice.at("/message/refusal").asText("");
                String content = choice.at("/message/content").asText("");
                RelationResult parsed = null;
                String parseFailure = null;
                if (!refusal.isBlank()) parseFailure = "MODEL_REFUSAL";
                else if (!"stop".equals(finishReason)) parseFailure = "INCOMPLETE_FINISH_REASON:" + finishReason;
                else if (content.isBlank()) parseFailure = "EMPTY_CONTENT";
                else try { parsed = JSON.readValue(content, RelationResult.class); }
                catch (Exception ex) { parseFailure = "INVALID_JSON:" + ex.getClass().getSimpleName(); }
                Map<String, Object> assessment = assess(request, parsed);
                var row = new LinkedHashMap<String, Object>();
                row.put("conditionId", request.conditionId()); row.put("repeat", request.repeat());
                row.put("system", request.system()); row.put("input", JSON.readTree(request.input()));
                row.put("providerResponse", providerResponse); row.put("providerContent", content);
                row.put("output", parsed); row.put("parseFailure", parseFailure); row.put("assessment", assessment);
                row.put("usage", Map.of("calls", 1, "promptTokens", promptTokens, "cachedTokens", cachedTokens,
                        "completionTokens", completionTokens, "costEstimatedUsd", requestCost));
                row.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                row.put("httpAttempts", budget.attempts - before); rows.add(row);
                save(destination, plan, rows, budget.attempts, budget.reserved, usageCost, "RUNNING");
                check(guard.sent && budget.attempts - before == 1 && !budget.exhausted,
                        "Call count/budget mismatch; stop without retrying the trial");
                System.out.println("SOURCE_RELATION_TRIAL " + rows.size() + "/15 " + request.conditionId() + " repeat=" + request.repeat());
            }
            check(budget.attempts == 15 && rows.size() == 15, "Exact 15-call completion required");
            save(destination, plan, rows, budget.attempts, budget.reserved, usageCost, "COMPLETE");
            var audit = audit(plan, JSON.readTree(Files.readAllBytes(destination.resolve("results.json"))));
            writePrivate(destination.resolve("offline-audit.json"), JSON.writeValueAsBytes(audit));
            System.out.println("SOURCE_RELATION_COMPLETE calls=15 reservedEstimatedUsd=" + budget.reserved
                    + " usageEstimatedUsd=" + usageCost + " actualBillingConfirmed=false");
        } catch (Exception ex) {
            // Preserve partial results. The run is deliberately non-resumable; no automatic replay.
            save(destination, plan, rows, budget.attempts, budget.reserved, usageCost, "STOPPED");
            throw ex;
        }
    }

    static final class TrialBudget {
        final double cap, inputRate, outputRate;
        final Path output;
        double reserved;
        int attempts;
        boolean exhausted;
        TrialBudget(double cap, double inputRate, double outputRate, Path output) {
            check(cap == CAP_USD, "Only freshly approved $0.50 cap allowed");
            this.cap = cap; this.inputRate = inputRate; this.outputRate = outputRate; this.output = output;
        }
        void reserve(int bodyBytes, int maxOutput) {
            double next = (bodyBytes + 1024d) * inputRate / 1_000_000d
                    + maxOutput * outputRate / 1_000_000d;
            if (attempts >= 15 || reserved + next > cap) { exhausted = true; throw new IllegalStateException("Approved call/cost cap reached"); }
            reserved += next; attempts++;
            try {
                Files.writeString(output.resolve("reservation.json"), JSON.writeValueAsString(Map.of("capUsd", cap,
                        "maxHttpAttempts", 15, "httpAttempts", attempts, "reservedEstimatedUsd", reserved,
                        "inputPer1m", inputRate, "outputPer1m", outputRate, "maxCompletionTokensPerCall", maxOutput,
                        "actualBillingConfirmed", false)));
            } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot persist pre-request reservation", ex); }
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length >= 1, "prepare or run required");
        if ("prepare".equals(args[0])) {
            check(args.length == 4, "prepare frozen inputs labels and new private plan directory");
            byte[] source = OfflineDecisionContractAudit.bounded(Path.of(args[1]));
            byte[] labels = OfflineDecisionContractAudit.bounded(Path.of(args[2]));
            Plan plan = prepare(source, labels);
            Path output = Path.of(args[3]).toAbsolutePath().normalize();
            Path root = Path.of("../datasets/controversy/experiments").toRealPath();
            check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root), "New private plan directory required");
            try { Files.createDirectory(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
            catch (UnsupportedOperationException e) { Files.createDirectory(output); }
            byte[] bytes = JSON.writeValueAsBytes(plan);
            writePrivate(output.resolve("plan.json"), bytes);
            System.out.println("SOURCE_RELATION_PREPARED calls=15 model=" + plan.model() + " outputCap=" + plan.maxCompletionTokens()
                    + " reservedEstimatedUsd=" + plan.estimatedCapUsd() + " providerCalls=0 planSha256=" + ReferenceOnlyTrial.sha(bytes));
            return;
        }
        if ("audit".equals(args[0])) {
            check(args.length == 4, "audit frozen plan, saved results, and new private audit output required");
            byte[] planBytes = OfflineDecisionContractAudit.bounded(Path.of(args[1]));
            byte[] resultBytes = OfflineDecisionContractAudit.bounded(Path.of(args[2]));
            check(ReferenceOnlyTrial.sha(planBytes).equals(JSON.readTree(resultBytes).path("planSha256").asText()),
                    "Saved response does not belong to frozen plan");
            Plan plan = JSON.readValue(planBytes, Plan.class);
            check("source-relation-extraction-1".equals(plan.schemaVersion()) && plan.requests().size() == 15,
                    "Historical v1 plan required for this read-only audit");
            JsonNode saved = JSON.readTree(resultBytes);
            Map<String, Object> report = audit(plan, saved);
            Path auditOutput = Path.of(args[3]).toAbsolutePath().normalize();
            Path root = Path.of("../datasets/controversy/experiments").toRealPath();
            check(!Files.exists(auditOutput) && auditOutput.getParent().toRealPath().startsWith(root),
                    "New private audit output required");
            writePrivate(auditOutput, JSON.writeValueAsBytes(report));
            System.out.println("SOURCE_RELATION_OFFLINE_AUDIT calls=15 structurallyAccepted=" + report.get("structurallyAccepted")
                    + " exactMatches=" + report.get("exactExpectedRelationMatches")
                    + " rawProviderResponsePreserved=" + report.get("rawProviderResponsePreserved"));
            return;
        }
        check("run".equals(args[0]) && args.length == 5, "run frozen plan, approved SHA, exact consent, and new private output required");
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        check(CONSENT.equals(args[3]) && ReferenceOnlyTrial.sha(bytes).equals(args[2]), "Fresh approval/hash required");
        Plan plan = JSON.readValue(bytes, Plan.class); validate(plan);
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        check(!Files.exists(output), "New private output directory required; runs cannot be resumed");
        run(plan, bytes, output);
    }
}
