package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ConfrontationStageTrial.*;
import static com.example.oops.analyzer.RecallFirstDiscoveryTrial.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/**
 * Opt-in BASE/GENERIC_POLICY comparison: the treatment removes region·store·menu clauses from the shared policy,
 * discovery, and verification prompts inside the trial client. Case-level references stay. Production is unchanged.
 * Development labels and synthetic controls only, not independent accuracy.
 */
public final class GenericPolicyTrial {
    static final String CONSENT = "GENERIC_POLICY_22X2_100_USD165";
    static final int LIMIT = 100, PER_RUN = 8;
    static final double BUDGET = 1.65;
    static final String BASE = "BASE", GENERIC = "GENERIC_POLICY";
    static final String SCOPE = "DEVELOPMENT_LABELS_AND_SYNTHETIC_CONTROLS_22_CASES_V1_UNITS_ALSO_RUNTIME_REFERENCES";

    /** Present once in every prompt that starts with the shared policy. */
    static final Map<String, String> POLICY_EDITS = edits(
            " 음식 평가를 주민 전체 공격으로 확대하지 않는다.", "",
            "대상에는 주민뿐 아니라 지역·장소·그곳의 생활과 선택지도 포함된다. 주민 전체 일반화는 조롱의 필수 조건이 아니다.\n", "",
            "가게·서비스·메뉴·상품도 평가 대상이다. 그 대상의 모욕·조롱 여부를 확인할 때 지역·주민 비하까지 요구하지 않는다.\n"
                    + "  맛·가격·특색·판매 조건에 대한 불만과 대상의 가치·선택을 하찮게 취급하는 연결을 원문으로 구별한다.\n", "",
            "시설·매장·메뉴가 없다는 정보나 개인의 불편만으로 경고하지 않는다.\n"
                    + "  그 부족함을 지역·장소·생활의 열등함이나 웃음거리로 연결하는 실제 비교·비유·반문·평가가 있는지 확인한다.\n", "",
            "반복·놀람·대체 행동 자체도 근거가 아니다. 무엇을 어떻게 낮추는지 원문으로 설명하고,\n"
                    + "  지역 정보/개인 불편/지역 가치 절하/주민 폄하를 혼동하지 않는다.\n",
            "반복·놀람 자체도 근거가 아니다. 무엇을 어떻게 낮추는지 원문으로 설명한다.\n");
    /** Present at most once: discovery and verification bodies only. */
    static final Map<String, String> STAGE_EDITS = edits(
            "  매장 부재 등 상황 설명만 대표로 고르지 말고 조롱·가치 절하를 구성하는 표현을 찾는다.\n",
            "  상황 설명만 대표로 고르지 말고 조롱·가치 절하를 구성하는 표현을 찾는다.\n",
            " 메뉴 불만·판매 조건 안내 자체는 후보 근거가 아니다.", "",
            "대상 취급 후보에서는 매장·시설 부재가 정보 전달/개인 불편인지, 지역·장소·생활을 낮추는 연결인지 대조한다.\n"
                    + "  주민 전체 일반화가 없다는 이유만으로 지역 가치 절하·특정 대상 조롱 후보를 PASS 처리하지 않는다.\n"
                    + "  반대로 매장이 없거나 대체 음식을 먹는다는 이유만으로 비하를 만들지 않는다.\n"
                    + "먼저 실제 평가 대상이 지역인지 가게·서비스·메뉴·상품인지 구분한다. 메뉴·서비스에 대한 후보를\n"
                    + "  지역 비하가 없다는 이유만으로 PASS 처리하지 않는다. 단순 불만·선택 조건 안내와 모욕적 가치 절하를 대조한다.\n", "");

    record Run(String caseId, String mode, Frozen initial) {}
    record Plan(String inputsPath, String inputsSha, String labelsPath, String labelsSha,
                String reviewPath, String reviewSha, String newReviewPath, String newReviewSha,
                Map<String, String> unitShas, String runtimePath, String runtimeSha,
                Map<String, String> implementation, List<Condition> cases, List<Run> runs, double maxReservation) {}

