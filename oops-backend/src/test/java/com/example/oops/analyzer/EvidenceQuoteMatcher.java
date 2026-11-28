package com.example.oops.analyzer;

import java.text.Normalizer;
import java.util.Locale;

/** Checks whether an LLM's cited excerpt occurs in the selected source line. */
final class EvidenceQuoteMatcher {

    private EvidenceQuoteMatcher() {}

    static boolean matches(String quote, String source) {
        if (quote == null || source == null || quote.isBlank() || source.isBlank()) {
            return false;
        }
        String normalizedQuote = normalize(quote);
        String normalizedSource = normalize(source);
        return normalizedQuote.length() >= 2 && normalizedSource.contains(normalizedQuote);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.KOREAN)
                .replaceAll("[^가-힣a-z0-9]", "");
    }
}
