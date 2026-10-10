package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.genre.GenreDetector;
import com.example.oops.lexicon.ContextValidator;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Compiled prompt/document agreement only; not a real-model accuracy evaluation. */
class PromptSnapshotTest {
    private String constant(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test void allNineDocumentedSystemsMatchActualCompositionIncludingClientInstruction() throws Exception {
        String speech = constant(SpeechReviewAnalyzer.class, "SYSTEM_PROMPT")
                + "\n" + ContextualComparisonPolicy.PROMPT;
        List<String> systems = List.of(
                speech + "\n" + TextReviewEngine.CONTRACT,
                speech + "\n" + TextReviewEngine.dialogueEvidenceContract() + "\n" + TextReviewEngine.DIALOGUE_CONTRACT,
                constant(ScreenTextReviewAnalyzer.class, "SYSTEM_PROMPT") + "\n" + TextReviewEngine.CONTRACT,
                constant(ContextValidator.class, "SYSTEM_PROMPT"),
                constant(EntityCheckAnalyzer.class, "EXTRACT_PROMPT"),
                constant(EntityCheckAnalyzer.class, "VERIFY_PROMPT"),
                constant(ContextCheckAnalyzer.class, "EXTRACT_PROMPT"),
                constant(ContextCheckAnalyzer.class, "JUDGE_PROMPT"),
                constant(GenreDetector.class, "SYSTEM_PROMPT"));
        String document = Files.readString(Path.of("../docs/현재-AI-요청별-프롬프트-전문.md"))
                + "\n" + Files.readString(Path.of("../docs/사례-검색-프롬프트-전문.md"));
        for (int i = 0; i < systems.size(); i++) {
            String actual = (systems.get(i) + "\n" + OpenAiClient.JSON_OUTPUT_INSTRUCTION).stripTrailing();
            assertThat(document).as("actual system prompt %s", i + 1)
                    .contains("```text\n" + actual + "\n```");
        }
        assertThat(document).contains("```text\n" + CandidateReviewEngine.ID_REPAIR_PROMPT.stripTrailing() + "\n```");
        assertThat(document).contains("```text\n" + CandidateReviewEngine.TARGET_REPAIR_PROMPT.stripTrailing() + "\n```");
        for (String system : List.of(CandidateReviewEngine.DISCOVERY_PROMPT, CandidateReviewEngine.VERIFICATION_PROMPT,
                CandidateReviewEngine.DISCOVERY_REPAIR_PROMPT,
                VisualContextReviewer.PROMPT,
                CandidateReviewEngine.DISCOVERY_PROMPT + "\n" + CandidateReviewEngine.CASE_REFERENCE_CONTRACT)) {
            assertThat(document).contains("```text\n" + (system + "\n" + OpenAiClient.JSON_OUTPUT_INSTRUCTION).stripTrailing() + "\n```");
        }
    }
}
