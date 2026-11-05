package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.node.ObjectNode;
import static com.example.oops.analyzer.ConfrontationStageTrial.*;
import static com.example.oops.analyzer.RecallFirstDiscoveryTrial.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/**
 * Opt-in BASE vs privacy treatment: PRIVACY_SCOPE widens the privacy-exposure definition in a runtime copy;
 * PRIVACY_AXES also widens the shared axis clause inside the trial client. Production prompts and runtime are unchanged.
 * prepare makes no provider call; run needs the exact plan hash and consent. Development labels, not independent accuracy.
 */
public final class PrivacyScopeTrial {
    static final String CONSENT = "PRIVACY_SCOPE_16X2_80_USD130";
    static final int LIMIT = 80, PER_RUN = 8;
    static final double BUDGET = 1.30;
    static final String BASE = "BASE", SCOPED = "PRIVACY_SCOPE", AXES_MODE = "PRIVACY_AXES";
    static final String AXES_FROM = "사적 개인정보 노출 등 검토 이유가 있는가?";
    static final String AXES_TO = "사적 개인정보 노출, 동의 없는 실존 제3자의 사생활(연애·건강·가족사) 폭로 등 검토 이유가 있는가?";
    static final String SCOPE = "DEVELOPMENT_LABELS_16_CASES_NOT_INDEPENDENT_142_WINDOWS_SHARE_ONE_VIDEO";
    static final String PRIVACY_ID = "privacy-exposure";

    static final String CURRENT_CONDITION = "동의 없이 특정인의 신상·연락처·거주지·얼굴 등 비공개 정보를 노출하거나 공개를 선동하는 실제 표현·행위를 검토한다. "
            + "적법한 신상공개 절차를 거치지 않은 자의적 공개(사적제재)를 포함한다.";
    static final String CURRENT_CONTRAST = "공적 인물의 이미 공개된 정보 인용, 수사기관·법원의 적법 절차에 따른 공개와 구별한다.";
    static final String CURRENT_EVIDENCE = "노출된 정보의 종류, 대상, 동의 없음 또는 적법 절차 부재를 함께 인용한다.";
    static final String CONDITION = "동의 없이 특정할 수 있는 실존 인물의 신상·연락처·거주지·얼굴 등 비공개 정보나 연애·건강·가족사 등 사생활 주장을 "
            + "노출·폭로하거나 공개를 선동하는 실제 표현·행위를 검토한다. 적법한 신상공개 절차를 거치지 않은 자의적 공개(사적제재)를 포함한다.";
    static final String CONTRAST = "공적 인물의 이미 공개된 정보 인용, 수사기관·법원의 적법 절차에 따른 공개, 당사자 본인의 자기 공개, "
            + "특정할 수 없는 익명 일화와 구별한다.";
    static final String EVIDENCE = "노출·주장된 사적 정보의 종류, 특정 가능한 대상, 동의 없음 또는 적법 절차 부재를 함께 인용한다.";

    record Run(String caseId, String mode, Frozen initial) {}
    record Plan(String treatment, String inputsPath, String inputsSha, String labelsPath, String labelsSha,
                String reviewPath, String reviewSha, String newReviewPath, String newReviewSha,
                String runtimePath, String runtimeSha, String variantRuntimePath, String variantRuntimeSha,
                Map<String, String> implementation, List<Condition> cases, List<Run> runs, double maxReservation) {}

    static byte[] expand(byte[] runtime) {
        var root = (ObjectNode) JSON.readTree(runtime);
        ObjectNode privacy = null;
        for (var g : root.path("guidelines")) if (g.path("id").asText().equals(PRIVACY_ID)) privacy = (ObjectNode) g;
        check(privacy != null && privacy.path("condition").asText().equals(CURRENT_CONDITION)
                && privacy.path("normalContrast").asText().equals(CURRENT_CONTRAST)
                && privacy.path("requiredEvidence").asText().equals(CURRENT_EVIDENCE), "Current privacy definition required");
        privacy.put("condition", CONDITION).put("normalContrast", CONTRAST).put("requiredEvidence", EVIDENCE);
        return JSON.writeValueAsBytes(root);
    }

    /** Trial-only axis wording; every system prompt in this pipeline carries the shared axis clause exactly once. */
    static String rewriteAxes(String system) {
        int at = system.indexOf(AXES_FROM);
        check(at >= 0 && at == system.lastIndexOf(AXES_FROM), "Exactly one axis clause required");
        return system.substring(0, at) + AXES_TO + system.substring(at + AXES_FROM.length());
    }

    static java.util.function.UnaryOperator<String> rewrite(String mode) {
        return mode.equals(AXES_MODE) ? PrivacyScopeTrial::rewriteAxes : s -> s;
    }

    static List<Condition> reviewed(byte[] raw) {
        var review = JSON.readTree(raw);
        check(review.path("status").asText().equals("USER_REVIEWED_DEVELOPMENT_ONLY"), "User-reviewed development record required");
        var cases = new ArrayList<Condition>();
        for (var item : review.path("reviews")) {
            String decision = item.path("decision").asText();
            check(item.path("decisionSource").asText().equals("USER") && Set.of("PASS", "REVIEW_REQUIRED").contains(decision),
                    "User decision required");
            var segments = new ArrayList<ReviewInput.Segment>();
            for (var s : item.path("transcriptSegments")) segments.add(new ReviewInput.Segment(s.path("id").asText(),
                    TimelineEventType.SPEECH, s.path("startMs").asLong(), s.path("endMs").asLong(), s.path("text").asText(), null));
            var anchors = new ArrayList<String>();
            item.path("anchorSegmentIds").forEach(a -> anchors.add(a.asText()));
            var ids = segments.stream().map(ReviewInput.Segment::id).toList();
            check(!segments.isEmpty() && segments.size() <= 64 && ids.containsAll(anchors)
                    && decision.equals("REVIEW_REQUIRED") == !anchors.isEmpty(), "Bounded window with reviewed anchors required");
            String anchor = anchors.isEmpty() ? ids.get(0) : anchors.get(0);
            cases.add(new Condition(item.path("id").asText(), "USER_REVIEWED_WINDOW_DEVELOPMENT_ONLY", decision,
                    List.copyOf(segments), new Seed(anchor, "TARGET_TREATMENT", "diagnostic only", List.copyOf(anchors))));
        }
        return List.copyOf(cases);
    }

