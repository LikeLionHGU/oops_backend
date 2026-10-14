#!/usr/bin/env python3
"""incidents(사건=파일 하나) <-> 레거시(cards + plan 3개) 무손실 왕복 변환 — 전략 A.

목적(마이그레이션 2단계 동치 증명):
  신규 구조로 바꿔도 판정 동작(runtime.json)이 안 바뀜을 증명한다.
  compile 로직은 건드리지 않는다. incidents 파일이 레거시 입력 조각을 '무손실'로 담고,
  복원하면 원본과 '바이트 동일'(파싱 동일)이면 — 같은 compile 함수에 같은 입력이 들어가므로
  runtime.json 동치가 자동으로 보장된다.

전략 A의 핵심: incidents 파일은 원본 card 객체를 '변형 없이' 통째로 보존한다.
  메커니즘/topicTags/documents 등 사건 메타만 사건 레벨로 끌어올려 재조직한다.
  따라서 복원은 '역조직'일 뿐이고 어떤 필드도 생성·추측·손실되지 않는다.

읽기 전용. --write 없이는 아무 파일도 만들지 않는다. 레거시 컴파일러는 건드리지 않는다.
"""
import argparse
import json
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[1] / "datasets/controversy"


def load(root: Path, rel: str):
    return json.loads((root / rel).read_text(encoding="utf-8"))


def to_incident(family_id, cards_incident, gplan, dplan, catalog):
    """레거시 조각들 -> 사건 하나(incident-2). 원본 카드는 변형 없이 보존."""
    cat_entry = next(i for i in catalog["incidents"] if i["familyId"] == family_id)
    # 메커니즘 역매핑: gplan(mechanism -> sourceCaseIds)에서 caseId별 mechanismIds 복원.
    mech_by_case = {}
    for m in gplan["mechanisms"]:
        mid = m["id"]
        for cid in m.get("sourceCaseIds", []):
            mech_by_case.setdefault(cid, []).append(mid)

    statements = []
    for card in cards_incident["cards"]:
        cid = card["id"]
        dcfg = dplan["cards"].get(cid, {})
        statements.append({
            "id": cid,                                   # 레거시 caseId 를 그대로 승계
            "mechanismIds": mech_by_case.get(cid, []),   # 배열(확정 §6) — 조회 편의용 승격 사본
            "topicTags": dcfg.get("topicTags", []),      # 조회 편의용 승격 사본
            # 원본 dplan 카드 객체를 통째로 보존 — 선택적 키(sourceOriginGroups 등)까지 무손실.
            "legacyDictionaryCard": dcfg,
            # 원본 card 객체를 통째로 보존 — 무손실의 핵심. 어떤 필드도 버리지 않는다.
            "legacyCard": card,
        })
        if "criticismHypotheses" in dcfg:
            statements[-1]["criticismHypotheses"] = dcfg["criticismHypotheses"]
    return {
        "schemaVersion": "incident-2",
        "incidentId": family_id,                         # §6 결정5: familyId 그대로 승격
        "status": "UNREVIEWED_DRAFT",
        "humanApproved": False,
        "runtimeEligible": False,
        "trainingUseAuthorized": False,
        "split": cards_incident["split"],
        "catalogMeta": {k: cat_entry[k] for k in cat_entry},  # catalog 항목 통째 보존
        "statements": statements,
    }


def from_incident(incident):
    """사건 하나(incident-2) -> 레거시 조각 복원. 역조직일 뿐, 생성 없음.

    반환: (cards_incident, gplan_fragments, dplan_cards, catalog_entry)
    """
    cards_incident = {
        "familyId": incident["incidentId"],
        "split": incident["split"],
        "cards": [s["legacyCard"] for s in incident["statements"]],
    }
    # dplan 카드 복원 — 보존한 원본 객체를 그대로 반환(무손실).
    import copy
    from review_context_pilot import require
    dplan_cards = {s["id"]: copy.deepcopy(s["legacyDictionaryCard"]) for s in incident["statements"]}
    for s in incident["statements"]:
        if "criticismHypotheses" in s:
            cfg = dplan_cards[s["id"]]
            require("criticismHypotheses" not in cfg or cfg["criticismHypotheses"] == s["criticismHypotheses"],
                    "CRITICISM_HYPOTHESES_CONFLICT")
            cfg["criticismHypotheses"] = copy.deepcopy(s["criticismHypotheses"])
    # gplan 역매핑 복원: mechanism -> [caseId...] (사건 내부 조각만)
    gplan_fragment = {}
    for s in incident["statements"]:
        for mid in s["mechanismIds"]:
            gplan_fragment.setdefault(mid, []).append(s["id"])
    catalog_entry = incident["catalogMeta"]
    return cards_incident, gplan_fragment, dplan_cards, catalog_entry


