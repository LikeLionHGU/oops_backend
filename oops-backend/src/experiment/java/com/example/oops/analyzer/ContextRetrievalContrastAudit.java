package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static com.example.oops.domain.TimelineEventType.SPEECH;

/** Free retrieval probes, not model verdicts, discovered candidates or approved controversy labels. */
public final class ContextRetrievalContrastAudit {
    static final Set<String> KINDS=Set.of("NEAR_REVIEW","PARAPHRASE_REVIEW","NEAR_NORMAL","PARAPHRASE_NORMAL");
    record Probe(String id,String pairId,String kind,String expectedExampleId,String manualKey,String anchorId,
                 List<ReviewInput.Segment> raw,SourceRelations roles) {}
    record Plan(String schemaVersion,String status,Boolean humanVerified,Boolean trainingUseAuthorized,List<Probe> probes) {}
    record Row(String probeId,String pairId,String kind,String arm,String inputSha256,String expectedExampleId,
               int expectedTrigramHits,int eligibleFullWindowRank,int selectedRank,String stage,
               double targetRelationComponentBeforeGate,SelectionTrace selection) {}
    record Summary(String kind,String arm,int probes,int eligible,int selected,int firstSelected) {}

    static void validate(Plan p,Archive archive) {
        check(p!=null && "context-retrieval-probes-1".equals(p.schemaVersion()) && "DRAFT".equals(p.status())
                && Boolean.FALSE.equals(p.humanVerified()) && Boolean.FALSE.equals(p.trainingUseAuthorized()),"Draft synthetic probes required");
        check(p.probes()!=null && !p.probes().isEmpty() && p.probes().size()<=100,"Bounded probes required");
        var examples=new HashMap<String,ContextExample>(); archive.examples().forEach(e -> examples.put(e.id(),e));
        Set<String> ids=new HashSet<>(); Map<String,List<Probe>> pairs=new TreeMap<>();
        for(var q:p.probes()) {
            check(q!=null && q.id()!=null && q.id().matches("[a-zA-Z0-9_-]{1,64}") && ids.add(q.id())
                    && q.pairId()!=null && q.pairId().matches("[a-zA-Z0-9_-]{1,64}") && q.kind()!=null && KINDS.contains(q.kind()),"Unique probe identity/kind required");
            var e=examples.get(q.expectedExampleId());
            check(e!=null && e.channels().contains("SPEECH") && e.routingKeys()!=null && e.routingKeys().contains(q.manualKey()),
                    "Existing target and manually supplied catalog key required");
            check(q.raw()!=null && !q.raw().isEmpty() && q.raw().size()<=8
                    && q.raw().stream().allMatch(s -> s!=null && s.type()==SPEECH && s.text().codePointCount(0,s.text().length())<=350),
                    "Bounded unmodified speech probe required");
            var rawIds=q.raw().stream().map(ReviewInput.Segment::id).toList();
            check(new HashSet<>(rawIds).size()==rawIds.size() && q.roles()!=null
                    && q.roles().failure(q.anchorId(),rawIds)==null,"Source-linked manually authored roles required");
            pairs.computeIfAbsent(q.pairId(),key -> new ArrayList<>()).add(q);
        }
        for(var pair:pairs.values()) check(pair.size()==4 && pair.stream().map(Probe::kind).collect(java.util.stream.Collectors.toSet()).equals(KINDS)
                && pair.stream().map(Probe::expectedExampleId).distinct().count()==1
                && pair.stream().map(Probe::manualKey).distinct().count()==1,"Each target needs four frozen contrast kinds");
    }
    static List<Row> evaluate(Plan p,Archive before,Archive after,ReviewGuidelineLibrary base,ReviewGuidelineLibrary preview) throws Exception {
        validate(p,after);
        check("review-guidelines-6".equals(before.schemaVersion()) && "review-guidelines-7".equals(after.schemaVersion())
                && base.contextUnitArchive() && !base.relationArchive() && preview.relationArchive(),"Ready v6/v7 required");
        var withoutProfiles=after.examples().stream().map(e -> new ContextExample(e.id(),e.familyId(),e.mechanismIds(),
                e.channels(),e.context(),e.retrievalView(),e.routingKeys())).toList();
        check(before.examples().equals(withoutProfiles) && before.guidelines().equals(after.guidelines())
                && before.sourceCases().equals(after.sourceCases()),"Only retrieval profile metadata may differ");
        List<Row> rows=new ArrayList<>();
        for(var q:p.probes()) {
            var proposal=new CandidateReviewEngine.Proposal(q.anchorId(),"TARGET_TREATMENT",null,
                    q.raw().stream().map(s -> new CandidateReviewEngine.Quote(s.id(),s.text())).toList(),q.roles());
            var query=ContextRelationRetrieval.from(new CandidateReviewEngine.Candidate(q.id(),proposal,q.raw(),false,false));
            check(query.present(),"Valid role query required");
            // Controlled evaluation-role focus, not a claim that live discovery would produce this candidate.
            var focus=q.raw().stream().filter(s -> q.roles().evaluationIds().contains(s.id())).toList();
            String baselinePrompt=null;
            for(String arm:List.of("V6_LEXICAL","V7_ROLE_ABLATED","V7_ROLES","V7_MANUAL_KEY_AND_ROLES")) {
                boolean baseline=arm.equals("V6_LEXICAL"), roles=arm.equals("V7_ROLES") || arm.equals("V7_MANUAL_KEY_AND_ROLES");
                var library=baseline?base:preview; var archive=baseline?before:after;
                var hints=arm.equals("V7_MANUAL_KEY_AND_ROLES")?Set.of(q.manualKey()):Set.<String>of();
                var selected=library.select(SPEECH,q.raw(),q.id(),focus,hints,roles?query:ContextRelationRetrieval.Query.empty());
                if(baseline) baselinePrompt=selected.prompt();
                if(arm.equals("V7_ROLE_ABLATED")) check(selected.prompt().equals(baselinePrompt),"Role-ablated payload must reproduce v6");
                var target=archive.examples().stream().filter(e -> e.id().equals(q.expectedExampleId())).findFirst().orElseThrow();
                var pool=new ContextExampleSelector(archive.examples(),archive.guidelines()).rank(SPEECH,q.raw(),Set.of(),hints,
                        roles?query:ContextRelationRetrieval.Query.empty());
                int eligible=0;
                for(int i=0;i<pool.size();i++) if(pool.get(i).example().id().equals(target.id())) eligible=i+1;
                int rank=selected.trace().exampleIds().indexOf(target.id())+1;
                var terms=ContextExampleSelector.terms(String.join(" ",q.raw().stream().map(ReviewInput.Segment::text).toList()));
                int hits=(int)ContextExampleSelector.terms(target.retrievalView().text()).stream().filter(terms::contains).count();
                rows.add(new Row(q.id(),q.pairId(),q.kind(),arm,ReferenceOnlyTrial.sha(JSON.writeValueAsString(q).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        target.id(),hits,eligible,rank,rank>0?"SELECTED":eligible>0?"ELIGIBLE_NOT_SELECTED":"NOT_ELIGIBLE",
                        roles?ContextRelationRetrieval.component(target.relationProfile(),query):0,selected.trace()));
            }
        }
        return List.copyOf(rows);
    }
    static List<Summary> summarize(List<Row> rows) {
        var groups=new TreeMap<String,List<Row>>();
        rows.forEach(r -> groups.computeIfAbsent(r.kind()+":"+r.arm(),key -> new ArrayList<>()).add(r));
        return groups.values().stream().map(g -> new Summary(g.get(0).kind(),g.get(0).arm(),g.size(),
                (int)g.stream().filter(r -> r.eligibleFullWindowRank()>0).count(),
                (int)g.stream().filter(r -> r.selectedRank()>0).count(),(int)g.stream().filter(r -> r.selectedRank()==1).count())).toList();
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"Draft probes, v6 runtime, v7 runtime, new private report required");
        Path output=Path.of(args[3]).toAbsolutePath().normalize();
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private report required");
        byte[] plan=OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        byte[] baseBytes=OfflineDecisionContractAudit.bounded(Path.of(args[1])), previewBytes=OfflineDecisionContractAudit.bounded(Path.of(args[2]));
        var rows=evaluate(JSON.readValue(plan,Plan.class),JSON.readValue(baseBytes,Archive.class),JSON.readValue(previewBytes,Archive.class),
                ReferenceOnlyTrial.library(Path.of(args[1])),ReferenceOnlyTrial.library(Path.of(args[2])));
        var report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion","context-retrieval-contrast-audit-1"); report.put("providerCalls",0);
        report.put("labelStatus","SYNTHETIC_AUTHOR_PROPOSALS_NOT_APPROVED_GOLD");
        report.put("rolesStatus","MANUALLY_AUTHORED_NOT_MODEL_PREDICTIONS");
        report.put("normalReferencePresenceIsNotFalsePositive",true); report.put("verdictAccuracyMeasured",false);
        report.put("manualKeyIsOracleControlNotClassifierAccuracy",true);
        report.put("poolRankScope","FULL_WINDOW_NOT_FINAL_CANDIDATE_CONTEXT_INTERLEAVING");
        report.put("planSha256",ReferenceOnlyTrial.sha(plan)); report.put("baselineSha256",ReferenceOnlyTrial.sha(baseBytes));
        report.put("previewSha256",ReferenceOnlyTrial.sha(previewBytes)); report.put("summary",summarize(rows)); report.put("rows",rows);
        Files.writeString(output,JSON.writeValueAsString(report),StandardOpenOption.CREATE_NEW);
        summarize(rows).forEach(s -> System.out.println("CONTRAST "+s.kind()+" "+s.arm()+" eligible="+s.eligible()+"/"+s.probes()
                +" selected="+s.selected()+" first="+s.firstSelected()));
    }
}
