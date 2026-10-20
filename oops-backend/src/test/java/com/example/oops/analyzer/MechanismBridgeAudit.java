package com.example.oops.analyzer;

import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.util.*;

/** Saved-plan request preparation only. Never sends requests or synthesizes model responses. */
public final class MechanismBridgeAudit {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Saved raw plan and local runtime required");
        Path path=Path.of(args[0]),runtime=Path.of(args[1]);
        if(Files.size(path)>8_000_000) throw new IllegalArgumentException("Input too large");
        var json=ReviewGuidelineLibrary.JSON; String saved=Files.readString(path);
        var root=json.readTree(saved); var cases=root.has("conditions")?root.path("conditions"):root.path("cases");
        if(!cases.isArray() || cases.isEmpty() || cases.size()>100) throw new IllegalArgumentException("Saved cases required");
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,runtime.toAbsolutePath().toUri().toString(),6000,3,3000,"",true);
        library.load();
        if(!"READY_WORKING_REFERENCE".equals(library.trace(com.example.oops.domain.TimelineEventType.SPEECH).state()))
            throw new IllegalArgumentException("Runtime unavailable");
        List<Object> results=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(var item:cases) {
            String id=item.path("id").asText();
            if(id.isBlank() || !ids.add(id) || !item.path("raw").isArray() || item.path("raw").isEmpty())
                throw new IllegalArgumentException("Unique case ID/nonempty raw required");
            List<ReviewInput.Segment> raw=json.convertValue(item.path("raw"),json.getTypeFactory().constructCollectionType(List.class,ReviewInput.Segment.class));
            var input=new ReviewInput(raw); var plan=CandidateUnionShadow.plan(input,List.of(),24);
            var prepared=MechanismVerificationBridge.prepareRules(input,plan,library,0);
            results.add(Map.of("id",id,"inputSha256",plan.inputSha256(),"aiCandidatesSupplied",0,
                    "entries",plan.entries().stream().map(e -> Map.of("anchorId",e.candidate().anchorId(),"state",e.state())).toList(),
                    "prepared",prepared.stream().map(p -> Map.of("anchorId",p.candidate().proposal().anchorId(),
                        "allowedSegments",p.candidate().raw().size(),"requestSha256",ReviewGuidelineLibrary.retrievalHash(p.user()),
                        "referenceSha256",p.referenceSha256(),"referenceExampleIds",p.referenceTrace().exampleIds(),
                        "hypothesisFieldPresent",json.readTree(p.user()).path("candidates").get(0).has("hypothesisNotEvidence"))).toList()));
        }
        System.out.println(json.writeValueAsString(Map.of("bridgeRevision",MechanismVerificationBridge.REVISION,"providerCalls",0,
                "referenceConfig",Map.of("maxExamples",3,"totalCodePoints",6000,"exampleCodePoints",3000,"structuredSearch",true),
                "savedPlanSha256",ReviewGuidelineLibrary.retrievalHash(saved),"results",results)));
    }
}
