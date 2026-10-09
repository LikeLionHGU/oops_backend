package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline retrieval inspection only. Does not start Spring, run STT or call an AI provider. */
public final class ReviewCaseReplay {
    private ReviewCaseReplay() {}
    public record Input(String familyId, List<String> speech) {}
    public record Output(String sourceFingerprint, ReviewCaseLibrary.Trace retrieval) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ReviewCaseReplay <input.json> <archive.json>");
        Input input;
        try (var stream = Files.newInputStream(Path.of(args[0]))) {
            byte[] bytes = stream.readNBytes(ReviewCaseLibrary.MAX_BYTES + 1);
            if (bytes.length > ReviewCaseLibrary.MAX_BYTES) throw new IllegalArgumentException("Replay input too large");
            input = ReviewCaseLibrary.JSON.readValue(bytes, Input.class);
        }
        var library = new ReviewCaseLibrary(new DefaultResourceLoader(), true,
                Path.of(args[1]).toAbsolutePath().toUri().toString(), 2, 2400, input == null ? "" : input.familyId());
        library.load();
        System.out.println(ReviewCaseLibrary.JSON.writeValueAsString(inspect(input, library)));
    }

    static Output inspect(Input input, ReviewCaseLibrary library) {
        if (input == null || input.familyId() == null || !input.familyId().matches("[a-zA-Z0-9_-]{1,64}")
                || input.speech() == null || input.speech().isEmpty() || input.speech().size() > 5000
                || input.speech().stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 6000))
            throw new IllegalArgumentException("Replay requires a family ID and bounded nonblank speech lines");
        var raw = new ArrayList<ReviewInput.Segment>();
        for (int i = 0; i < input.speech().size(); i++) raw.add(new ReviewInput.Segment("replay-" + i,
                TimelineEventType.SPEECH, i * 2000L, i * 2000L + 1500, input.speech().get(i), null));
        String hash = ReviewCaseLibrary.fingerprint(raw);
        var selected = library.select(raw, hash, input.familyId());
        return new Output(hash, selected.trace());
    }
}
