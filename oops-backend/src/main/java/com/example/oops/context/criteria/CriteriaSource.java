package com.example.oops.context.criteria;

/**
 * 맥락 확인 기준을 읽는 곳. (redesign-v3, docs/redesign-v3.md 8절)
 *
 * 지금은 {@link JsonCriteriaSource}(저장소 안 JSON 파일)를 쓴다.
 * 서버 Neo4j 가 준비되면 이 인터페이스를 구현한 Neo4j 버전으로 바꾸고, 분석 코드는 그대로 둔다.
 *
 * 규칙:
 * - 분석 시작 때 한 번 호출해 받은 스냅숏을 그 실행 동안 고정해서 쓴다.
 * - 누락·중복·버전 불일치가 있으면 {@link CriteriaLoadException} 을 던진다.
 *   빈 결과나 일부만 돌려주지 않는다. 기준을 못 읽은 것을 "문제없음"으로 바꾸면 안 되기 때문이다.
 */
public interface CriteriaSource {

    CriteriaModel.CriteriaSnapshot load();
}
