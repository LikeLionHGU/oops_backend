package com.example.oops.fusion;

import com.example.oops.domain.EvidenceSource;
import com.example.oops.domain.FindingSupport;
import com.example.oops.domain.RiskCategory;
import com.example.oops.domain.RiskFinding;
import com.example.oops.service.ReportBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 여러 분석기가 만든 논란 후보를 하나의 목록으로 정리한다.
 *
 * 음성(STT)과 화면(OCR)을 따로 분석하면 같은 장면이 여러 번 잡힌다.
 * 예를 들어 00:15 의 욕설은 룰 분석기와 LLM 분석기가 각각 보고하고,
 * 그 욕설이 화면 자막에도 박혀 있으면 OCR 분석기까지 세 번 보고한다.
 * 그대로 두면 프론트에 중복 카드가 쌓이고 어느 게 중요한지 알 수 없다.
 *
 * 처리 순서:
 *   1. 검증된 anchor·공유 인용 연결로 보수적으로 묶는다
 *   2. 대표 1건에 원문 근거와 분석 지원을 보존한다
 *   3. 복수 분석 지원만으로 점수나 등장 횟수를 올리지 않는다
 *   4. 우선순위 점수를 매겨 정렬한다
 */
@Slf4j
@Service
public class FindingFusionService {

    public static final String REVISION = "2026-10-10-validated-flow-fusion-2";

    /** 이 시간(ms) 안에 있으면 같은 장면으로 본다 */
    private static final long MERGE_WINDOW_MS = 3000;

    /**
     * 뜻이 겹치는 카테고리 묶음.
     *
     * 분석기마다 같은 문장을 조금씩 다른 이름으로 부른다.
     * 예를 들어 화면 자막의 "재선거" 는
     *   screen-text-risk 가 SENSITIVE_TOPIC 으로,
     *   timeliness 가 TIMING_SENSITIVE 로 보고한다.
     * 사용자 입장에서는 같은 지적이므로 카드가 두 장 뜨면 안 된다.
     * 여기 묶인 카테고리끼리는 같은 장면이면 한 건으로 합친다.
     */
    private static final Map<RiskCategory, String> MERGE_GROUP = new EnumMap<>(RiskCategory.class);

    static {
        MERGE_GROUP.put(RiskCategory.SENSITIVE_TOPIC, "SENSITIVE");
        MERGE_GROUP.put(RiskCategory.UNFAMILIAR_CONTEXT, "SENSITIVE");
        MERGE_GROUP.put(RiskCategory.TIMING_SENSITIVE, "SENSITIVE");

        MERGE_GROUP.put(RiskCategory.MOCKERY, "PUTDOWN");
        MERGE_GROUP.put(RiskCategory.BELITTLEMENT, "PUTDOWN");
        MERGE_GROUP.put(RiskCategory.STRONG_NEGATIVE_REVIEW, "NEGATIVE_REVIEW");
        MERGE_GROUP.put(RiskCategory.GRAPHIC_METAPHOR, "GRAPHIC_METAPHOR");

        MERGE_GROUP.put(RiskCategory.AD_DEMONETIZED, "AD");
        MERGE_GROUP.put(RiskCategory.AD_LIMITED, "AD");

        MERGE_GROUP.put(RiskCategory.FACT_ERROR, "FACT");
        MERGE_GROUP.put(RiskCategory.MISINFORMATION, "FACT");
        MERGE_GROUP.put(RiskCategory.UNVERIFIED_CLAIM, "FACT");

        MERGE_GROUP.put(RiskCategory.HATE_SPEECH, "HATE");
        MERGE_GROUP.put(RiskCategory.DISCRIMINATION, "HATE");
    }

    /** 묶음에 속하지 않으면 카테고리 자체가 그룹이 된다. */
    private static String groupOf(RiskCategory category) {
        return MERGE_GROUP.getOrDefault(category, category.name());
    }

    /** 카테고리별 기본 중요도. 같은 점수면 이 순서가 앞선다. */
    private static final Map<RiskCategory, Integer> CATEGORY_WEIGHT = new EnumMap<>(RiskCategory.class);

