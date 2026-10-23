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
    @Test void remoteCueDoesNotSupplementCandidateButNearbyCueDoes() {
        var lead=example("lead","주제");
        var tail=example("tail","주제");
        var boundary=example("boundary","뜻 설명과 직접 요청");
        var pool=List.of(new ContextExampleSelector.Match(boundary,1,false,0,List.of("topic")));
        var anchor=new ReviewInput.Segment("a",SPEECH,0,1000,"그 표현을 이야기합니다",null);
        var remote=new ReviewInput.Segment("b",SPEECH,60000,61000,"줄임말 뜻을 설명합니다",null);
        var remotePlan=ContextFitRankingPreview.select(pool,List.of(lead,tail),List.of(anchor,remote),List.of(anchor),2,rows->true);
        assertThat(remotePlan.selected()).containsExactly(lead,tail);
        assertThat(remotePlan.state()).isEqualTo("NO_DISCOURSE_CUE");
        var nearby=new ReviewInput.Segment("b",SPEECH,2000,3000,remote.text(),null);
        assertThat(ContextFitRankingPreview.select(pool,List.of(lead,tail),List.of(anchor,nearby),List.of(anchor),2,rows->true)
                .selected()).containsExactly(lead,boundary);
    }
    @Test void focusTextCannotInjectCueAndUnknownFocusDoesNotExpandToFullRaw() {
        var lead=example("lead","주제");
        var boundary=example("boundary","뜻 설명과 직접 요청");
        var pool=List.of(new ContextExampleSelector.Match(boundary,1,false,0,List.of("topic")));
        var fake=new ReviewInput.Segment("s",SPEECH,0,1000,"줄임말 뜻",null);
        assertThat(ContextFitRankingPreview.select(pool,List.of(lead),raw("오늘 이야기합니다"),List.of(fake),2,rows->true)
                .state()).isEqualTo("NO_DISCOURSE_CUE");
        var unknown=new ReviewInput.Segment("unknown",SPEECH,0,1000,"뜻",null);
        assertThat(ContextFitRankingPreview.select(pool,List.of(lead),raw("뜻을 설명합니다"),List.of(unknown),2,rows->true)
                .state()).isEqualTo("FOCUS_NOT_IN_RAW");
    }
    @Test void singleSlotNeverEvictsLeadingReferenceButEmptySlotCanBeFilled() {
        var lead=example("lead","주제");
        var boundary=example("boundary","뜻 설명과 직접 요청");
        var pool=List.of(new ContextExampleSelector.Match(boundary,1,false,0,List.of("topic")));
        assertThat(ContextFitRankingPreview.select(pool,List.of(lead),raw("뜻을 설명합니다"),1,rows->true)
                .selected()).containsExactly(lead);
        assertThat(ContextFitRankingPreview.select(pool,List.of(),raw("뜻을 설명합니다"),1,rows->true)
                .selected()).containsExactly(boundary);
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
    @Test void diagnoseFrozenQueriesAndCompareTopicFocusedSearchMetadata(
            @org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        var root=Path.of("../datasets/controversy/experiments/speech-act-mechanism-20261023-verified");
        assumeTrue(Files.exists(root.resolve("preview-runtime.json")));
        var json=ReviewGuidelineLibrary.JSON;
        var original=json.readTree(Files.readAllBytes(root.resolve("preview-runtime.json")));
        var revised=json.readTree(json.writeValueAsBytes(original));
        String target="speech-act-dev#reported-vs-enacted";
        String search="성적 사생활 속옷 정보 질문. 줄임말 뜻을 물음 의미 설명 답변 정의. "
                +"사적 질문을 비판적으로 인용해 소개하는 전달과 현재 상대에게 답변 공개를 요청하는 실행을 구별. 거절 이후 중단.";
        int changes=0;
        for(var e:revised.get("examples")) if(e.get("id").asText().equals(target)) {
            var view=(tools.jackson.databind.node.ObjectNode)e.get("retrievalView");
            view.put("text",search);
            view.put("sha256",ReviewGuidelineLibrary.retrievalHash(search));
            changes++;
        }
        assertThat(changes).isEqualTo(1);
        for(int i=0;i<original.get("examples").size();i++) {
            var before=json.readTree(original.get("examples").get(i).toString());
            var after=json.readTree(revised.get("examples").get(i).toString());
            ((tools.jackson.databind.node.ObjectNode)before).remove("retrievalView");
            ((tools.jackson.databind.node.ObjectNode)after).remove("retrievalView");
            assertThat(after).isEqualTo(before);
        }
        var preview=temporary.resolve("preview.json");
        Files.writeString(preview,json.writeValueAsString(revised));
        var queries=json.readTree(Files.readAllBytes(root.resolve("queries.json")));
        for(var variant:List.of(root.resolve("preview-runtime.json"),preview)) {
            var archive=variant.equals(preview)?revised:original;
            var assets=new ArrayList<ReviewGuidelineLibrary.ContextExample>();
            for(var e:archive.get("examples")) assets.add(json.treeToValue(e,ReviewGuidelineLibrary.ContextExample.class));
            var rules=new ArrayList<ReviewGuidelineLibrary.Rule>();
            for(var r:archive.get("guidelines")) rules.add(json.treeToValue(r,ReviewGuidelineLibrary.Rule.class));
            var selector=new ContextExampleSelector(assets,rules,StructuredContextSearch.load());
            var patterns=rules.stream().filter(r->r.channels().contains("SPEECH")).map(r->Map.of("id",r.id(),"axis",r.axis(),"condition",r.condition(),"normalContrast",r.normalContrast(),"requiredEvidence",r.requiredEvidence(),"missingContext",r.missingContext())).toList();
            var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,variant.toAbsolutePath().toUri().toString(),6000,3,3000,"",true);
            library.load();
            for(var q:queries) {
                var input=new ArrayList<ReviewInput.Segment>();
                for(var s:q.get("raw")) input.add(new ReviewInput.Segment(s.get("id").asText(),SPEECH,s.get("startMs").asLong(),s.get("endMs").asLong(),s.get("text").asText(),null));
                String expected=q.get("caseId").asText().equals("mimiminu-dev#victim-quotation")?"mimiminu-dev#victim-quotation":target;
                var pool=selector.rank(SPEECH,input,Set.of());
                var selected=library.select(SPEECH,input,"gap-audit").trace().exampleIds();
                var stage=ContextRetrievalGapAudit.diagnose(expected,"SPEECH",Set.of(),assets,pool,selected);
                assertThat(stage).isEqualTo(q.get("caseId").asText().contains("critical-quotation") || expected.equals("mimiminu-dev#victim-quotation")
                        ?ContextRetrievalGapAudit.Stage.SELECTED:ContextRetrievalGapAudit.Stage.RETRIEVED_NOT_SELECTED);
                var chosen=selected.stream().map(id->assets.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow()).toList();
                var trial=ContextFitRankingPreview.select(pool,chosen,input,3,
                        items->exampleSize(items)<=3000 && payloadSize(patterns,items)<=6000);
                assertThat(trial.selected().stream().map(ReviewGuidelineLibrary.ContextExample::id)).contains(expected);
                var replacement=new ArrayList<>(chosen);
                if(!selected.contains(expected)) {
                    if(replacement.size()==3) replacement.remove(2);
                    replacement.add(assets.stream().filter(e->e.id().equals(expected)).findFirst().orElseThrow());
                }
                boolean fits=exampleSize(replacement)<=3000 && payloadSize(patterns,replacement)<=6000;
                assertThat(fits).isTrue();
                System.out.println("GAP_AUDIT "+(variant.equals(preview)?"focused":"original")+" "+q.get("caseId").asText()+" "+stage
                        +" poolRank="+(pool.stream().map(m->m.example().id()).toList().indexOf(expected)+1)+" replacementFits="+fits);
                assertThat(assets).hasSize(44);
            }
        }
    }
    @Test void auditOtherTopicsWithRealRetrievalAssetsWithoutAssigningVerdicts() throws Exception {
        var path=Path.of("../datasets/controversy/experiments/speech-act-mechanism-20261023-verified/preview-runtime.json");
        assumeTrue(Files.exists(path));
        var archive=ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(path));
        var examples=new ArrayList<ReviewGuidelineLibrary.ContextExample>();
        for(var e:archive.get("examples")) examples.add(ReviewGuidelineLibrary.JSON.treeToValue(e,ReviewGuidelineLibrary.ContextExample.class));
        var rules=new ArrayList<ReviewGuidelineLibrary.Rule>();
        for(var r:archive.get("guidelines")) rules.add(ReviewGuidelineLibrary.JSON.treeToValue(r,ReviewGuidelineLibrary.Rule.class));
        var selector=new ContextExampleSelector(examples,rules,StructuredContextSearch.load());
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,path.toAbsolutePath().toUri().toString(),6000,3,3000,"",true);
        library.load();
        var queries=List.of(
                "학원 선생님이 낮은 반 학생에게 했던 모욕을 인용합니다. 그 말을 듣고 충격받았어요.",
                "지역에 음식점이 없어서 집에서 만들어 먹었어요. 이 메뉴의 뜻을 설명합니다.",
                "이 음식은 할머니의 살을 뜯는 것 같다는 신체 비유입니다. 표현의 의미를 설명합니다.",
                "플래너 사용법과 공부 계획의 의미를 설명합니다.",
                "그 모욕은 제 피해 경험입니다. 과거 말을 인용해서 설명합니다.",
                "지도에서 범례의 뜻을 알려 주세요. 이 기호는 주차장이에요.");
        var patterns=rules.stream().filter(r->r.channels().contains("SPEECH")).map(r->Map.of("id",r.id(),"axis",r.axis(),"condition",r.condition(),"normalContrast",r.normalContrast(),"requiredEvidence",r.requiredEvidence(),"missingContext",r.missingContext())).toList();
        for(int i=0;i<queries.size();i++) {
            var input=raw(queries.get(i));
            var baseline=library.select(SPEECH,input,"cross-topic-"+i);
            var before=baseline.trace().exampleIds().stream().map(id->examples.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow()).toList();
            var pool=selector.rank(SPEECH,input,Set.of());
            var result=ContextFitRankingPreview.select(pool,before,input,input,3,
                    items->exampleSize(items)<=3000 && payloadSize(patterns,items)<=6000);
            assertThat(result.selected()).hasSizeLessThanOrEqualTo(3);
            assertThat(payloadSize(patterns,result.selected())).isLessThanOrEqualTo(6000);
            // Retrieval-only guard, not a PASS/REVIEW label for these authored inputs.
            assertThat(result.addedId()).isNull();
            assertThat(result.selected()).containsExactlyElementsOf(before);
            if(!before.isEmpty()) assertThat(result.selected().get(0)).isEqualTo(before.get(0));
            if(result.addedId()!=null) assertThat(pool.stream().map(m->m.example().id())).contains(result.addedId());
            System.out.println("CROSS_TOPIC " + i + " " + result.state()+" added="+result.addedId()+" before="+baseline.trace().exampleIds());
        }
    }
    static int payloadSize(Object patterns,List<ReviewGuidelineLibrary.ContextExample> items) {
        return size(Map.of("reviewGuidelines",patterns,"referenceContexts",items.stream().map(e->new ReviewGuidelineLibrary.SelectedExample(e.mechanismIds(),e.context())).toList()));
    }
    static int size(Object value) { try { String s=ReviewGuidelineLibrary.JSON.writeValueAsString(value); return s.codePointCount(0,s.length()); } catch(Exception ex) { throw new IllegalArgumentException(ex); } }
}
