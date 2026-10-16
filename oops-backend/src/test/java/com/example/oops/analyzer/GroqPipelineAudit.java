package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.TimelineEventType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Selected paired transcripts and synthetic drafts; no media, application DB, or gold promotion. */
public final class GroqPipelineAudit {
    record Case(String id, String labelStatus, String source, List<Long> requestedWindowMs,
                List<ReviewInput.Segment> raw) {}
    static final double RESERVATION_CAP = .50;
    static final class Budget {
        final Path path; int attempts; double reserved;
        Budget(Path path) { this.path = path; }
        void reserve(int bytes) throws java.io.IOException {
            double next = (bytes + 1024) * .10 / 1_000_000 + 8000 * .50 / 1_000_000;
            check(attempts < 80 && reserved + next <= RESERVATION_CAP, "Diagnostic reservation cap reached");
            reserved += next; attempts++;
            Files.writeString(path, JSON.writeValueAsString(Map.of("httpAttempts", attempts,
                    "reservedEstimatedUsd", reserved, "selfImposedEstimatedCapUsd", RESERVATION_CAP,
                    "actualBillingConfirmed", false)));
        }
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 4 && Set.of("inspect", "run").contains(args[0]), "inspect/run input runtime NEW-output required");
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        check(output.getParent().startsWith(Path.of("../datasets/controversy/experiments").toRealPath())
                && !Files.exists(output), "New private output only");
        byte[] inputBytes = OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        byte[] runtimeBytes = OfflineDecisionContractAudit.bounded(Path.of(args[2]));
        var root = JSON.readTree(inputBytes);
        check(root.path("schemaVersion").asText().equals("groq-pipeline-audit-input-1"), "Known input required");
        var cases = new ArrayList<Case>();
        for (var node : root.path("cases")) {
            var c = JSON.readValue(node.toString(), Case.class);
            check(c.id() != null && c.labelStatus() != null && !c.raw().isEmpty() && c.raw().size() <= 80
                    && c.raw().stream().allMatch(s -> s.type() == TimelineEventType.SPEECH), "Bounded speech cases required");
            new ReviewInput(c.raw()); cases.add(c);
        }
        check(cases.size() == 16 && cases.stream().map(Case::id).distinct().count() == 16, "Sixteen distinct paired/draft cases required");
        var library = ContextRankReadinessAudit.library(new String(runtimeBytes, java.nio.charset.StandardCharsets.UTF_8));
        var report = new LinkedHashMap<String,Object>();
        report.put("inputSha256", ReferenceOnlyTrial.sha(inputBytes));
        report.put("runtimeSha256", ReferenceOnlyTrial.sha(runtimeBytes));
        report.put("scope", "SELECTED_EXCERPTS_NOT_FULL_VIDEO_OCR_VISUAL_FUSION_OR_DB");
        report.put("pipelineRevision", CandidateReviewEngine.REVISION);
        report.put("routerEnabled", false); report.put("sourceRelationsEnabled", false);
        report.put("normalDraftsHumanApproved", false); report.put("repeats", 1);
        var rows = new ArrayList<Map<String,Object>>(); report.put("results", rows);
        if (args[0].equals("inspect")) {
            for (var c : cases) {
                var selections = new ArrayList<Map<String,Object>>();
                for (var batch : TextReviewBatchPlanner.plan(new ReviewInput(c.raw()), TimelineEventType.SPEECH, 3)) {
                    var window = new ArrayList<>(batch.primary()); window.addAll(batch.context());
                    var selection = library.select(TimelineEventType.SPEECH, window, "discovery-" + (selections.size() + 1));
                    selections.add(Map.of("trace", selection.trace(), "referencePrompt", selection.prompt()));
                }
                rows.add(Map.of("id", c.id(), "labelStatus", c.labelStatus(), "selections", selections));
            }
            report.put("providerCalls", 0); report.put("state", "COMPLETE");
            Files.writeString(output, JSON.writeValueAsString(report), StandardOpenOption.CREATE_NEW);
            System.out.println("GROQ_RETRIEVAL_AUDIT cases=" + rows.size() + " providerCalls=0"); return;
        }
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "OPENAI_API_KEY required; never print");
        var budget = new Budget(output.resolveSibling("text-reservation.json"));
        var props = new OpenAiProperties(key, System.getenv("OPENAI_ORG_ID"), System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1", "gpt-6-luna", "none", Duration.ofSeconds(90),
                new OpenAiProperties.Pricing(.10, .01, .50, null, null));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(90));
        var rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    byte[] bounded = V7RoutingVideoTrial.guardBody(body, request.getURI().toString());
                    budget.reserve(bounded.length); request.getHeaders().setContentLength(bounded.length);
                    return execution.execute(request, bounded);
                });
        if (props.hasOrganization()) rest.defaultHeader("OpenAI-Organization", props.organization());
        if (props.hasProject()) rest.defaultHeader("OpenAI-Project", props.project());
        var client = new MechanismRoutingTrial.Client(rest.build(), props);
        double cost = 0;
        report.put("state", "RUNNING"); Files.writeString(output, JSON.writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        try {
            for (var c : cases) {
                client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("groq-paired-text-audit");
                var condition = new MechanismRoutingTrial.Condition(c.id(), c.labelStatus(), c.raw());
                var result = CandidateReviewEngine.run(client, MechanismRoutingTrial.context(condition), 24,
                        null, null, library, null, 0, false, null);
                var usage = client.videoUsage(); cost += usage.costUsd();
                var row = new LinkedHashMap<String,Object>(); row.put("id", c.id()); row.put("labelStatus", c.labelStatus());
                row.put("status", result.status()); row.put("diagnostics", result.diagnostics()); row.put("evaluations", result.evaluations());
                row.put("unassessedSegmentIds", result.unassessedSegmentIds()); row.put("calls", List.copyOf(client.calls)); row.put("usage", usage);
                row.put("findings", result.findings().stream().map(f -> Map.of("category", f.getCategory(), "text", f.primaryText(),
                        "reason", f.getReason(), "target", f.getTarget() == null ? "" : f.getTarget(), "supports", f.validatedSupports())).toList());
                rows.add(row); report.put("usageEstimatedUsd", cost); report.put("httpAttempts", budget.attempts);
                report.put("reservedEstimatedUsd", budget.reserved); report.put("actualBillingConfirmed", false);
                Files.writeString(output, JSON.writeValueAsString(report));
                System.out.println("GROQ_TEXT_AUDIT " + rows.size() + "/16 " + c.id() + " findings=" + result.findings().size()
                        + " status=" + result.status() + " calls=" + usage.calls());
                boolean ok = client.failureCount() == 0 && usage.calls() == client.calls.size();
                client.endVideo(); check(ok, "Stop on request/usage failure; no automatic rerun");
            }
            report.put("state", "COMPLETE");
        } catch (Exception ex) {
            report.put("state", "STOPPED"); throw ex;
        } finally {
            Files.writeString(output, JSON.writeValueAsString(report));
        }
    }
}
