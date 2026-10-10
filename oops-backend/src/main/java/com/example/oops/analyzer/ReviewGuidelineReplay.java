package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline loading/contract audit of saved responses; no Spring, media or API calls. */
public final class ReviewGuidelineReplay {
    private ReviewGuidelineReplay() {}
    public static void main(String[] args) {
        if (args.length != 3) throw new IllegalArgumentException("Three local JSON paths required");
        String stage = "INPUT";
        try {
            var json = ReviewGuidelineLibrary.JSON;
            var snapshot = json.readTree(read(args[0]));
            var report = json.readTree(read(args[1]));
            var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                    Path.of(args[2]).toAbsolutePath().toUri().toString(), 6000);
            library.load();
            List<ReviewInput.Segment> raw = new ArrayList<>();
            int index = 0;
            for (var row : snapshot.get("transcript")) {
                raw.add(new ReviewInput.Segment("stt-replay-" + index++, TimelineEventType.SPEECH,
                        row.get("startMs").asLong(), row.get("endMs").asLong(), row.get("text").asText(), null));
            }
            if (raw.isEmpty() || raw.size() > 100) throw new IllegalArgumentException();
            var input = new ReviewInput(raw);
            stage = "DISCOVERY";
            List<Map<String,Object>> arms = new ArrayList<>();
            for (var arm : report.get("arms")) {
                var discovery = json.readValue(arm.get("calls").get(0).get("output").toString(), CandidateReviewEngine.Discovery.class);
                Map<String,CandidateReviewEngine.Proposal> proposed = new LinkedHashMap<>();
                for (int i = 0; i < discovery.candidates().size(); i++) proposed.put("candidate-" + (i + 1), discovery.candidates().get(i));
                List<Map<String,Object>> assessments = new ArrayList<>();
                for (var node : arm.get("verifications")) {
                    stage = "VERIFICATION";
                    var v = json.readValue(node.toString(), CandidateReviewEngine.Verification.class);
                    var proposal = proposed.get(v.candidateId());
                    if (proposal == null) throw new IllegalArgumentException();
                    var c = new CandidateReviewEngine.Candidate(v.candidateId(), proposal, input.segments(), false, false);
                    var valid = CandidateReviewEngine.validate(c, v.assessment(), input);
                    assessments.add(Map.of("candidateId",v.candidateId(),"anchorId",proposal.anchorId(),
                            "accepted",valid.failureCode() == null,"failureCode",valid.failureCode() == null ? "NONE" : valid.failureCode()));
                }
                arms.add(Map.of("name",arm.get("name").asText(),"assessments",assessments));
            }
            System.out.println(json.writeValueAsString(Map.of("guidelineReference",library.trace(TimelineEventType.SPEECH),
                    "arms",arms,"productionDomainValidation",true,"fullPipelineReplayed",false)));
        } catch (Exception ex) {
            // No private inputs, provider text or file paths in diagnostics.
            throw new IllegalArgumentException("INVALID_LOCAL_REPLAY_" + stage + "_" + ex.getClass().getSimpleName());
        }
    }
    private static byte[] read(String path) throws Exception {
        try (var in = Files.newInputStream(Path.of(path))) {
            byte[] bytes = in.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new IllegalArgumentException();
            return bytes;
        }
    }
}
