package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.util.*;

/** Offline common-candidate contract. Does not create model proposals, verdicts or report findings. */
final class CandidateUnionShadow {
    static final String REVISION = "2026-10-20-candidate-union-shadow-1";
    enum Origin { RULE, AI }
    enum State { READY, BUDGET_SKIPPED, CONTEXT_INVALID }
    record AiCandidate(String sourceId, String inputSha256, String anchorId, String axis, List<String> evidenceIds) {
        AiCandidate { evidenceIds = List.copyOf(evidenceIds); }
    }
    record Candidate(Origin origin, String sourceId, String sourceRevision, String mechanismId,
                     String anchorId, String axis, List<String> evidenceIds,
                     List<MechanismCandidateShadow.Signal> signals) {
        Candidate { evidenceIds=List.copyOf(evidenceIds); signals=List.copyOf(signals); }
    }
    record Entry(Candidate candidate, State state, List<ReviewInput.Segment> raw, boolean contextLimited) {
        Entry { raw=List.copyOf(raw); }
    }
    record Plan(String revision, String inputSha256, int duplicateInputs, List<Entry> entries) {
        Plan { entries=List.copyOf(entries); }
    }
    static String snapshotHash(ReviewInput input) {
        return ReviewGuidelineLibrary.retrievalHash(ReviewGuidelineLibrary.JSON.writeValueAsString(input));
    }

    static Plan plan(ReviewInput input, List<AiCandidate> ai, int budget) {
        if (budget < 0 || budget > 1000 || ai.size() > 1000) throw new IllegalArgumentException("Invalid shadow budget");
        String hash = snapshotHash(input);
        var rules = MechanismCandidateShadow.inspect(input);
        if (rules.omittedCandidates() > 0 || rules.overlongSegments() > 0)
            throw new IllegalArgumentException("Incomplete rule scan requires separate coverage handling");
        List<Candidate> candidates = new ArrayList<>();
        for (var r : rules.candidates()) candidates.add(new Candidate(Origin.RULE,
                r.mechanismId()+":"+r.anchorId(),r.ruleRevision(),r.mechanismId(),r.anchorId(),
                r.mechanismId().equals("graphic-body-image") ? "EXPRESSION_CONTENT" : "TARGET_TREATMENT",
                r.matchedSignals().stream().map(MechanismCandidateShadow.Signal::segmentId).distinct().toList(),r.matchedSignals()));
        int duplicates = 0;
        Map<String,AiCandidate> seen = new HashMap<>();
        for (var a : ai) {
            if (!hash.equals(a.inputSha256()) || a.sourceId()==null || a.sourceId().isBlank()
                    || !Set.of("TARGET_TREATMENT","EXPRESSION_CONTENT").contains(a.axis())
                    || a.evidenceIds().isEmpty() || !a.evidenceIds().contains(a.anchorId())
                    || new HashSet<>(a.evidenceIds()).size()!=a.evidenceIds().size())
                throw new IllegalArgumentException("Same snapshot and anchored unique evidence required");
            var previous = seen.putIfAbsent(a.sourceId(),a);
            if (previous!=null) {
                if (!previous.equals(a)) throw new IllegalArgumentException("Conflicting source ID");
                duplicates++; continue;
            }
            candidates.add(new Candidate(Origin.AI,a.sourceId(),"CALLER_SUPPLIED_UNVERIFIED_AI_CANDIDATE",null,
                    a.anchorId(),a.axis(),a.evidenceIds(),List.of()));
        }
        var byId = new HashMap<String,ReviewInput.Segment>(); input.segments().forEach(s -> byId.put(s.id(),s));
        List<Entry> all = new ArrayList<>();
        for (var c : candidates) {
            for (var id : c.evidenceIds()) if (!byId.containsKey(id) || byId.get(id).type()!=TimelineEventType.SPEECH)
                throw new IllegalArgumentException("Unknown or non-speech evidence");
            var evidence = input.segments().stream().filter(s -> c.evidenceIds().contains(s.id())).toList();
            var speech = input.segments().stream().filter(s -> s.type()==TimelineEventType.SPEECH).toList();
            var baseline = speech.subList(speech.indexOf(evidence.get(0)),speech.indexOf(evidence.get(evidence.size()-1))+1);
            // Never advertise a window that drops supplied evidence, exceeds budgets or crosses long silence.
            long end = baseline.get(0).endMs(); boolean gap=false;
            for (var s : baseline) { gap |= s.startMs()-end>5000; end=Math.max(end,s.endMs()); }
            boolean invalid = gap || baseline.size()>48 || end-baseline.get(0).startMs()>60000
                    || baseline.stream().mapToInt(s -> s.text().codePointCount(0,s.text().length())).sum()>6000;
            if (invalid) { all.add(new Entry(c,State.CONTEXT_INVALID,baseline,true)); continue; }
            var window = CandidateContextWindow.extend(input,c.anchorId(),baseline);
            // extend() also fills intervening raw; validate its actual result, not only endpoint evidence.
            var raw = window.raw(); long maxEnd=raw.get(0).endMs(); boolean rawGap=false;
            for (var s : raw) { rawGap |= s.startMs()-maxEnd>5000; maxEnd=Math.max(maxEnd,s.endMs()); }
            boolean rawInvalid=rawGap || raw.size()>48 || maxEnd-raw.get(0).startMs()>60000
                    || raw.stream().mapToInt(s -> s.text().codePointCount(0,s.text().length())).sum()>6000;
            all.add(new Entry(c,rawInvalid?State.CONTEXT_INVALID:State.READY,raw,window.limited()||rawInvalid));
        }
        // One slot per active minute per round. Within each minute alternate RULE/AI, no risk ranking.
        Map<Long,ArrayDeque<Entry>> buckets = new TreeMap<>();
        var order=Comparator.comparingLong((Entry e) -> byId.get(e.candidate().anchorId()).startMs())
                .thenComparing(e -> e.candidate().sourceId());
        for (var e : all.stream().filter(e -> e.state()==State.READY).sorted(order).toList())
            buckets.computeIfAbsent(byId.get(e.candidate().anchorId()).startMs()/60000,k -> new ArrayDeque<>()).add(e);
        Map<Long,ArrayDeque<Entry>> interleaved = new TreeMap<>();
        buckets.forEach((minute,items) -> {
            var r=new ArrayDeque<Entry>(); var a=new ArrayDeque<Entry>();
            items.forEach(e -> { if(e.candidate().origin()==Origin.RULE) r.add(e); else a.add(e); });
            var mixed=new ArrayDeque<Entry>();
            while(!r.isEmpty() || !a.isEmpty()) { if(!r.isEmpty()) mixed.add(r.remove()); if(!a.isEmpty()) mixed.add(a.remove()); }
            interleaved.put(minute,mixed);
        });
        Set<Candidate> selected=new HashSet<>(); int remaining=budget;
        while(remaining>0 && interleaved.values().stream().anyMatch(q -> !q.isEmpty()))
            for(var queue:interleaved.values()) if(remaining>0 && !queue.isEmpty()) { selected.add(queue.remove().candidate()); remaining--; }
        var entries=all.stream().sorted(order).map(e -> e.state()!=State.READY || selected.contains(e.candidate()) ? e
                : new Entry(e.candidate(),State.BUDGET_SKIPPED,e.raw(),e.contextLimited())).toList();
        return new Plan(REVISION,hash,duplicates,entries);
    }
}