    static Plan prepare(Path inputs, Path labels, Path review, Path newReview, Path variantRuntime, String treatment) throws Exception {
        check(Set.of(SCOPED, AXES_MODE).contains(treatment), "Known treatment required");
        var fixed = FixedBoundaryBaselineTrial.prepare(inputs, labels);
        byte[] reviewRaw = OfflineDecisionContractAudit.bounded(review), newReviewRaw = OfflineDecisionContractAudit.bounded(newReview);
        Path runtime = Path.of(fixed.runtimePath());
        byte[] variantRaw = Files.readAllBytes(variantRuntime);
        check(JSON.readTree(variantRaw).equals(JSON.readTree(expand(Files.readAllBytes(runtime)))), "Variant must be the planned expansion");
        var cases = new ArrayList<>(fixed.cases());
        cases.add(reviewed142(reviewRaw));
        cases.addAll(reviewed(newReviewRaw));
        var libraries = Map.of(BASE, library(runtime), treatment, library(variantRuntime));
        var runs = new ArrayList<Run>();
        for (var c : cases) for (String mode : List.of(BASE, treatment)) {
            var client = new TrialClient(RestClient.create(), c, mode, true, null, null, "offline-no-key", rewrite(mode));
            pipeline(client, c, libraries.get(mode), false);
            check(client.captured.size() == 1, "One discovery page per condition required");
            runs.add(new Run(c.id(), mode, client.captured.get(0)));
        }
        var implementation = new TreeMap<>(fixed.implementation());
        for (String file : List.of("RecallFirstDiscoveryTrial.java", "PrivacyScopeTrial.java")) {
            var path = Path.of("src/test/java/com/example/oops/analyzer").resolve(file);
            implementation.put(path.toString(), ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
        }
        double worst = LIMIT * reservation(MAX_BYTES);
        check(worst <= BUDGET, "Reservation exceeds budget");
        return new Plan(treatment, fixed.inputsPath(), fixed.inputsSha(), fixed.labelsPath(), fixed.labelsSha(),
                review.toRealPath().toString(), ReferenceOnlyTrial.sha(reviewRaw),
                newReview.toRealPath().toString(), ReferenceOnlyTrial.sha(newReviewRaw),
                fixed.runtimePath(), fixed.runtimeSha(), variantRuntime.toRealPath().toString(), ReferenceOnlyTrial.sha(variantRaw),
                implementation, List.copyOf(cases), List.copyOf(runs), worst);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        check((args.length == 8 && args[0].equals("prepare")) || (args.length == 4 && args[0].equals("run")), "Invalid mode");
        Path out = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(root), "New private output required");
        if (args[0].equals("prepare")) {
            Files.createDirectory(out);
            Path variant = out.resolve("runtime-privacy-scope.json");
            Files.write(variant, expand(Files.readAllBytes(Path.of(args[5]))), StandardOpenOption.CREATE_NEW);
            var plan = prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), variant, args[6]);
            check(Path.of(plan.runtimePath()).toRealPath().equals(Path.of(args[5]).toRealPath()), "Expanded runtime must be the baseline runtime");
            byte[] bytes = JSON.writeValueAsBytes(plan);
            Files.write(out.resolve("plan.json"), bytes, StandardOpenOption.CREATE_NEW);
            System.out.println("PRIVACY_SCOPE_PREPARED providerCalls=0 runs=" + plan.runs().size() + " maxHttp=" + LIMIT
                    + " maxReservation=" + plan.maxReservation() + " sha=" + ReferenceOnlyTrial.sha(bytes));
            return;
        }
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[1])); String sha = ReferenceOnlyTrial.sha(bytes);
        check(sha.equals(args[2]) && CONSENT.equals(System.getenv("PRIVACY_SCOPE_CONSENT")), "Fresh exact-plan consent required");
        var plan = JSON.readValue(bytes, Plan.class);
        var fresh = prepare(Path.of(plan.inputsPath()), Path.of(plan.labelsPath()), Path.of(plan.reviewPath()),
                Path.of(plan.newReviewPath()), Path.of(plan.variantRuntimePath()), plan.treatment());
        check(JSON.valueToTree(plan).equals(JSON.valueToTree(fresh)), "Source, label, runtime, or code drift");
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]), out, sha, CONSENT, LIMIT);
        Files.createDirectory(out);
        var libraries = Map.of(BASE, library(Path.of(plan.runtimePath())), plan.treatment(), library(Path.of(plan.variantRuntimePath())));
        var steps = plan.runs().stream().map(run -> new Step(caseOf(plan.cases(), run.caseId()), run.mode(), false,
                libraries.get(run.mode()), run.initial(), rewrite(run.mode()))).toList();
        execute(out, sha, SCOPE, steps, new TrialGuard(LIMIT, PER_RUN, BUDGET), key);
    }
}
