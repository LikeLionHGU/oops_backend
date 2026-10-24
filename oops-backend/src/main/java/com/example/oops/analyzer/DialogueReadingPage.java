package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.util.*;

/** Lossless reading pages, not inferred utterances, speakers, topics or transcript summaries. */
final class DialogueReadingPage {
    static final String REVISION = "2026-10-25-reading-page-1";
    static final long MAX_SPAN_MS = 120_000, MAX_GAP_MS = 5_000, PREFIX_MS = 10_000;
    static final int MAX_TEXT_CODE_POINTS = 6_000, MAX_INPUT_CODE_POINTS = 24_000;
    static final int MAX_ATOMS = 128, PREFIX_CODE_POINTS = 1_000;
    private DialogueReadingPage() {}

    static List<TextReviewBatchPlanner.Batch> plan(ReviewInput input) {
        var source=input.segments().stream().filter(s -> s.type()==TimelineEventType.SPEECH && s.reviewTarget()).toList();
        List<TextReviewBatchPlanner.Batch> pages=new ArrayList<>();
        int start=0,previousStart=0;
        while(start<source.size()) {
            int left=start;
            if(start>0 && gap(source.get(start-1),source.get(start))<=MAX_GAP_MS) {
                int chars=0,cap=Math.max(1,(start-previousStart)/4);
                while(left>previousStart && start-left<cap) {
                    var next=source.get(left-1);
                    if(gap(next,source.get(left))>MAX_GAP_MS
                            || source.get(start).startMs()-next.startMs()>PREFIX_MS
                            || chars+size(next)>PREFIX_CODE_POINTS) break;
                    chars+=size(next);left--;
                }
            }
            // Context must never make an otherwise admissible primary disappear.
            while(left<start && !fits(source.subList(left,start+1),source.subList(start,start+1)))left++;
            int end=start;
            while(end<source.size()) {
                if(!fits(source.subList(left,end+1),source.subList(start,end+1)))break;
                end++;
            }
            boolean oversized=end==start;
            if(oversized){left=start;end=start+1;}
            boolean limited=left>0 && gap(source.get(left-1),source.get(left))<=MAX_GAP_MS
                    || end<source.size() && gap(source.get(end-1),source.get(end))<=MAX_GAP_MS;
            pages.add(new TextReviewBatchPlanner.Batch(source.subList(start,end),source.subList(left,start),limited,oversized,true));
            previousStart=start;start=end;
        }
        return List.copyOf(pages);
    }

    static List<ReviewUnit> windows(TextReviewBatchPlanner.Batch page) {
        var raw=raw(page); if(!fits(raw,page.primary()) || hasGap(raw))return List.of();
        var ids=raw.stream().map(ReviewInput.Segment::id).toList();
        long end=raw.stream().mapToLong(ReviewInput.Segment::endMs).max().orElseThrow();
        return page.primary().stream().map(s -> new ReviewUnit(s.id(),ids,raw.get(0).startMs(),end,
                page.contextLimited(),ReviewUnit.View.CENTRED)).toList();
    }

    static List<ReviewInput.Segment> raw(TextReviewBatchPlanner.Batch page) {
        List<ReviewInput.Segment> raw=new ArrayList<>(page.context());raw.addAll(page.primary());
        return new ReviewInput(raw).segments();
    }

    static Map<String,Object> metadata() {
        return Map.of("revision",REVISION,"basis","RAW_IDS_NOT_SPEAKER_OR_TOPIC_INFERENCE",
                "maxSpanMs",MAX_SPAN_MS,"maxTextCodePoints",MAX_TEXT_CODE_POINTS,
                "maxCoreInputCodePoints",MAX_INPUT_CODE_POINTS,"maxAtomicSegments",MAX_ATOMS);
    }

    static int inputCodePoints(List<ReviewInput.Segment> raw,List<ReviewInput.Segment> primary) {
        var ids=raw.stream().map(ReviewInput.Segment::id).toList();
        var anchors=primary.stream().map(ReviewInput.Segment::id).toList();
        var window=List.of(Map.of("anchorIds",anchors,"segmentIds",ids));
        // Same fields as the core discovery envelope, including both compatibility aliases.
        var body=Map.of("promptRevision",CandidateReviewEngine.REVISION,"primaryIds",anchors,
                "raw",raw.stream().map(s -> Map.of("id",s.id(),"startMs",s.startMs(),"endMs",s.endMs(),"text",s.text())).toList(),
                "windows",window,"targetWindows",window,"contextLimited",false,"readingPage",metadata());
        String json=ReviewGuidelineLibrary.JSON.writeValueAsString(body);
        return json.codePointCount(0,json.length());
    }

    private static boolean fits(List<ReviewInput.Segment> raw,List<ReviewInput.Segment> primary) {
        return !raw.isEmpty() && !primary.isEmpty() && raw.size()<=MAX_ATOMS && !hasGap(raw)
                && raw.stream().mapToLong(ReviewInput.Segment::endMs).max().orElseThrow()-raw.get(0).startMs()<=MAX_SPAN_MS
                && raw.stream().mapToInt(DialogueReadingPage::size).sum()<=MAX_TEXT_CODE_POINTS
                // Reserve room for composed revision tags; reference-library budgets are separate.
                && inputCodePoints(raw,primary)<=MAX_INPUT_CODE_POINTS-256;
    }
    private static int size(ReviewInput.Segment s){return s.text().codePointCount(0,s.text().length());}
    private static long gap(ReviewInput.Segment a,ReviewInput.Segment b){return Math.max(0,b.startMs()-a.endMs());}
    private static boolean hasGap(List<ReviewInput.Segment> raw){
        if(raw.isEmpty())return false;
        long coveredEnd=raw.get(0).endMs();
        for(int i=1;i<raw.size();i++){
            if(raw.get(i).startMs()-coveredEnd>MAX_GAP_MS)return true;
            coveredEnd=Math.max(coveredEnd,raw.get(i).endMs());
        }
        return false;
    }
}