    static {
        // 수익과 직결되므로 가장 위에 보여준다
        CATEGORY_WEIGHT.put(RiskCategory.AD_DEMONETIZED, 120);
        CATEGORY_WEIGHT.put(RiskCategory.AD_LIMITED, 110);

        CATEGORY_WEIGHT.put(RiskCategory.HATE_SPEECH, 100);
        CATEGORY_WEIGHT.put(RiskCategory.DISCRIMINATION, 95);
        CATEGORY_WEIGHT.put(RiskCategory.PRIVACY, 90);
        CATEGORY_WEIGHT.put(RiskCategory.TIMING_SENSITIVE, 88);
        CATEGORY_WEIGHT.put(RiskCategory.BELITTLEMENT, 80);
        CATEGORY_WEIGHT.put(RiskCategory.STRONG_NEGATIVE_REVIEW, 35);
        CATEGORY_WEIGHT.put(RiskCategory.GRAPHIC_METAPHOR, 45);
        CATEGORY_WEIGHT.put(RiskCategory.MOCKERY, 78);
        CATEGORY_WEIGHT.put(RiskCategory.SENSITIVE_TOPIC, 75);
        CATEGORY_WEIGHT.put(RiskCategory.FACT_ERROR, 96);
        CATEGORY_WEIGHT.put(RiskCategory.MISINFORMATION, 72);
        CATEGORY_WEIGHT.put(RiskCategory.UNVERIFIED_CLAIM, 68);
        CATEGORY_WEIGHT.put(RiskCategory.UNFAMILIAR_CONTEXT, 85);
        CATEGORY_WEIGHT.put(RiskCategory.CAPTION_MISMATCH, 82);
        CATEGORY_WEIGHT.put(RiskCategory.GENERALIZATION, 65);
        CATEGORY_WEIGHT.put(RiskCategory.VIOLENCE, 60);
        CATEGORY_WEIGHT.put(RiskCategory.SEXUAL, 55);
        CATEGORY_WEIGHT.put(RiskCategory.PROFANITY, 50);
        CATEGORY_WEIGHT.put(RiskCategory.GESTURE, 45);
        CATEGORY_WEIGHT.put(RiskCategory.SCREEN_TEXT, 40);
        CATEGORY_WEIGHT.put(RiskCategory.ADVERTISING, 30);
        CATEGORY_WEIGHT.put(RiskCategory.COMMENT_BACKLASH, 25);
    }

    public List<RiskFinding> fuse(List<RiskFinding> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Cluster> clusters = cluster(candidates);
        List<RiskFinding> result = new ArrayList<>();

        for (Cluster cluster : clusters) {
            RiskFinding representative = cluster.pickRepresentative();
            boolean crossModal = cluster.isCrossModal();

            // Compute before mutating ranges or adopting supports.
            int occurrences = cluster.occurrenceCount();
            String occurrenceTimes = cluster.occurrenceTimes();
            var support = new ArrayList<>(representative.validatedSupports());
            for (var member : cluster.members) if (member != representative) support.addAll(member.validatedSupports());
            if (!support.isEmpty()) representative.recordValidatedSupports(support);
            representative.recordOccurrenceCount(occurrences);

            // 여러 번 등장했다면 카드 하나로 합치고 구간을 처음~끝으로 넓힌다
            representative.expandRange(cluster.minStartMs(), cluster.maxEndMs());
            if (occurrences > 1) {
                representative.recordOccurrences(occurrenceTimes);
            }

            // 버려지는 후보가 들고 있던 참고 자료를 대표에게 넘긴다.
            // 근거를 들고 있던 쪽이 대표가 아닐 수 있어서, 그냥 두면 링크가 사라진다.
            cluster.collectReferencesInto(representative);

            if (crossModal) {
                representative.appendReason("발언과 화면 양쪽에서 나타납니다.");
                // 대표가 발언 쪽이라 프레임이 없으면, 같은 묶음의 화면 캡처를 붙여준다
                cluster.anyFrame().ifPresent(representative::attachFrame);
            }

            representative.applyFusion(
                    calculatePriority(representative),
                    crossModal,
                    cluster.size()
            );
            result.add(representative);
        }

        result.sort(FindingOrder.byPriority());

        long repeated = result.stream().filter(f -> f.getMergedCount() > 1).count();
        log.info("[fusion] revision={} 후보 {}건 → 최종 {}건 (교차출처 {}건, 병합 {}건, 제거 {}건)",
                REVISION, candidates.size(), result.size(),
                result.stream().filter(RiskFinding::isCrossModal).count(),
                repeated, candidates.size() - result.size());
        return result;
    }

