package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Reuses the guarded runner; prepares a system-only comparison without any provider calls. */
public final class IssueScopeComparisonPlan {
    static final String VERSION = "candidate-issue-scope-comparison-1";
    static final String SOURCE_SHA = "985b0ea1133a136619c614bc7a8b63b45e5e5dd542f5212fe7d04c92feec46df";
    static final String CONSENT = "NEW_USD050_ISSUE_SCOPE_COMPARISON_TEXT_APPROVED";
    static final String OLD = """
            대상 취급은 '상황 → 평가 → 평가 대상'의 연결로 읽는다. 시설 부재·자신의 대체 선택·불만은 정상 해석이다.
              반면 그 상황을 빌미로 타인의 생활·선택지를 열등하거나 우스운 것으로 취급하는 실제 비교·비유·평가는 검토한다.
              직접 욕설은 필요하지 않지만, 타인의 동기·억양·열등함을 상상해서 연결을 만들지 않는다.
            """;
    static final String ADDED = """
            허용 raw 전체에서 anchor의 실제 발언·행위를 확인한다. 뜻풀이·인용 속 질문을 실제 질문으로,
              자기 설명·자기 제안을 상대 요구로 바꾸지 않는다. 주변의 별도 쟁점만으로 현재 후보를 경고하지 않는다.
              reason에는 anchor와 문제의 연결 근거를 적는다. 뜻풀이·인용·자기 제안도 자동 PASS는 아니다.
            """;
    static final String INSERT_BEFORE = "각 candidateId에 정확히 하나의 assessment를 반환한다.";
    private IssueScopeComparisonPlan() {}

    static String upgrade(String before) {
        int boundary = before.indexOf("# 대상 필드 형식 예시");
        check(boundary > 0, "Known verification body required");
        String prefix = before.substring(0, boundary), suffix = before.substring(boundary);
        check(prefix.indexOf(OLD) >= 0 && prefix.indexOf(OLD) == prefix.lastIndexOf(OLD)
                && !prefix.contains(ADDED) && prefix.indexOf(INSERT_BEFORE) == prefix.lastIndexOf(INSERT_BEFORE),
                "Exact pre-change guidance required");
        return prefix.replace(OLD, "").replace(INSERT_BEFORE, ADDED + INSERT_BEFORE) + suffix;
    }

    static RelationGuidedVerificationTrial.Plan build(JsonNode source) {
        var requests = new ArrayList<DefaultVerificationPlan.Request>();
        for (JsonNode n : source.path("requests")) if ("WITHOUT".equals(n.path("arm").asText())) {
            var r = JSON.treeToValue(n, DefaultVerificationPlan.Request.class);
            String after = upgrade(r.system());
            check(after.equals(CandidateReviewEngine.verificationPrompt(0) + "\n" + DefaultVerificationPlan.reference(r.system())),
                    "Unexpected current prompt drift");
            for (String arm : r.repeat() % 2 == 1 ? List.of("BEFORE", "AFTER") : List.of("AFTER", "BEFORE"))
                requests.add(new DefaultVerificationPlan.Request(r.caseId(), r.labelStatus(), r.expectedDecision(),
                        arm, r.repeat(), arm.equals("BEFORE") ? r.system() : after, r.input()));
        }
        var bodies = requests.stream().map(RelationGuidedVerificationTrial::body).toList();
        var plan = new RelationGuidedVerificationTrial.Plan(VERSION, "gpt-6-luna", CandidateReviewEngine.REVISION,
                SOURCE_SHA, "NOT_USED_NO_RELATION_RECORDS", 30, .50,
                RelationGuidedVerificationTrial.reservation(bodies), "SYSTEM_GUIDANCE_ONLY_REMOVED_REDUNDANCY_AND_ADDED_ISSUE_SCOPE",
                "NONE_BOTH_ARMS_WITHOUT_SOURCE_READING", false, Map.of(), List.copyOf(requests), bodies);
        validate(plan); return plan;
    }

    static void validate(RelationGuidedVerificationTrial.Plan p) {
        check(VERSION.equals(p.schemaVersion()) && SOURCE_SHA.equals(p.baselineSha256())
                && "NOT_USED_NO_RELATION_RECORDS".equals(p.extractionSha256())
                && CandidateReviewEngine.REVISION.equals(p.engineRevision()) && "gpt-6-luna".equals(p.model())
                && p.maxHttpAttempts() == 30 && p.proposedEstimatedCapUsd() == .50 && !p.independentGold()
                && p.fixedReadings().isEmpty() && p.requests().size() == 30 && p.requestBodies().size() == 30,
                "Known issue-scope plan required");
        check(Math.abs(p.reservationEstimatedUsd() - RelationGuidedVerificationTrial.reservation(p.requestBodies())) < 1e-12
                && p.reservationEstimatedUsd() <= .50, "Frozen request budget changed");
        var seen = new HashSet<String>();
        for (int i = 0; i < 30; i += 2) {
            var a = p.requests().get(i); var b = p.requests().get(i + 1);
            check(Set.of(a.arm(), b.arm()).equals(Set.of("BEFORE", "AFTER")), "Two system arms required");
            var before = a.arm().equals("BEFORE") ? a : b; var after = a.arm().equals("AFTER") ? a : b;
            check(before.caseId().equals(after.caseId()) && before.repeat() == after.repeat()
                    && before.repeat() >= 1 && before.repeat() <= 3 && RelationGuidedVerificationTrial.IDS.contains(before.caseId())
                    && seen.add(before.caseId() + ":" + before.repeat()), "Five cases repeated three times required");
            check(before.input().equals(after.input()) && before.expectedDecision().equals(after.expectedDecision())
                    && before.labelStatus().equals(after.labelStatus()) && upgrade(before.system()).equals(after.system()),
                    "Only the system guidance may change");
            check(after.system().equals(CandidateReviewEngine.verificationPrompt(0) + "\n" + DefaultVerificationPlan.reference(before.system())),
                    "Current system drift");
            check(!JSON.readTree(before.input()).has(RelationGuidedVerificationTrial.FIELD), "Source reading must remain absent");
            check((Set.of("R04", "R08").contains(before.caseId()) ? "REVIEW_REQUIRED" : "PASS")
                    .equals(before.expectedDecision()), "Approved decision drift");
        }
        for (int i = 0; i < 30; i++) {
            var body = p.requestBodies().get(i);
            check(RelationGuidedVerificationTrial.body(p.requests().get(i)).equals(body), "Frozen provider body changed");
            check(!body.toString().contains("expectedDecision") && !body.toString().contains("USER_APPROVED"),
                    "Evaluator metadata must not be transmitted");
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "frozen source plan and new private directory required");
        byte[] source = OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        check(SOURCE_SHA.equals(ReferenceOnlyTrial.sha(source)), "Exact frozen source plan required");
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(Path.of("../datasets/controversy/experiments").toRealPath()),
                "New private experiment directory required");
        var plan = build(JSON.readTree(source)); byte[] bytes = JSON.writeValueAsBytes(plan);
        Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SourceRelationExtractionTrial.writePrivate(out.resolve("plan.json"), bytes);
        System.out.println("ISSUE_SCOPE_PREPARED providerCalls=0 calls=30 reservationEstimatedUsd=" + plan.reservationEstimatedUsd()
                + " planSha256=" + ReferenceOnlyTrial.sha(bytes));
    }
}
