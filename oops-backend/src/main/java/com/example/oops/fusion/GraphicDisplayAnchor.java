package com.example.oops.fusion;

import com.example.oops.domain.FindingSupport;
import java.util.*;
import java.util.regex.Pattern;

/** Presentation hint only after validated reciprocal graphic-pair fusion; never a risk detector.
 * A deliberately narrow Korean surface rule. Ambiguous/unsupported wording keeps the old display.
 */
final class GraphicDisplayAnchor {
    private GraphicDisplayAnchor() {}
    private static final Pattern DIRECT_IMAGE = Pattern.compile(
            "(?<![가-힣a-zA-Z0-9])(?:살점|피부|신체|몸통|머리|살|팔|다리|목)"
                    + "(?:을|를)?\\s*(?:뜯|찢|도려|절단|자르|잘라|베어|씹|먹는|먹어)");

    static Optional<String> choose(List<FindingSupport> supports) {
        Set<String> matches = new LinkedHashSet<>();
        for (var support : supports) for (var quote : support.quotes()) {
            if ("PRIMARY".equals(quote.role()) && quote.segmentId().equals(support.anchorId())
                    && quote.quote() != null && DIRECT_IMAGE.matcher(quote.quote()).find()) matches.add(quote.segmentId());
        }
        return matches.size() == 1 ? Optional.of(matches.iterator().next()) : Optional.empty();
    }
}