    /** Stable chronological order, conservative complete-link validated-flow grouping. */
    private List<Cluster> cluster(List<RiskFinding> candidates) {
        List<RiskFinding> sorted = new ArrayList<>(candidates);
        sorted.sort(FindingOrder.byTime());

        List<Cluster> clusters = new ArrayList<>();

        for (RiskFinding candidate : sorted) {
            Cluster target = clusters.stream()
                    .filter(c -> c.accepts(candidate))
                    .findFirst()
                    .orElse(null);

            if (target == null) {
                Cluster created = new Cluster();
                created.add(candidate);
                clusters.add(created);
            } else {
                target.add(candidate);
            }
        }
        return clusters;
    }

    /**
     * 0 ~ 1000 우선순위. 병합·교차 출처만으로 점수를 올리지 않는다.
     */
    private int calculatePriority(RiskFinding finding) {
        int score = (int) Math.round(finding.getScore() * 600);
        score += CATEGORY_WEIGHT.getOrDefault(finding.getCategory(), 20);
        return Math.min(1000, score);
    }

    /** 같은 장면 + 같은 카테고리로 묶인 후보 그룹 */
    private static class Cluster {

        private final List<RiskFinding> members = new ArrayList<>();

        boolean accepts(RiskFinding candidate) {
            if (members.isEmpty()) return true;
            // Exact legacy repeated text retains adjacent deduplication; never target-name-only merging.
            if (candidate.validatedSupports().isEmpty() && members.stream().allMatch(m -> m.validatedSupports().isEmpty())) {
                long gap = Math.max(candidate.getStartMs() - maxEndMs(), minStartMs() - candidate.getEndMs());
                return gap <= MERGE_WINDOW_MS && members.stream().allMatch(m ->
                        groupOf(m.getCategory()).equals(groupOf(candidate.getCategory()))
                        && targetMatches(m, candidate)
                        && m.primaryText() != null && !m.primaryText().isBlank()
                        && m.primaryText().equals(candidate.primaryText()));
            }
            // Complete-link, not A~B~C transitive closure. Every member must justify the same flow.
            return members.stream().allMatch(m -> sameValidatedFlow(m, candidate));
        }

        private boolean sameValidatedFlow(RiskFinding a, RiskFinding b) {
            if (a.getCategory() != b.getCategory() || a.getEventType() != b.getEventType()) return false;
            var x = a.validatedSupports(); var y = b.validatedSupports();
            if (x.isEmpty() || y.isEmpty()) return false;
            var p = x.get(0); var q = y.get(0);
            if (!java.util.Objects.equals(p.targetType(), q.targetType())) return false;
            if (p.anchorId().equals(q.anchorId())) {
                boolean samePrimary = p.quotes().stream().filter(e -> "PRIMARY".equals(e.role()) && e.segmentId().equals(p.anchorId()))
                        .anyMatch(e -> q.quotes().stream().anyMatch(f -> "PRIMARY".equals(f.role()) && sameQuote(e, f)));
                return samePrimary && sameAnchoredTarget(a, b, p, q);
            }
            if (!targetMatches(a, b)) return false;
            if (p.targetType() == null) return false;
            long start = Math.min(a.getStartMs(), b.getStartMs()), end = Math.max(a.getEndMs(), b.getEndMs());
            if (end - start > 60000) return false;
            boolean linkedAnchor = p.quotes().stream().anyMatch(e -> "CONTEXT".equals(e.role()) && e.segmentId().equals(q.anchorId())
                    && q.quotes().stream().anyMatch(f -> "PRIMARY".equals(f.role()) && sameQuote(e, f)))
                    || q.quotes().stream().anyMatch(e -> "CONTEXT".equals(e.role()) && e.segmentId().equals(p.anchorId())
                    && p.quotes().stream().anyMatch(f -> "PRIMARY".equals(f.role()) && sameQuote(e, f)));
            long shared = p.quotes().stream().filter(e -> "CONTEXT".equals(e.role()))
                    .filter(e -> q.quotes().stream().anyMatch(f -> "CONTEXT".equals(f.role()) && sameQuote(e, f)))
                    .map(FindingSupport.Quote::segmentId).distinct().count();
            return linkedAnchor && shared >= 2;
        }

