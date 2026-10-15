package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Test-only output-instruction comparison. No policy/validator changes and no provider calls. */
public final class VerificationContractComparison {
    private VerificationContractComparison() {}
    static final String REVISION = "2026-10-15-verification-output-contract-42";

    static final String OLD_CONTRAST = """
            reason은 400자 이내, alternativeInterpretation은 300자 이내로 정상 해석이 설명하는 범위와
              별도의 검토 이유가 남는지를 적는다. REVIEW_REQUIRED에는 대조 해석이 필수다.
              TARGET_TREATMENT의 proposedEvidence가 서로 다른 두 줄 이상이면 PASS에도 대조 해석이 필수다.
              이 PASS의 reason에는 전체 연결의 정상 해석을, alternativeInterpretation에는 가장 강한 비판 가설과
              그 가설을 현재 원문이 뒷받침하지 않는 이유를 적는다. 후보 이유를 사실로 받아들이지 않는다.
              또한 proposedEvidence의 anchor 이외 줄에서 실제 연결을 설명하는 CONTEXT 인용을 하나 이상 반환한다.
              필요한 연결을 확인할 수 없으면 UNCERTAIN이다. 통과·경고 어느 쪽도 강제하지 않는다.
            """;
    static final String CONTRAST = """
            판정을 정한 뒤 아래 조건별 출력 계약을 확인한다. reason은 400자, alternativeInterpretation은 300자 이내다.
              REVIEW_REQUIRED: alternativeInterpretation에 정상 해석과 그래도 검토 이유가 남는 근거를 반드시 적는다.
              PASS이고 axis=TARGET_TREATMENT이며 proposedEvidence의 서로 다른 segmentId가 2개 이상인 경우:
                reason에는 전체 연결의 정상 해석을 적는다. alternativeInterpretation에도 비판 가설이 왜 원문으로
                뒷받침되지 않는지 반드시 적는다. reason에 이미 설명했어도 이 필드는 null/빈 문자열이면 안 된다.
                evidence에는 proposedEvidence 중 anchor가 아닌 줄의 실제 CONTEXT를 하나 이상 선택한다.
              그 외 PASS/UNCERTAIN은 alternativeInterpretation=null도 허용한다.
              후보 가설은 사실이 아니다. 비판 근거를 꾸며 대조를 채우지 않는다. 필요한 연결 정보가 없으면 UNCERTAIN이다.
              출력 계약을 맞추려고 판정을 바꾸거나 없는 근거를 만들지 않는다.
            """;
    static final String OLD_RELATION = """
              EXPLICIT은 targetMention이 target과 같아야 한다. CONTEXTUAL은 '여기' 같은 지칭어와
              별도 발언의 CONTEXT 인용을 함께 반환하고 targetReason에 둘의 연결 근거를 설명한다.
            """;
    static final String RELATION = """
              targetMention과 target이 문자 그대로 같을 때만 EXPLICIT이다. 의미가 같은지 여부로 정하지 않는다.
              대명사뿐 아니라 신체 지칭·호칭도 해석한 대상명과 문자열이 다르면 CONTEXTUAL이다.
              CONTEXTUAL은 지칭어가 있는 TARGET과 다른 segmentId의 실제 CONTEXT를 함께 선택하고
              targetReason에 두 원문의 연결을 설명한다. 문자열을 억지로 같게 하려고 해석 대상이나 인용을 바꾸지 않는다.
            """;
    static final String OLD_JSON_INTRO = "JSON: {\"verifications\":[{\"candidateId\":\"요청 후보 ID\",\"assessment\":{\n";
    static final String JSON_INTRO = """
            아래 JSON은 필드 구조다. alternativeInterpretation:null은 모든 판정에 쓸 기본값이 아니다.
            위 조건의 REVIEW_REQUIRED/연결 PASS이면 실제 대조 설명으로 바꾸고 필수 CONTEXT도 선택한다.
            JSON: {"verifications":[{"candidateId":"요청 후보 ID","assessment":{
            """;

    static String replaceOnce(String source, String before, String after) {
        int at = source.indexOf(before);
        check(at >= 0 && at == source.lastIndexOf(before), "Exactly one known output section required");
        return source.substring(0, at) + after + source.substring(at + before.length());
    }

    static String replaceContract(String baseline) {
        check(baseline != null && (baseline.startsWith(SourceRelationPrompts.verification(0))
                || baseline.startsWith(CandidateReviewPrompts.VERIFICATION_PROMPT)), "Known baseline verification required");
        // Only verification output sections; shared judgment policy and reference suffix remain unchanged.
        String updated = replaceOnce(baseline, OLD_CONTRAST, CONTRAST);
        updated = replaceOnce(updated, OLD_RELATION, RELATION);
        return replaceOnce(updated, OLD_JSON_INTRO, JSON_INTRO);
    }

    static Map<String,Object> prepare(JsonNode source) {
        List<ContextPolicyTrial.Task> tasks = new ArrayList<>();
        source.path("tasks").forEach(t -> tasks.add(JSON.treeToValue(t, ContextPolicyTrial.Task.class)));
        BanterPolicyTrial.validatePlan(source, tasks);
        var pairs = tasks.stream().map(t -> new ContextPolicyTrial.Task(t.spec(), t.baselineSystem(),
                replaceContract(t.baselineSystem()), t.input())).toList();
        return Map.of("schemaVersion", REVISION, "scope", "FIXED_VERIFICATION_OUTPUT_INSTRUCTIONS_ONLY",
                "providerCalls", 0, "liveApprovalRequired", true, "tasks", pairs);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "Source comparison41 plan and new private output file required");
        byte[] source = OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        var prepared = new LinkedHashMap<>(prepare(JSON.readTree(source)));
        prepared.put("sourcePlanSha256", ReferenceOnlyTrial.sha(source));
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root), "New private output path required");
        Files.writeString(output, JSON.writeValueAsString(prepared), StandardOpenOption.CREATE_NEW);
        System.out.println("OUTPUT_CONTRACT_PREPARED pairs=12 providerCalls=0 policyChanged=false validatorChanged=false");
    }
}
