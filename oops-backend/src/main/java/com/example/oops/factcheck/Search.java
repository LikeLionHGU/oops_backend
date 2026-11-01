package com.example.oops.factcheck;

import java.util.List;

/**
 * 사실대조 검색 공통 타입. (redesign-v3, docs/redesign-v3.md 6절)
 *
 * 검색 실패·결과 없음·키 없음을 빈 목록 하나로 뭉치지 않는다. "검색했는데 없음"과
 * "검색을 못 함"은 다른 결과다.
 */
public final class Search {

    private Search() {
    }

    /** 검색 결과 1건 */
    public record Item(String title, String snippet, String date, String url, String publisher) {}

    public enum Status { OK, EMPTY, FAILED, DISABLED }

    public record Outcome(String provider, String query, Status status, List<Item> items, String error) {
        public Outcome {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static Outcome disabled(String provider, String query) {
            return new Outcome(provider, query, Status.DISABLED, List.of(), null);
        }

        public static Outcome failed(String provider, String query, String error) {
            return new Outcome(provider, query, Status.FAILED, List.of(), error);
        }

        public static Outcome of(String provider, String query, List<Item> items) {
            return new Outcome(provider, query, items.isEmpty() ? Status.EMPTY : Status.OK, items, null);
        }

        /** 검색 자체가 되지 않았다(키 없음·장애). 결과 없음과 다르다. */
        public boolean unavailable() {
            return status == Status.FAILED || status == Status.DISABLED;
        }
    }

    /** 검색 API 하나 */
    public interface Provider {
        String name();

        boolean isEnabled();

        Outcome search(String query, int max);
    }

    /** 자료 본문을 AI 에게 넘길 길이로 자른다. 줄바꿈과 겹친 공백은 한 칸으로. */
    static String clip(String text, int limit) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