        private boolean sameAnchoredTarget(RiskFinding a, RiskFinding b, FindingSupport p, FindingSupport q) {
            var left = p.quotes().stream().filter(e -> "TARGET".equals(e.role())).toList();
            var right = q.quotes().stream().filter(e -> "TARGET".equals(e.role())).toList();
            if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty() && targetMatches(a, b);
            if (p.targetType() == null) return false;
            // Resolved display labels are not identities. Compare grounded raw mentions instead.
            if (!left.isEmpty() && !right.isEmpty()
                    && left.stream().allMatch(e -> right.stream().anyMatch(f -> overlappingTargetMention(e, f)))
                    && right.stream().allMatch(e -> left.stream().anyMatch(f -> overlappingTargetMention(e, f)))) return true;
            // Nearby place deixis needs two shared context segments, never proximity alone.
            if (!"REGION".equals(p.targetType())) return false;
            long shared = p.quotes().stream().filter(e -> "CONTEXT".equals(e.role()))
                    .filter(e -> q.quotes().stream().anyMatch(f -> "CONTEXT".equals(f.role()) && sameQuote(e, f)))
                    .map(FindingSupport.Quote::segmentId).distinct().count();
            return shared >= 2 && !left.isEmpty() && !right.isEmpty()
                    && left.stream().allMatch(e -> nearbyPlaceMention(e, a.representativeStartMs()))
                    && right.stream().allMatch(e -> nearbyPlaceMention(e, b.representativeStartMs()));
        }

        private boolean overlappingTargetMention(FindingSupport.Quote a, FindingSupport.Quote b) {
            return a.segmentId().equals(b.segmentId()) && a.type() == b.type()
                    && a.startMs() == b.startMs() && a.endMs() == b.endMs()
                    && (a.quote().contains(b.quote()) || b.quote().contains(a.quote()));
        }

        private boolean nearbyPlaceMention(FindingSupport.Quote quote, long anchorMs) {
            return quote.type() == com.example.oops.domain.TimelineEventType.SPEECH
                    && Math.abs(quote.startMs() - anchorMs) <= 3000
                    && quote.quote().matches(".*(?:여기|이곳|그곳|저기|저곳).*");
        }

        private boolean targetMatches(RiskFinding a, RiskFinding b) {
            return a.getTarget() == null && b.getTarget() == null
                    || a.getTarget() != null && b.getTarget() != null && similarity(a.getTarget(), b.getTarget()) >= 0.85;
        }

        private boolean sameQuote(FindingSupport.Quote a, FindingSupport.Quote b) {
            return a.segmentId().equals(b.segmentId()) && a.type() == b.type()
                    && a.startMs() == b.startMs() && a.endMs() == b.endMs() && a.quote().equals(b.quote());
        }

