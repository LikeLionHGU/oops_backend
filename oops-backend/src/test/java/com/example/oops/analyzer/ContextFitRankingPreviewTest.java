package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ContextFitRankingPreviewTest {
    static ReviewGuidelineLibrary.ContextExample example(String id, String search) {
        return new ReviewGuidelineLibrary.ContextExample(id,"synthetic",List.of("sexual-objectification"),List.of("SPEECH"),
                new ReviewGuidelineLibrary.Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT",List.of("합성 맥락"),List.of(),List.of("초안"),List.of(),List.of()),
                new ReviewGuidelineLibrary.RetrievalView("context-unit-retrieval-1","AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",search,"0".repeat(64)),List.of());
    }
    static List<ReviewInput.Segment> raw(String text) { return List.of(new ReviewInput.Segment("s",SPEECH,0,1000,text,null)); }
    @Test void changesOnlyOneTrailingSlotAndNeverUsesDecisionOrExampleIdToRank() {
        var first=example("a","주제 설명");
        var second=example("b","다른 주제");
        var third=example("c","주제");
        var contrast=example("arbitrary-id","뜻 설명과 직접 요청을 구별");
        var pool=List.of(new ContextExampleSelector.Match(contrast,.01,false,0,List.of("topic")));
        var p=ContextFitRankingPreview.select(pool,List.of(first,second,third),raw("줄임말 뜻을 설명합니다"),3,rows->true);
        assertThat(p.selected()).containsExactly(first,second,contrast);
        assertThat(p.state()).isEqualTo("BOUNDARY_SUPPLEMENTED");
        assertThat(p.removedId()).isEqualTo("c");
        assertThat(ContextFitRankingPreview.select(pool,p.selected(),raw("줄임말 뜻을 설명합니다"),3,rows->true).selected()).isEqualTo(p.selected());
    }
    @Test void noCueNoTopicNoRoomAndNoPoolNeverForceAReference() {
        var topic=example("topic","주제");
        var boundary=example("boundary","뜻 설명과 직접 요청");
        var qualified=List.of(new ContextExampleSelector.Match(boundary,1,false,0,List.of("topic")));
        assertThat(ContextFitRankingPreview.select(qualified,List.of(topic),raw("오늘 날씨 좋네요"),1,rows->true).selected()).containsExactly(topic);
        assertThat(ContextFitRankingPreview.select(List.of(new ContextExampleSelector.Match(boundary,1)),List.of(topic),raw("뜻을 설명합니다"),1,rows->true).selected()).containsExactly(topic);
        assertThat(ContextFitRankingPreview.select(qualified,List.of(topic),raw("뜻을 설명합니다"),1,rows->false).selected()).containsExactly(topic);
        assertThat(ContextFitRankingPreview.select(List.of(),List.of(topic),raw("뜻을 설명합니다"),1,rows->true).selected()).containsExactly(topic);
        assertThat(ContextFitRankingPreview.select(qualified,List.of(),raw("뜻을 설명합니다"),0,rows->true).selected()).isEmpty();
    }
    @Test void doesNotCombineDistantReportedSpeechCues() {
        var raw=List.of(new ReviewInput.Segment("a",SPEECH,0,1000,"과거 사례",null),new ReviewInput.Segment("b",SPEECH,60000,61000,"굉장히 충격받았어요",null));
        assertThat(ContextFitRankingPreview.queryCues(raw)).doesNotContain(ContextFitRankingPreview.Cue.REPORTED);
    }
    @Test void compareSameFrozenPoolWithOriginalBudgetWithoutProviderCalls() throws Exception {
        Path root=Path.of("../datasets/controversy"), preview=root.resolve("experiments/speech-act-mechanism-20261023-verified");
        assumeTrue(Files.exists(preview.resolve("preview-runtime.json")));
        var json=ReviewGuidelineLibrary.JSON;
        var archive=json.readTree(Files.readAllBytes(preview.resolve("preview-runtime.json")));
        var queries=json.readTree(Files.readAllBytes(preview.resolve("queries.json")));
        var examples=new ArrayList<ReviewGuidelineLibrary.ContextExample>();
        for(var e:archive.get("examples")) examples.add(json.treeToValue(e,ReviewGuidelineLibrary.ContextExample.class));
        var rules=new ArrayList<ReviewGuidelineLibrary.Rule>();
        for(var r:archive.get("guidelines")) rules.add(json.treeToValue(r,ReviewGuidelineLibrary.Rule.class));
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,preview.resolve("preview-runtime.json").toAbsolutePath().toUri().toString(),6000,3,3000,"",true);
        library.load();
        var selector=new ContextExampleSelector(examples,rules,StructuredContextSearch.load());
        var patterns=rules.stream().filter(r->r.channels().contains("SPEECH")).map(r->Map.of("id",r.id(),"axis",r.axis(),"condition",r.condition(),"normalContrast",r.normalContrast(),"requiredEvidence",r.requiredEvidence(),"missingContext",r.missingContext())).toList();
        var rows=new ArrayList<Map<String,Object>>();
        for(var q:queries) {
            var raw=new ArrayList<ReviewInput.Segment>();
            for(var s:q.get("raw")) raw.add(new ReviewInput.Segment(s.get("id").asText(),SPEECH,s.get("startMs").asLong(),s.get("endMs").asLong(),s.get("text").asText(),null));
            var baseline=library.select(SPEECH,raw,"context-fit-audit");
            var selected=baseline.trace().exampleIds().stream().map(id->examples.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow()).toList();
            assertThat(payloadSize(patterns,selected)).isEqualTo(baseline.trace().payloadCodePoints());
            var pool=selector.rank(SPEECH,raw,Set.of());
            var result=ContextFitRankingPreview.select(pool,selected,raw,3,items->payloadSize(patterns,items)<=6000 && exampleSize(items)<=3000);
            assertThat(result.selected()).hasSizeLessThanOrEqualTo(3);
            assertThat(payloadSize(patterns,result.selected())).isLessThanOrEqualTo(6000);
            rows.add(Map.of("caseId",q.get("caseId").asText(),"before",baseline.trace().exampleIds(),"after",result.selected().stream().map(ReviewGuidelineLibrary.ContextExample::id).toList(),
                    "queryCues",result.queryCues(),"state",result.state(),"payloadCodePoints",payloadSize(patterns,result.selected())));
        }
        String output=System.getenv("CONTEXT_FIT_AUDIT_OUTPUT");
        if(output!=null) {
            Path path=Path.of(output).toAbsolutePath();
            assertThat(path.getParent().toRealPath()).startsWith(root.resolve("experiments").toRealPath());
            Files.writeString(path,json.writeValueAsString(Map.of("providerCalls",0,"runtimeChanged",false,"judgmentMeasured",false,"testOnlyPrototype",true,"rows",rows)),StandardOpenOption.CREATE_NEW);
        }
    }
    static int exampleSize(List<ReviewGuidelineLibrary.ContextExample> items) { return size(items.stream().map(e->new ReviewGuidelineLibrary.SelectedExample(e.mechanismIds(),e.context())).toList()); }
    static int payloadSize(Object patterns,List<ReviewGuidelineLibrary.ContextExample> items) {
        return size(Map.of("reviewGuidelines",patterns,"referenceContexts",items.stream().map(e->new ReviewGuidelineLibrary.SelectedExample(e.mechanismIds(),e.context())).toList()));
    }
    static int size(Object value) { try { String s=ReviewGuidelineLibrary.JSON.writeValueAsString(value); return s.codePointCount(0,s.length()); } catch(Exception ex) { throw new IllegalArgumentException(ex); } }
}
