package com.example.oops.analyzer;

import java.util.*;

/** Lexical signals in a model's reason, not verified causes or a decision override. */
public record UncertaintyDiagnostic(String basis, List<String> signals, boolean necessityValidated) {
    static UncertaintyDiagnostic from(List<String> reasons) {
        String s = String.join(" ", reasons == null ? List.of() : reasons).toLowerCase(Locale.ROOT);
        List<String> signals = new ArrayList<>();
        if (s.matches("(?s).*(전사.{0,12}(오류|불명확)|불명확.{0,8}(전사|발화|표현)|오인식|깨진|실제.{0,8}표현|transcri.{0,15}(error|unclear)).*"))
            signals.add("TRANSCRIPT_QUALITY");
        if (s.matches("(?s).*(문맥|앞뒤|연속.{0,3}장면|context).*")) signals.add("CONTEXT_REQUEST");
        if (s.matches("(?s).*(화자|원음|억양|말투|speaker|intonation|audio).*"))
            signals.add("SPEAKER_OR_DELIVERY_REQUEST");
        if (signals.isEmpty()) signals.add("UNCLASSIFIED");
        return new UncertaintyDiagnostic("LEXICAL_REASON_SIGNALS_NOT_SEMANTIC_VALIDATION", List.copyOf(signals), false);
    }
}