        int occurrenceCount() {
            // Evaluator support duplicates at one raw anchor are one occurrence.
            var speech = members.stream().flatMap(m -> m.validatedSupports().stream())
                    .flatMap(s -> s.quotes().stream().filter(q -> q.segmentId().equals(s.anchorId()) && "PRIMARY".equals(q.role())))
                    .filter(q -> q.type() == com.example.oops.domain.TimelineEventType.SPEECH).toList();
            if (!speech.isEmpty()) return (int) speech.stream().map(FindingSupport.Quote::segmentId).distinct().count();
            return (int) members.stream().map(m -> m.getStartMs() + ":" + m.getEndMs() + ":" + m.primaryText()).distinct().count();
        }

        void add(RiskFinding finding) {
            members.add(finding);
        }

        int size() {
            return members.size();
        }

        long minStartMs() {
            return members.stream().mapToLong(RiskFinding::getStartMs).min().orElse(0);
        }

        long maxEndMs() {
            return members.stream().mapToLong(RiskFinding::getEndMs).max().orElse(0);
        }

        /**
         * 각각 몇 분 몇 초에 나왔는지.
         * 구간만 보여주면 "00:26 ~ 00:59 사이 어딘가" 로 뭉뚱그려져서
         * 제작자가 어디를 봐야 할지 알 수 없다.
         */
        String occurrenceTimes() {
            List<String> times = members.stream()
                    .mapToLong(RiskFinding::getStartMs)
                    .distinct()
                    .sorted()
                    .mapToObj(ReportBuilder::formatTime)
                    .toList();

            if (times.size() <= 5) {
                return String.join(", ", times);
            }
            return String.join(", ", times.subList(0, 5)) + " 외 " + (times.size() - 5) + "곳";
        }

        /** 조사·기호를 뺀 뒤 겹치는 글자 비율. OCR 오인식이 섞여도 견딜 수 있게 글자 단위로 본다. */
        private double similarity(String a, String b) {
            if (a == null || b == null) return 0;
            String x = a.replaceAll("[^가-힣a-zA-Z0-9]", "");
            String y = b.replaceAll("[^가-힣a-zA-Z0-9]", "");
            if (x.isEmpty() || y.isEmpty()) return 0;

            Map<Character, Integer> counts = new HashMap<>();
            for (char c : x.toCharArray()) counts.merge(c, 1, Integer::sum);

            int common = 0;
            for (char c : y.toCharArray()) {
                Integer left = counts.get(c);
                if (left != null && left > 0) {
                    counts.put(c, left - 1);
                    common++;
                }
            }
            return (double) common / Math.min(x.length(), y.length());
        }

        /** 확신도가 가장 높은 건을 대표로 삼는다. 같으면 설명이 구체적인 쪽. */
        RiskFinding pickRepresentative() {
            return members.stream()
                    .max(Comparator
                            .comparingDouble(RiskFinding::getScore)
                            .thenComparingInt(f -> f.getReason() == null ? 0 : f.getReason().length()))
                    .orElse(members.get(0));
        }

        /**
         * 묶음 안 다른 후보들의 참고 자료를 대표에게 모아준다.
         *
         * 예를 들어 "OO 사건" 을 은어 사전이 먼저 잡고 맥락 분석기가 기사와 함께 잡으면,
         * 확신도가 높은 사전 쪽이 대표가 되면서 기사 링크가 통째로 날아간다.
         * 사용자에게는 같은 카드이므로 근거는 합쳐서 보여준다.
         */
        void collectReferencesInto(RiskFinding representative) {
            for (RiskFinding member : members) {
                if (member != representative) {
                    representative.adoptReferences(member.getReferences());
                }
            }
        }

        /** 발언(음성)과 화면 양쪽에서 잡혔는지 */
        boolean isCrossModal() {
            Set<EvidenceSource> sources = members.stream()
                    .map(RiskFinding::getSource)
                    .collect(Collectors.toSet());
            return sources.contains(EvidenceSource.SUBTITLE)
                    && (sources.contains(EvidenceSource.VISION) || sources.contains(EvidenceSource.AUDIO));
        }

        java.util.Optional<com.example.oops.domain.VideoFrame> anyFrame() {
            return members.stream()
                    .map(RiskFinding::getFrame)
                    .filter(java.util.Objects::nonNull)
                    .findFirst();
        }
    }
}
