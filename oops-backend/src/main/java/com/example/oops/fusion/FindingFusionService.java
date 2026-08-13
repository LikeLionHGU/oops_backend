package com.example.oops.fusion;

import com.example.oops.domain.EvidenceSource;
import com.example.oops.domain.RiskCategory;
import com.example.oops.domain.RiskFinding;
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
 *   1. 시간이 겹치고 카테고리가 같은 후보를 한 묶음으로 만든다
 *   2. 묶음마다 대표 1건을 뽑고 나머지는 버린다
 *   3. 음성과 화면 양쪽에서 잡힌 묶음은 근거가 강하므로 점수를 올린다 (교차 검증)
 *   4. 우선순위 점수를 매겨 정렬한다
 */
@Slf4j
@Service
public class FindingFusionService {

    /** 이 시간(ms) 안에 있으면 같은 장면으로 본다 */
    private static final long MERGE_WINDOW_MS = 3000;

    /**
     * 시간이 떨어져 있어도 내용이 이만큼 같으면 같은 논란으로 본다.
     *
     * 영상 내내 떠 있는 고정 자막은 OCR 이 프레임마다 다시 읽어서
     * 같은 문구가 6초 간격으로 5번, 10번 잡힌다.
     * 시간만 보고 묶으면 똑같은 카드가 그만큼 쌓인다.
     */
    private static final double SAME_ISSUE_THRESHOLD = 0.7;

    /** 교차 검증됐을 때 점수를 몇 배로 올릴지 */
    private static final double CROSS_MODAL_BOOST = 1.25;

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

            // 여러 번 등장했다면 카드 하나로 합치고 구간을 처음~끝으로 넓힌다
            representative.expandRange(cluster.minStartMs(), cluster.maxEndMs());

            if (crossModal) {
                representative.boostScore(representative.getScore() * CROSS_MODAL_BOOST);
                representative.appendReason("(발언과 화면 양쪽에서 나타납니다)");
                // 대표가 발언 쪽이라 프레임이 없으면, 같은 묶음의 화면 캡처를 붙여준다
                cluster.anyFrame().ifPresent(representative::attachFrame);
            }

            representative.applyFusion(
                    calculatePriority(representative, crossModal, cluster.size()),
                    crossModal,
                    cluster.size()
            );
            result.add(representative);
        }

        result.sort(FindingOrder.byPriority());

        long repeated = result.stream().filter(f -> f.getMergedCount() > 1).count();
        log.info("[fusion] 후보 {}건 → 최종 {}건 (교차검증 {}건, 반복 병합 {}건, 제거 {}건)",
                candidates.size(), result.size(),
                result.stream().filter(RiskFinding::isCrossModal).count(),
                repeated, candidates.size() - result.size());
        return result;
    }

    /** 카테고리가 같고 시간이 가까운 것끼리 묶는다. */
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
     * 0 ~ 1000 우선순위.
     * 확신도(최대 600) + 카테고리 중요도(최대 100) + 교차검증 보너스(150) + 중복 보고 보너스(최대 60)
     */
    private int calculatePriority(RiskFinding finding, boolean crossModal, int mergedCount) {
        int score = (int) Math.round(finding.getScore() * 600);
        score += CATEGORY_WEIGHT.getOrDefault(finding.getCategory(), 20);
        if (crossModal) {
            score += 150;
        }
        score += Math.min(60, (mergedCount - 1) * 20);
        return Math.min(1000, score);
    }

    /** 같은 장면 + 같은 카테고리로 묶인 후보 그룹 */
    private static class Cluster {

        private final List<RiskFinding> members = new ArrayList<>();

        boolean accepts(RiskFinding candidate) {
            if (members.isEmpty()) return true;
            RiskFinding first = members.get(0);

            if (!groupOf(first.getCategory()).equals(groupOf(candidate.getCategory()))) return false;

            // 1) 구간이 겹치거나 아주 가까이 붙어 있으면 같은 장면
            long gap = Math.max(
                    candidate.getStartMs() - maxEndMs(),
                    minStartMs() - candidate.getEndMs());
            if (gap <= MERGE_WINDOW_MS) return true;

            // 2) 시간이 떨어져 있어도 내용이 사실상 같으면 같은 논란
            //    (영상 내내 떠 있는 고정 자막이 프레임마다 다시 잡히는 경우)
            return members.stream()
                    .anyMatch(m -> similarity(m.primaryText(), candidate.primaryText())
                            >= SAME_ISSUE_THRESHOLD);
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