def emit_incidents(root: Path):
    """6사건을 incidents/{id}.json 로 디스크에 쓰고, 쓴 파일로 재조립해 동치를 재검증한다.

    datasets 아래라 Git 제외 로컬 생성물. 쓰기 후 '파일에서 다시 읽어' 재조립→runtime 동치가
    녹색이어야 성공으로 본다(쓰기 과정에서의 손실까지 잡는다).
    """
    import json as _json
    cards = load(root, "drafts/cards.json")
    gplan = load(root, "guidelines/plan.json")
    dplan = load(root, "research/context-dictionary-plan.json")
    catalog = load(root, "catalog.json")

    out_dir = root / "incidents"
    out_dir.mkdir(exist_ok=True)
    written = []
    for ci in cards["incidents"]:
        inc = to_incident(ci["familyId"], ci, gplan, dplan, catalog)
        path = out_dir / (inc["incidentId"] + ".json")
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(_json.dumps(inc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(path)   # 원자적 교체
        written.append(path.name)

    # 쓴 파일에서 다시 읽어 재조립 → runtime 동치 재검증.
    from review_context_pilot import parse, read_bytes
    from build_review_guidelines import build_bundle, compile_guidelines
    from build_context_dictionary import compile_dictionary
    from review_dataset_catalog import inside

    incidents = [load(root, "incidents/" + name) for name in written]
    from incident_build_definition import extract
    r_cards, r_gplan, r_dplan, r_catalog = reassemble(root, incidents, extract(cards, gplan, dplan, catalog))
    checks = {
        "cards_json": r_cards == cards,
        "gplan_json": r_gplan == gplan,
        "dplan_json": r_dplan == dplan,
        "catalog_json": r_catalog == catalog,
    }
    private_root = root.parent.parent / "uploads/comment-collections"
    reaction_plan = load(root, "research/reaction-classification-plan.json")
    expected = build_bundle(root, private_root, reaction_plan)
    checks["bundle_matches_source"] = (r_cards == expected)
    snapshots = {}
    for cfg in r_dplan["cards"].values():
        for d in cfg.get("documents", []):
            snapshots[d["snapshot"]] = read_bytes(inside(root, d["snapshot"]))
    sb = read_bytes(root / "drafts/cards.json")
    dictionary = compile_dictionary(r_cards, r_dplan, r_gplan, sb, snapshots)
    result = compile_guidelines(r_cards, r_gplan, sb, dictionary)
    saved = parse(read_bytes(root / "guidelines/runtime.json"))
    checks["runtime_byte_equivalent_except_ts"] = (_strip_ts(result) == _strip_ts(saved))
    return written, checks


def reassemble(root: Path, incidents, definition=None):
    """6사건 incident 목록 -> 완전한 레거시 입력(cards/gplan/dplan/catalog) 재조립.

    명시적 definitions/build.json의 공통 메타와 순서를 읽고 사건 조각만 결합한다.
    레거시 파일을 자동으로 읽거나 정의를 추측 생성하지 않는다.
    """
    import copy
    from incident_build_definition import load as load_definition
    definition = copy.deepcopy(definition if definition is not None else load_definition(root))
    base_cards = definition["cards"]
    base_gplan = definition["guidelines"]
    base_dplan = definition["dictionary"]
    base_catalog = definition["catalog"]

    by_family = {}
    seen_statements = set()
    for inc in incidents:
        from review_context_pilot import require
        require(inc["incidentId"] not in by_family, "DUPLICATE_INCIDENT")
        for s in inc["statements"]:
            require(s["id"] not in seen_statements and s["id"] == s["legacyCard"]["id"]
                    and s["legacyCard"]["familyId"] == inc["incidentId"], "STATEMENT_IDENTITY_INVALID")
            require(s["mechanismIds"] and len(s["mechanismIds"]) == len(set(s["mechanismIds"])), "STATEMENT_MECHANISMS_REQUIRED")
            seen_statements.add(s["id"])
        cards_inc, gplan_frag, dplan_cards, cat_entry = from_incident(inc)
        by_family[inc["incidentId"]] = (cards_inc, gplan_frag, dplan_cards, cat_entry)

    # 사건 처리 순서: 원본에 있던 familyId 는 원본 순서로 먼저, incidents 에만 있는
    # 새 사건은 incidents 순서로 뒤에 붙인다. 기존 사건만 있으면 결과가 원본과 동일(동치 보존),
    # 새 사건(기사-only 포함)은 cards/catalog/dplan/gplan 모두에 일관되게 추가된다(결함 B 제거).
    orig_families = [f for f in definition["incidentOrder"] if f in by_family]
    orig_set = set(orig_families)
    new_families = sorted(f for f in by_family if f not in orig_set)
    ordered_families = orig_families + new_families

    # cards.incidents 재조립
    base_cards["incidents"] = [by_family[f][0] for f in ordered_families]
    # catalog.incidents 재조립
    base_catalog["incidents"] = [by_family[f][3] for f in ordered_families]
    # dplan.cards 재조립 — 사건 순서대로 카드 키를 쌓는다(원본 카드가 먼저).
    merged_dplan = {}
    for f in ordered_families:
        merged_dplan.update(by_family[f][2])
    base_dplan["cards"] = merged_dplan
    # gplan.mechanisms 재조립 — 원본 메타 보존, sourceCaseIds만 복원값으로 교체(순서 유지).
    # 새 사건이 아직 gplan 에 없는 mechanism 을 쓰면 그 사실을 드러낸다(조용히 누락하지 않음).
    merged_map = {}
    for f in ordered_families:
        for mid, cases in by_family[f][1].items():
            merged_map.setdefault(mid, []).extend(cases)
    known_mechanisms = {m["id"] for m in base_gplan["mechanisms"]}
    unknown = set(merged_map) - known_mechanisms
    from review_context_pilot import require  # 지연 import — 모듈 관행 유지
    require(not unknown, "UNKNOWN_MECHANISM:" + ",".join(sorted(unknown)))
    for m in base_gplan["mechanisms"]:
        cases = merged_map.get(m["id"], [])
        order = {cid: i for i, cid in enumerate(definition["mechanismCaseOrder"][m["id"]])}
        m["sourceCaseIds"] = sorted(cases, key=lambda cid: (order.get(cid, len(order)), cid))
    return base_cards, base_gplan, base_dplan, base_catalog


def _strip_ts(obj):
    o = dict(obj)
    o.pop("refreshOrDeleteBy", None)
    return o


def runtime_equivalence(root: Path):
    """6사건 전부 incident로 변환 -> 재조립 -> 기존 compile 체인 -> runtime 바이트 동치(refreshOrDeleteBy 제외)."""
    from review_context_pilot import parse, read_bytes
    from build_review_guidelines import build_bundle, compile_guidelines
    from build_context_dictionary import compile_dictionary
    from review_dataset_catalog import inside

    cards = load(root, "drafts/cards.json")
    gplan = load(root, "guidelines/plan.json")
    dplan = load(root, "research/context-dictionary-plan.json")
    catalog = load(root, "catalog.json")

    incidents = [to_incident(ci["familyId"], ci, gplan, dplan, catalog) for ci in cards["incidents"]]
    from incident_build_definition import extract
    r_cards, r_gplan, r_dplan, r_catalog = reassemble(root, incidents, extract(cards, gplan, dplan, catalog))

    checks = {
        "cards_json": r_cards == cards,
        "gplan_json": r_gplan == gplan,
        "dplan_json": r_dplan == dplan,
        "catalog_json": r_catalog == catalog,
    }

    # 재조립본이 원본과 완전히 같으면, 같은 compile 함수가 같은 runtime 을 낸다 — 동치 자동.
    # 그래도 end-to-end 로 한 번 더 증명한다: 재조립 입력으로 실제 runtime 생성 후 저장본과 비교.
    private_root = root.parent.parent / "uploads/comment-collections"
    source_bytes = json.dumps(r_cards, ensure_ascii=False).encode("utf-8")
    bundle = r_cards
    reaction_plan = load(root, "research/reaction-classification-plan.json")
    expected = build_bundle(root, private_root, reaction_plan)
    checks["bundle_matches_source"] = (bundle == expected)

    snapshots = {}
    for cfg in r_dplan["cards"].values():
        for d in cfg.get("documents", []):
            snapshots[d["snapshot"]] = read_bytes(inside(root, d["snapshot"]))
    sb = read_bytes(root / "drafts/cards.json")  # compile 내부 해시용 원본 바이트
    dictionary = compile_dictionary(bundle, r_dplan, r_gplan, sb, snapshots)
    result = compile_guidelines(bundle, r_gplan, sb, dictionary)
    saved = parse(read_bytes(root / "guidelines/runtime.json"))
    checks["runtime_byte_equivalent_except_ts"] = (_strip_ts(result) == _strip_ts(saved))
    return checks


def roundtrip_check(root: Path, family_id: str):
    """pisik 1건: 레거시 -> incident -> 레거시 복원이 원본과 파싱 동일한지."""
    cards = load(root, "drafts/cards.json")
    gplan = load(root, "guidelines/plan.json")
    dplan = load(root, "research/context-dictionary-plan.json")
    catalog = load(root, "catalog.json")

    orig_cards_inc = next(i for i in cards["incidents"] if i["familyId"] == family_id)
    orig_dplan = {cid: dplan["cards"][cid] for cid in dplan["cards"] if cid in {c["id"] for c in orig_cards_inc["cards"]}}
    orig_gplan = {m["id"]: m["sourceCaseIds"] for m in gplan["mechanisms"]
                  if any(cid in {c["id"] for c in orig_cards_inc["cards"]} for cid in m.get("sourceCaseIds", []))}
    orig_cat = next(i for i in catalog["incidents"] if i["familyId"] == family_id)

    incident = to_incident(family_id, orig_cards_inc, gplan, dplan, catalog)
    r_cards, r_gplan, r_dplan, r_cat = from_incident(incident)

    results = {}
    # 1. cards incident 왕복
    results["cards_incident"] = (r_cards == orig_cards_inc)
    # 2. dplan 카드 왕복
    results["dplan_cards"] = (r_dplan == orig_dplan)
    # 3. gplan 역매핑 왕복 (사건 내부 mechanism->caseId 집합이 보존되는지)
    #    원본 gplan은 사건 밖 caseId도 섞일 수 있으므로, 사건 내부 caseId로 필터해 비교.
    inc_cases = {c["id"] for c in orig_cards_inc["cards"]}
    orig_gplan_filtered = {mid: [c for c in cases if c in inc_cases] for mid, cases in orig_gplan.items()}
    results["gplan_mapping"] = (r_gplan == orig_gplan_filtered)
    # 4. catalog 항목 왕복
    results["catalog_entry"] = (r_cat == orig_cat)
    return results, incident


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset-root", type=Path, default=DEFAULT_ROOT)
    p.add_argument("--family", default="pisik-yeongyang")
    p.add_argument("--dump-incident", action="store_true", help="복원 incident JSON을 stdout에 출력")
    p.add_argument("--all", action="store_true", help="6사건 전부 변환->재조립->runtime 바이트 동치 검증")
    p.add_argument("--emit", action="store_true", help="incidents/{id}.json 6개를 디스크에 쓰고 동치 재검증(Git 제외 로컬)")
    p.add_argument("--emit-definition", action="store_true", help="기존 자료에서 definitions/build.json 신규 생성(덮어쓰기 금지)")
    args = p.parse_args()
    if args.emit_definition:
        if args.emit or args.all or args.dump_incident:
            p.error("DEFINITION_MIGRATION_MUST_BE_SEPARATE")
        from incident_build_definition import emit
        print(json.dumps({"definition": str(emit(args.dataset_root))}, ensure_ascii=False))
        return
    if args.emit:
        written, checks = emit_incidents(args.dataset_root)
        ok = all(checks.values())
        print(json.dumps({"emitted": written, "count": len(written),
                          "equivalentAfterWrite": ok, "checks": checks}, ensure_ascii=False, indent=2))
        raise SystemExit(0 if ok else 1)
    if args.all:
        checks = runtime_equivalence(args.dataset_root)
        ok = all(checks.values())
        print(json.dumps({"allIncidents": True, "runtimeEquivalent": ok, "checks": checks},
                         ensure_ascii=False, indent=2))
        raise SystemExit(0 if ok else 1)
    results, incident = roundtrip_check(args.dataset_root, args.family)
    ok = all(results.values())
    out = {"family": args.family, "roundtripEquivalent": ok, "checks": results}
    print(json.dumps(out, ensure_ascii=False, indent=2))
    if args.dump_incident:
        print("--- INCIDENT ---")
        print(json.dumps(incident, ensure_ascii=False, indent=2))
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
