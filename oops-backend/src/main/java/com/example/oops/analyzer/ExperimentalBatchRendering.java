package com.example.oops.analyzer;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Experiment-only raw rendering: judged/context roles and omitted-transcript markers. Not used by run(). */
final class ExperimentalBatchRendering {
    static final String VERSION = "experimental-batch-v1";
    static final String JUDGED = "JUDGED", CONTEXT_ONLY = "CONTEXT_ONLY";
    static final String CONTRACT = """

            [실험 입력 계약 %s]
            - raw의 batchRole이 JUDGED인 구간만 판단 대상이다. reviewedSegmentIds·uncertainSegments·anchorId에는 JUDGED 구간 ID만 쓴다.
            - batchRole이 CONTEXT_ONLY인 구간은 판단을 돕는 참고 원문이다. 근거로 인용할 수 있지만 검토 완료 대상으로 보고하지 않는다.
            - omittedBefore가 있으면 그 구간 앞에 제공되지 않은 대화가 있었다. 제공된 구간끼리 시간상 인접하거나 직접 이어진다고 가정하지 않고, 생략된 내용을 추측하지 않는다.
            """.formatted(VERSION);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Comparator<ReviewInput.Segment> ORDER =
            Comparator.comparingLong(ReviewInput.Segment::startMs).thenComparing(ReviewInput.Segment::id);

    private final Map<String, Integer> transcriptIndex = new HashMap<>();
    private final Set<String> judgedIds;

    ExperimentalBatchRendering(ReviewInput transcript, Set<String> judgedIds) {
        var ordered = transcript.segments();
        for (int i = 0; i < ordered.size(); i++) transcriptIndex.put(ordered.get(i).id(), i);
        this.judgedIds = Set.copyOf(judgedIds);
    }

    String system(String base) {
        return base + CONTRACT;
    }

    List<Map<String, Object>> render(List<ReviewInput.Segment> raw) {
        var ordered = raw.stream().sorted(ORDER).toList();
        List<Map<String, Object>> rows = new ArrayList<>(ordered.size());
        ReviewInput.Segment previous = null;
        for (var s : ordered) {
            Integer index = transcriptIndex.get(s.id());
            if (index == null) throw new IllegalArgumentException("Segment outside transcript: " + s.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id());
            row.put("startMs", s.startMs());
            row.put("endMs", s.endMs());
            row.put("text", s.text());
            row.put("batchRole", judgedIds.contains(s.id()) ? JUDGED : CONTEXT_ONLY);
            if (previous != null) {
                int omitted = index - transcriptIndex.get(previous.id()) - 1;
                if (omitted > 0) row.put("omittedBefore",
                        Map.of("segments", omitted, "gapMs", Math.max(0L, s.startMs() - previous.endMs())));
            }
            rows.add(row);
            previous = s;
        }
        return List.copyOf(rows);
    }

    /** Exactly the CONTEXT_ONLY rows as rendered, including their markers: the unit the token budget counts. */
    String budgetedContext(List<ReviewInput.Segment> raw) {
        return JSON.writeValueAsString(contextRows(raw));
    }

    List<Map<String, Object>> contextRows(List<ReviewInput.Segment> raw) {
        return render(raw).stream().filter(r -> CONTEXT_ONLY.equals(r.get("batchRole"))).toList();
    }
}
