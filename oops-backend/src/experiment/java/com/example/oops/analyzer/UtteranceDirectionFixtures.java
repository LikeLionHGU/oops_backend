package com.example.oops.analyzer;

import java.util.List;

final class UtteranceDirectionFixtures {
    private UtteranceDirectionFixtures() {}
    record Boundary(String id,String relationDraft,String unsupportedInference,List<String> speech) {}
    record Suite(String schemaVersion,String labelStatus,List<Boundary> cases) {}
}
