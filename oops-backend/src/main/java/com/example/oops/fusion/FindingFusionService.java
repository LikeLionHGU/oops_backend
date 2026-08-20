package com.example.oops.fusion;

import com.example.oops.domain.EvidenceSource;
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

    /**
     * 대상 이름이 이만큼 겹치면 같은 대상으로 본다.
     *
     * 같은 것을 두고 분석기마다 다른 유형으로 보고하는 일이 흔하다.
     * "패스트푸드" 를 한쪽은 비하로, 다른 쪽은 일반화로 잡는 식이다.
     * 사용자에게는 같은 지적이므로 카드 하나로 합친다.
     */
    private static final double SAME_TARGET_THRESHOLD = 0.5;

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
            if (cluster.size() > 1) {
                representative.recordOccurrences(cluster.occurrenceTimes());
            }

            // 버려지는 후보가 들고 있던 참고 자료를 대표에게 넘긴다.
            // 근거를 들고 있던 쪽이 대표가 아닐 수 있어서, 그냥 두면 링크가 사라진다.
            cluster.collectReferencesInto(representative);

            // **흡수된 쪽이 지적한 표현도 남긴다.**
            //
            // 같은 대상에 대한 지적이라 한 카드로 묶는 건 맞지만,
            // 대표의 문장만 남기면 나머지가 무엇이었는지 흔적도 없이 사라진다.
            // "할머니 맛" 이 대표로 뽑히면서 같은 구간의
            // "할머니 살을 뜯는 거 같다" 가 통째로 안 보이게 된 적이 있다.
            //
            // 제작자는 그 문장을 보려고 이 도구를 쓴다. 지워서는 안 된다.
            String others = cluster.otherExpressions(representative);
            if (!others.isBlank()) {
                representative.appendReason("같은 구간에서 함께 걸린 표현: " + others);
            }

            if (crossModal) {
                representative.boostScore(representative.getScore() * CROSS_MODAL_BOOST);
                representative.appendReason("발언과 화면 양쪽에서 나타납니다.");
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

        // 무엇이 무엇에 흡수됐는지 남긴다.
        //
        // 예전에는 "제거 9건" 만 찍혀서, 분석기가 못 잡은 건지
        // 잡았는데 여기서 사라진 건지 구분이 안 됐다.
        // 8초 영상에서 10건이 1건으로 줄어든 걸 찾는 데 한참 걸렸다.
        if (candidates.size() > result.size()) {
            for (Cluster c : clusters) {
                if (c.size() <= 1) continue;
                log.info("[fusion] {}ms 에서 {}건을 '{}' 하나로 합침 — 흡수된 것: {}",
                        c.minStartMs(), c.size(), c.pickRepresentative().getTarget(),
                        c.absorbedSummary());
            }
        }
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

            // 1) 같은 대상을 지적한 것이면 유형이 달라도 한 건이다.
            //    "패스트푸드" 를 두고 한쪽은 BELITTLEMENT, 다른 쪽은 GENERALIZATION 으로
            //    보고하는 일이 흔하다. 사용자에게는 같은 지적이다.
            if (sharesTarget(candidate)) return true;

            // 2) 문장이 사실상 같으면 한 건이다.
            //    영상 내내 떠 있는 자막이 프레임마다 다시 잡히는 경우가 여기 해당한다.
            //
            //    **단, 가리키는 대상이 다르면 같은 문장이어도 다른 지적이다.**
            //
            //    한 문장 안에 지적할 것이 둘 있으면 분석기 둘이 같은 줄을
            //    서로 다른 이유로 보고한다. 그때 문장은 당연히 같다.
            //    8초 영상에서 사전이 '나오노' 를 커뮤니티 어미로 잡고
            //    배경 확인이 같은 줄을 '정치판 논란' 으로 잡았는데,
            //    문장이 같다는 이유로 후보 5건이 1건이 됐다.
            //    사용자에게 커뮤니티 어미 카드는 아예 나가지 않았다.
            //
            //    3번 갈래에는 이 관문이 있었는데 여기에는 없었다.
            //    문장이 같다는 것은 같은 줄이라는 뜻일 뿐, 같은 지적이라는 뜻이 아니다.
            //    같은 문구가 프레임마다 다시 잡히는 경우는 대상도 같으므로 그대로 묶인다.
            boolean sameText = members.stream().anyMatch(m ->
                    similarity(m.primaryText(), candidate.primaryText()) >= SAME_ISSUE_THRESHOLD);
            if (sameText && !hasDifferentTarget(candidate)) {
                return true;
            }

            // 3) 같은 유형이고 시간이 붙어 있으면 같은 장면이다.
            RiskFinding first = members.get(0);
            if (!groupOf(first.getCategory()).equals(groupOf(candidate.getCategory()))) return false;

            // **가리키는 대상이 서로 분명히 다르면 시간이 붙어 있어도 다른 건이다.**
            //
            // 이 조건이 없으면 짧은 영상에서 모든 후보가 한 덩어리가 된다.
            // 8초 영상은 모든 것이 서로 3초 안에 있기 때문이다.
            // 실제로 '무섭노', '오조오억', '독도는 일본땅' 이 전혀 다른 지적인데
            // 한 장면이라는 이유로 묶여서 10건이 1건으로 줄었다.
            //
            // 같은 문구가 프레임마다 다시 잡히는 경우는 2번에서 이미 걸러진다.
            // 여기까지 온 것 중 대상이 다른 건 정말 다른 지적이다.
            if (hasDifferentTarget(candidate)) return false;

            long gap = Math.max(
                    candidate.getStartMs() - maxEndMs(),
                    minStartMs() - candidate.getEndMs());
            return gap <= MERGE_WINDOW_MS;
        }

        /**
         * 양쪽 다 대상을 적었는데 서로 다른지.
         *
         * 한쪽이라도 대상이 비어 있으면 false 다.
         * 모르는 것을 다르다고 단정하면 묶여야 할 것까지 흩어진다.
         */
        private boolean hasDifferentTarget(RiskFinding candidate) {
            String candidateTarget = candidate.getTarget();
            if (candidateTarget == null || candidateTarget.isBlank()) return false;

            List<String> targets = members.stream()
                    .map(RiskFinding::getTarget)
                    .filter(t -> t != null && !t.isBlank())
                    .toList();
            if (targets.isEmpty()) return false;

            return targets.stream()
                    .noneMatch(t -> similarity(t, candidateTarget) >= SAME_TARGET_THRESHOLD);
        }

        /** 대상 이름이 겹치는지. "할머니" 와 "할머니 맛" 은 같은 대상으로 본다. */
        private boolean sharesTarget(RiskFinding candidate) {
            String candidateTarget = candidate.getTarget();
            if (candidateTarget == null || candidateTarget.isBlank()) return false;

            return members.stream()
                    .map(RiskFinding::getTarget)
                    .filter(t -> t != null && !t.isBlank())
                    .anyMatch(t -> similarity(t, candidateTarget) >= SAME_TARGET_THRESHOLD);
        }

        /**
         * 대표가 아닌 구성원이 지적한 표현들.
         *
         * 대표와 겹치는 것은 뺀다. 같은 말을 두 번 보여줄 이유가 없다.
         * 카드가 길어지지 않게 두 개까지만, 각각 30자까지 자른다.
         */
        String otherExpressions(RiskFinding representative) {
            String repText = representative.primaryText() == null
                    ? "" : representative.primaryText();
            String repTarget = representative.getTarget() == null
                    ? "" : representative.getTarget();

            return members.stream()
                    .filter(m -> m != representative)
                    .map(m -> labelFor(m, repText, repTarget))
                    .filter(t -> t != null && !t.isBlank())
                    .distinct()
                    .limit(2)
                    .map(t -> "\"" + (t.length() > 30 ? t.substring(0, 30) + "…" : t) + "\"")
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        /**
         * 흡수된 구성원을 카드에 무엇으로 적을지 고른다.
         *
         *   문장이 대표와 다르면        그 문장을 적는다
         *   문장은 같은데 대상이 다르면  **대상 이름**을 적는다
         *   둘 다 같으면               진짜 중복이므로 적지 않는다
         *
         * 가운데 갈래가 없어서 지적이 통째로 사라진 적이 있다.
         * 한 줄에 지적이 둘이면 문장이 같을 수밖에 없는데, 문장만 비교해
         * 걸러내니 흡수된 쪽이 흔적도 없이 없어졌다.
         * 병합 자체를 막는 것은 accepts() 몫이고, 여기는 마지막 보험이다.
         */
        private String labelFor(RiskFinding member, String repText, String repTarget) {
            String text = member.primaryText();
            if (text != null && !text.isBlank()
                    && similarity(text, repText) < SAME_ISSUE_THRESHOLD) {
                return text;
            }
            String target = member.getTarget();
            if (target != null && !target.isBlank()
                    && similarity(target, repTarget) < SAME_TARGET_THRESHOLD) {
                return target;
            }
            return null;
        }

        /** 어떤 것들이 흡수됐는지 로그용 한 줄 */
        String absorbedSummary() {
            RiskFinding rep = pickRepresentative();
            return members.stream()
                    .filter(m -> m != rep)
                    .map(m -> "%s/'%s'".formatted(m.getCategory(), m.getTarget()))
                    .collect(java.util.stream.Collectors.joining(", "));
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
