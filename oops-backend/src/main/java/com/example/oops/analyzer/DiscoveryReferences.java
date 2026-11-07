package com.example.oops.analyzer;

import java.util.List;

/** Experiment seam: casebook reference IDs chosen outside the engine for one discovery batch. */
@FunctionalInterface
interface DiscoveryReferences {
    List<String> exampleIds(List<ReviewInput.Segment> raw);
}
