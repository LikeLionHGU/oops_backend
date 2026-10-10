package com.example.oops.analyzer;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import com.example.oops.domain.TimelineEventType;
import java.time.OffsetDateTime;
import java.util.*;

/** Working mechanism reference, distinct from approved evidence/training cases. */
@Component
public class ReviewGuidelineLibrary {
    public static final String CONTRACT = """
            # 범용 맥락 참고 기준
            reviewGuidelines는 여러 사례에서 추출한 미검증 작업 기준이며 정답·현재 영상의 증거가 아니다.
            기준의 조건과 정상 해석을 현재 원문에 대조한다. 항목별로 반드시 경고를 만들지는 않는다.
            단어 일치가 아니라 상황→대상→비교·평가의 연결을 시간순으로 읽는다.
            비판이 원문으로 설명되면 정상 해석이 가능하다는 이유만으로 지우지 않는다.
            다만 단순 불만·강한 리뷰만으로 대상 폄하를 만들지 않는다.
            기준·과거 사건·댓글의 표현을 evidence로 복사하거나 현재 원문을 교정하지 않는다.
            화자·억양·악의·외부 사건·문화적 의미를 상상하지 않는다. 필수 누락 정보는 명시한다.
            대상 평가를 반환할 때 TARGET 인용과 지칭 연결을 확인한다. CONTEXT 인용으로 대체하지 않는다.
            """;
    static final JsonMapper JSON = JsonMapper.builder().build();
    public record Rule(String id, String axis, List<String> channels, String condition,
                       String normalContrast, String requiredEvidence, String missingContext, List<String> sourceCaseIds) {}
    public record Archive(String schemaVersion, String version, String status, Boolean humanValidated,
                          String usage, String sourceBundleSha256, String refreshOrDeleteBy,
                          List<SourceCase> sourceCases, List<Rule> guidelines) {}
    public record SourceCase(String caseId, String familyId, String status) {}
    public record Example(String id, String axis, String condition, String normalContrast,
                          String requiredEvidence, String missingContext) {}
    public record Trace(String version, String state, String sourceBundleSha256, int payloadCodePoints,
                        List<String> guidelineIds) { public Trace { guidelineIds = List.copyOf(guidelineIds); } }
    private final ResourceLoader resources;
    private final boolean enabled;
    private final String location;
    private final int budget;
    private Archive archive;
    private String state = "NOT_LOADED";

