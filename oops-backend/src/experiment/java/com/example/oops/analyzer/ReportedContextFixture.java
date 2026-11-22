package com.example.oops.analyzer;

import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Research-only archive copy; never publishes the runtime source. */
final class ReportedContextFixture {
    private ReportedContextFixture() {}
    static Archive corrected(Archive archive,tools.jackson.databind.JsonNode p){
        var details=p.path("proposal");String id=p.path("statementId").asString();
        var examples=new ArrayList<ContextExample>();int changed=0;
        for(var ex:archive.examples()){
            if(!id.equals(ex.id())){examples.add(ex);continue;}
            var context=new Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT",
                    List.of(details.path("reportedContext").path("summary").asString()),
                    List.of(details.path("reviewHypothesis").asString()),
                    java.util.stream.StreamSupport.stream(details.path("unknowns").spliterator(),false).map(x->x.asString()).toList(),
                    List.of(),List.of(details.path("normalContrast").asString()));
            examples.add(new ContextExample(ex.id(),ex.familyId(),ex.mechanismIds(),ex.channels(),context,ex.retrievalView(),ex.routingKeys(),ex.relationProfile(),ex.sourceGroupId()));changed++;
        }
        assertThat(changed).isOne();
        var candidate=new Archive(archive.schemaVersion(),archive.version(),archive.status(),archive.humanValidated(),archive.usage(),
                archive.sourceBundleSha256(),archive.refreshOrDeleteBy(),archive.sourceCases(),archive.guidelines(),archive.contextDictionarySha256(),examples);
        assertThat(ReviewGuidelineLibrary.valid(candidate)).isTrue();return candidate;
    }
}
