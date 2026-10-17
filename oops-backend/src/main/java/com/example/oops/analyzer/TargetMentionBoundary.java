package com.example.oops.analyzer;

import java.util.Set;

/** Literal referent boundaries with a conservative Korean particle allowance, not morphology or entity recognition. */
final class TargetMentionBoundary {
    private static final Set<String> FOCUS=Set.of("은","는","도","만","까지","부터","조차","마저");
    private static final Set<String> PARTICLES=Set.of("은","는","이","가","을","를","의","에","에서","에게","에게서",
            "한테","한테서","께","께서","께서는","로","으로","과","와","하고","도","만","까지","부터","보다","처럼",
            "같이","라고","이라고","라는","이라는","아","야","이랑","랑","이나","나","조차","마저","밖에",
            "에서는","에서도","에서만","에게는","에게도","에게만","한테는","한테도","한테만","으로는","으로도",
            "으로만","로는","로도","로만","와는","과는","까지는","부터는","만은","만도");
    private TargetMentionBoundary() {}
    static boolean matches(String text,String mention) {
        if(text==null || mention==null || mention.isBlank()) return false;
        for(int from=0;from<=text.length()-mention.length();) {
            int at=text.indexOf(mention,from);if(at<0) return false;
            int end=at+mention.length();from=at+1;
            if(at>0 && lexical(text.codePointBefore(at))) continue;
            if(end==text.length() || !lexical(text.codePointAt(end))) return true;
            int tail=end;
            while(tail<text.length() && lexical(text.codePointAt(tail))) tail+=Character.charCount(text.codePointAt(tail));
            String suffix=text.substring(end,tail);
            if(PARTICLES.contains(suffix) || FOCUS.stream().anyMatch(f -> suffix.endsWith(f)
                    && PARTICLES.contains(suffix.substring(0,suffix.length()-f.length())))) return true;
        }
        return false;
    }
    private static boolean lexical(int cp) {
        int type=Character.getType(cp);
        return Character.isLetterOrDigit(cp) || cp=='_' || type==Character.NON_SPACING_MARK
                || type==Character.COMBINING_SPACING_MARK || type==Character.ENCLOSING_MARK;
    }
}