    private static Map<String, String> edits(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) n++;
        return n;
    }

    static String rewrite(String system) {
        String out = system;
        for (var e : POLICY_EDITS.entrySet()) {
            check(count(out, e.getKey()) == 1, "Shared policy clause must appear exactly once");
            out = out.replace(e.getKey(), e.getValue());
        }
        for (var e : STAGE_EDITS.entrySet()) {
            check(count(out, e.getKey()) <= 1, "Stage clause must appear at most once");
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private static Map<String, ReviewInput.Segment> segments(tools.jackson.databind.JsonNode rows) {
        var map = new LinkedHashMap<String, ReviewInput.Segment>();
        for (var s : rows) map.put(s.path("id").asText(), new ReviewInput.Segment(s.path("id").asText(),
                TimelineEventType.SPEECH, s.path("startMs").asLong(), s.path("endMs").asLong(), s.path("text").asText(), null));
        return map;
    }

    private static int index(String id) {
        check(id.matches("stt-index-\\d{1,4}"), "Stored STT id required");
        return Integer.parseInt(id.substring("stt-index-".length()));
    }

    /** Contiguous stored STT span covering the approved unit: the full origin page when it has it, else the snapshot. */
    private static List<ReviewInput.Segment> span(List<String> ids, Map<String, ReviewInput.Segment> full,
                                                  Map<String, ReviewInput.Segment> snapshot) {
        var pool = full.keySet().containsAll(ids) ? full : snapshot;
        int lo = ids.stream().mapToInt(GenericPolicyTrial::index).min().orElseThrow();
        int hi = ids.stream().mapToInt(GenericPolicyTrial::index).max().orElseThrow();
        var raw = new ArrayList<ReviewInput.Segment>();
        for (int i = lo; i <= hi; i++) {
            check(pool.containsKey("stt-index-" + i), "Contiguous stored span required");
            raw.add(pool.get("stt-index-" + i));
        }
        return List.copyOf(raw);
    }

    static List<Condition> v1Cases(Path dataset) throws Exception {
        var source = JSON.readTree(Files.readAllBytes(dataset.resolve("context-sources/pisik-v1-stt-20261015.json")));
        byte[] originRaw = Files.readAllBytes(dataset.resolve(source.path("origin").path("path").asText()));
        check(ReferenceOnlyTrial.sha(originRaw).equals(source.path("origin").path("sha256").asText()), "Snapshot origin sha required");
        var snapshot = segments(source.path("segments"));
        // The origin plan mixes several videos; a v1 page is one whose overlap with the snapshot matches it exactly.
        var full = new LinkedHashMap<String, ReviewInput.Segment>();
        for (var task : JSON.readTree(originRaw).path("tasks")) {
            var page = segments(JSON.readTree(task.path("input").asText()).path("raw"));
            var shared = page.keySet().stream().filter(snapshot::containsKey).toList();
            if (shared.isEmpty() || !shared.stream().allMatch(id -> page.get(id).equals(snapshot.get(id)))) continue;
            for (var e : page.entrySet()) {
                var prior = full.putIfAbsent(e.getKey(), e.getValue());
                check(prior == null || prior.equals(e.getValue()), "v1 origin pages must agree on stored STT");
            }
        }
        check(full.keySet().containsAll(snapshot.keySet()), "Every snapshot segment must come from a v1 origin page");
        var positives = new ArrayList<Condition>(); var controls = new ArrayList<Condition>();
        for (String name : List.of("region", "menu", "life-choice")) {
            var unit = JSON.readTree(Files.readAllBytes(dataset.resolve("context-units/pisik-" + name + ".json")));
            check(unit.path("familyId").asText().equals("pisik-yeongyang-20240511"), "v1 unit required");
            if (!name.equals("life-choice")) {
                var ids = new ArrayList<String>();
                unit.path("utteranceIds").forEach(id -> ids.add(id.asText()));
                var raw = span(ids, full, snapshot);
                var anchors = new ArrayList<String>();
                unit.path("roles").path("evaluationIds").forEach(a -> anchors.add(a.asText()));
                check(!anchors.isEmpty(), "Evaluation anchor required");
                positives.add(new Condition("v1-" + name, "USER_APPROVED_DEVELOPMENT_BOUNDARY_DRAFT", "REVIEW_REQUIRED",
                        raw, new Seed(anchors.get(0), "TARGET_TREATMENT", "diagnostic only", List.copyOf(anchors))));
            }
            int n = 0;
            for (var control : unit.path("normalContrasts")) {
                check(control.path("kind").asText().equals("SYNTHETIC_DEVELOPMENT_CONTROL"), "Synthetic control required");
                var sentences = control.path("text").asText().split("(?<=[.?!])\\s+");
                var raw = new ArrayList<ReviewInput.Segment>();
                for (int i = 0; i < sentences.length; i++) raw.add(new ReviewInput.Segment("s" + i, TimelineEventType.SPEECH,
                        i * 1000L, (i + 1) * 1000L, sentences[i], null));
                controls.add(new Condition("v1-" + name + "-control-" + n++, "SYNTHETIC_DEVELOPMENT_CONTROL", "PASS",
                        List.copyOf(raw), new Seed("s0", "TARGET_TREATMENT", "diagnostic only", List.of())));
            }
        }
        var cases = new ArrayList<>(positives); cases.addAll(controls);
        return List.copyOf(cases);
    }

    static Plan prepare(Path inputs, Path labels, Path review, Path newReview, Path dataset) throws Exception {
        var fixed = FixedBoundaryBaselineTrial.prepare(inputs, labels);
        byte[] reviewRaw = OfflineDecisionContractAudit.bounded(review), newReviewRaw = OfflineDecisionContractAudit.bounded(newReview);
        var cases = new ArrayList<>(fixed.cases());
        cases.add(reviewed142(reviewRaw));
        cases.addAll(PrivacyScopeTrial.reviewed(newReviewRaw));
        cases.addAll(v1Cases(dataset));
        var unitShas = new TreeMap<String, String>();
        for (String file : List.of("context-sources/pisik-v1-stt-20261015.json", "experiments/banter-policy-plan-41-20261015/plan.json",
                "context-units/pisik-region.json",
                "context-units/pisik-menu.json", "context-units/pisik-life-choice.json"))
            unitShas.put(file, ReferenceOnlyTrial.sha(Files.readAllBytes(dataset.resolve(file))));
        var library = library(Path.of(fixed.runtimePath()));
        var runs = new ArrayList<Run>();
        for (var c : cases) for (String mode : List.of(BASE, GENERIC)) {
            var client = new TrialClient(RestClient.create(), c, mode, true, null, null, "offline-no-key", rewriteFor(mode));
            pipeline(client, c, library, false);
            check(client.captured.size() == 1, "One discovery page per condition required: " + c.id() + " " + mode + "=" + client.captured.size());
            runs.add(new Run(c.id(), mode, client.captured.get(0)));
        }
        var implementation = new TreeMap<>(fixed.implementation());
        for (String file : List.of("RecallFirstDiscoveryTrial.java", "PrivacyScopeTrial.java", "GenericPolicyTrial.java")) {
            var path = Path.of("src/test/java/com/example/oops/analyzer").resolve(file);
            implementation.put(path.toString(), ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
        }
        double worst = LIMIT * reservation(MAX_BYTES);
        check(worst <= BUDGET, "Reservation exceeds budget");
        return new Plan(fixed.inputsPath(), fixed.inputsSha(), fixed.labelsPath(), fixed.labelsSha(),
                review.toRealPath().toString(), ReferenceOnlyTrial.sha(reviewRaw),
                newReview.toRealPath().toString(), ReferenceOnlyTrial.sha(newReviewRaw), unitShas,
                fixed.runtimePath(), fixed.runtimeSha(), implementation, List.copyOf(cases), List.copyOf(runs), worst);
    }

    static java.util.function.UnaryOperator<String> rewriteFor(String mode) {
        return mode.equals(GENERIC) ? GenericPolicyTrial::rewrite : s -> s;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path dataset = Path.of("../datasets/controversy");
        check((args.length == 6 && args[0].equals("prepare")) || (args.length == 4 && args[0].equals("run")), "Invalid mode");
        Path out = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(root), "New private output required");
        if (args[0].equals("prepare")) {
            var plan = prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), dataset);
            byte[] bytes = JSON.writeValueAsBytes(plan);
            Files.createDirectory(out); Files.write(out.resolve("plan.json"), bytes, StandardOpenOption.CREATE_NEW);
            System.out.println("GENERIC_POLICY_PREPARED providerCalls=0 runs=" + plan.runs().size() + " maxHttp=" + LIMIT
                    + " maxReservation=" + plan.maxReservation() + " sha=" + ReferenceOnlyTrial.sha(bytes));
            return;
        }
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[1])); String sha = ReferenceOnlyTrial.sha(bytes);
        check(sha.equals(args[2]) && CONSENT.equals(System.getenv("GENERIC_POLICY_CONSENT")), "Fresh exact-plan consent required");
        var plan = JSON.readValue(bytes, Plan.class);
        var fresh = prepare(Path.of(plan.inputsPath()), Path.of(plan.labelsPath()), Path.of(plan.reviewPath()),
                Path.of(plan.newReviewPath()), dataset);
        check(JSON.valueToTree(plan).equals(JSON.valueToTree(fresh)), "Source, label, runtime, or code drift");
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]), out, sha, CONSENT, LIMIT);
        Files.createDirectory(out);
        var library = library(Path.of(plan.runtimePath()));
        var steps = plan.runs().stream().map(run -> new Step(caseOf(plan.cases(), run.caseId()), run.mode(), false,
                library, run.initial(), rewriteFor(run.mode()))).toList();
        execute(out, sha, SCOPE, steps, new TrialGuard(LIMIT, PER_RUN, BUDGET), key);
    }
}