    public ReviewGuidelineLibrary(ResourceLoader resources,
            @Value("${oops.analysis.guidelines-enabled:true}") boolean enabled,
            @Value("${oops.analysis.guidelines-location:file:../datasets/controversy/guidelines/runtime.json}") String location,
            @Value("${oops.analysis.guidelines-max-code-points:6000}") int budget) {
        if (location == null || !(location.startsWith("classpath:") || location.startsWith("file:"))
                || budget < 256 || budget > 6000)
            throw new IllegalArgumentException("Guidelines require a local archive and bounded budget");
        if (location.startsWith("file:")) {
            var uri = java.net.URI.create(location);
            if (uri.getAuthority() != null && !uri.getAuthority().isEmpty())
                throw new IllegalArgumentException("Guidelines do not allow remote file hosts");
        }
        this.resources = resources; this.enabled = enabled; this.location = location; this.budget = budget;
    }
    @PostConstruct void load() {
        archive = null;
        if (!enabled) { state = "DISABLED"; return; }
        try (var in = resources.getResource(location).getInputStream()) {
            byte[] bytes = in.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) { state = "ARCHIVE_TOO_LARGE"; return; }
            Archive candidate = JSON.readValue(bytes, Archive.class);
            if (!valid(candidate)) { state = "INVALID_ARCHIVE"; return; }
            archive = candidate; state = "READY_WORKING_REFERENCE";
            for (var channel : List.of(TimelineEventType.SPEECH, TimelineEventType.CAPTION)) {
                if (size(examples(channel)) > budget) { archive = null; state = "BUDGET_EXCEEDED"; return; }
            }
            if (expired()) state = "EXPIRED";
        } catch (Exception ex) { archive = null; state = "UNAVAILABLE_OR_INVALID"; }
    }
    private static boolean valid(Archive a) {
        if (a == null || !"review-guidelines-1".equals(a.schemaVersion()) || !text(a.version(), 64)
                || !"WORKING_REFERENCE_NOT_VALIDATED".equals(a.status()) || !Boolean.FALSE.equals(a.humanValidated())
                || !"REFERENCE_ONLY_CURRENT_INPUT_EVIDENCE_REQUIRED".equals(a.usage())
                || a.sourceBundleSha256() == null || !a.sourceBundleSha256().matches("[0-9a-f]{64}")
                || a.sourceCases() == null || a.sourceCases().isEmpty() || a.sourceCases().size() > 1000
                || a.guidelines() == null || a.guidelines().isEmpty() || a.guidelines().size() > 16) return false;
        try { OffsetDateTime.parse(a.refreshOrDeleteBy()); } catch (Exception ex) { return false; }
        Set<String> sources = new HashSet<>(), ids = new HashSet<>(), covered = new HashSet<>();
        for (var c : a.sourceCases()) if (c == null || !id(c.caseId()) || !id(c.familyId())
                || !"UNREVIEWED_DRAFT".equals(c.status()) || !sources.add(c.caseId())) return false;
        for (var r : a.guidelines()) {
            if (r == null || !id(r.id()) || !ids.add(r.id())
                    || !Set.of("TARGET_TREATMENT", "EXPRESSION_CONTENT").contains(r.axis() == null ? "" : r.axis())
                    || r.channels() == null || r.channels().isEmpty() || r.channels().stream().anyMatch(c -> !Set.of("SPEECH", "CAPTION").contains(c))
                    || !text(r.condition(), 350) || !text(r.normalContrast(), 350)
                    || !text(r.requiredEvidence(), 350) || !text(r.missingContext(), 350)
                    || r.sourceCaseIds() == null || r.sourceCaseIds().isEmpty() || !sources.containsAll(r.sourceCaseIds())) return false;
            covered.addAll(r.sourceCaseIds());
        }
        return covered.equals(sources);
    }
    private boolean expired() { return archive != null && !OffsetDateTime.parse(archive.refreshOrDeleteBy()).isAfter(OffsetDateTime.now()); }
    List<Example> examples(TimelineEventType channel) {
        if (archive == null || expired() || !"READY_WORKING_REFERENCE".equals(state)) return List.of();
        return archive.guidelines().stream().filter(r -> r.channels().contains(channel.name()))
                .map(r -> new Example(r.id(), r.axis(), r.condition(), r.normalContrast(), r.requiredEvidence(), r.missingContext())).toList();
    }
    Trace trace(TimelineEventType channel) {
        var examples = examples(channel);
        return new Trace(archive == null ? null : archive.version(), expired() ? "EXPIRED" : state,
                archive == null ? null : archive.sourceBundleSha256(), size(examples), examples.stream().map(Example::id).toList());
    }
    String prompt(TimelineEventType channel) {
        var examples = examples(channel);
        return examples.isEmpty() ? "" : "\n" + CONTRACT + "\nreviewGuidelines=" + JSON.writeValueAsString(examples);
    }
    Optional<String> unavailableNotice(TimelineEventType channel) {
        String current = trace(channel).state();
        return Set.of("READY_WORKING_REFERENCE", "DISABLED").contains(current) ? Optional.empty()
                : Optional.of("맥락 참고 기준집을 적용하지 못했습니다 (" + current + "). 기본 원문 검수만 수행했습니다.");
    }
    private static int size(List<Example> values) { String s = JSON.writeValueAsString(values); return s.codePointCount(0, s.length()); }
    private static boolean text(String s, int max) { return s != null && !s.isBlank() && s.codePointCount(0, s.length()) <= max; }
    private static boolean id(String s) { return s != null && s.matches("[a-zA-Z0-9_-]{1,64}"); }
}
