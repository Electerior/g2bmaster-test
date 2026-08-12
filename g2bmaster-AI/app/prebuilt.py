"""번들이 "부품 따로 사기"인지 "완본체 사기"인지 판정하고, 완본체면 유사 완제품을 찾는다.

원본 `lib/prebuilt-comparables.js` 이식. 부품 단가 조회(part_resolver)와 방향이 반대다 —
그쪽은 완제품 PC 를 버리고, 이쪽은 완제품 PC 만 남긴다.

신호 기반 score(P1~P7, N1~N2)로 완제품 여부를 판정한다. 임계 4 이상이고 컴퓨터 시스템이면
완본체로 보고, 검색어를 만들어 다나와에서 유사 완제품을 찾는다.

입력 group = {name, components:[{name}]}. 순수 판정부는 self-check 로 검증된다.
"""

from __future__ import annotations

import re

from .part_resolver import looks_like_kind, strip_inclusion_notes
from .price import resolve as resolve_price

PREBUILT_SCORE_THRESHOLD = 4
_MAX_COMPACT = 6
_DETAILED_COUNT = 20

# 완본체 브랜드(부품 제조사가 아닌 조립·시스템 업체). 부품+완본체 겸업(ASUS·MSI 등)은 제외 —
# 부품 규격서에 늘 나와 신호가 항상 켜지면 판정이 무의미해진다.
_BUILDER_KO = ["한성컴퓨터", "주연테크", "삼보컴퓨터", "TG삼보", "대우루컴즈", "루컴즈", "아이웍스",
               "조이젠", "인성디지털", "라익컴", "휴렛팩커드", "레노버", "에이서", "포유컴퓨터"]
_BUILDER_EN = ["HP", "DELL", "LENOVO", "ACER", "BEELINK", "GEEKOM", "MINISFORUM", "GMKTEC",
               "CHUWI", "ACEMAGIC", "AYANEO", "MAXTANG", "FIREBAT", "GENMACHINE", "CHATREEY"]
_BUILDER_KO_RE = re.compile("|".join(_BUILDER_KO))
_BUILDER_EN_RE = re.compile(r"\b(?:" + "|".join(_BUILDER_EN) + r")\b", re.I)

_SYSTEM_ITEM = re.compile(r"(?:본체|데스크[탑톱]|워크스테이션|일체형\s*PC|올인원)(?!\s*용)|"
                          r"(?:^|[\s(\[-])(?:PC|피씨|컴퓨터)(?!\s*용)", re.I)
_COMPONENT_MODEL = re.compile(
    r"\b(?:RTX|GTX|RX|ARC)\s*-?\d{3,4}|\bDDR\d|\b(?:NVME|SATA|M\.?2)\b|\b[BXHZ]\d{3}[A-Z]*\b|"
    r"\b[RI]\d\s*-?\s*\d{4,5}[A-Z]*\b|"
    r"\b(?:XEON|EPYC|RYZEN|GENOA|BERGAMO|TURIN|MILAN|THREADRIPPER|GEFORCE|RADEON|QUADRO|TESLA)\b", re.I)
_GENERIC_WORD = re.compile(
    r"^(?:GAMING|DESKTOP|COMPUTER|SYSTEM|TOWER|OFFICE|WORKSTATION|SERVER|MAIN|BODY|TYPE|MODEL|SET|"
    r"MINI|MICRO|SLIM|SERIES|VESA|QSFP\d*|SFP\d*|USB|HDMI|DISPLAYPORT|ETHERNET|WIFI|BLUETOOTH|KVA|WATT)$", re.I)
_MANUF_WORD = re.compile(
    r"^(?:ASUS|ASUSTOR|MSI|GIGABYTE|ASROCK|ZOTAC|PALIT|NVIDIA|AMD|INTEL|SAMSUNG|SKHYNIX|APPLE|SONY|"
    r"CANON|EPSON|BROTHER|XEROX|SUPERMICRO|TYAN|QNAP|SYNOLOGY|CISCO|NETGEAR|TPLINK|IPTIME|EFM)$", re.I)
_CODE_TOKEN = re.compile(r"\b[A-Z][A-Z0-9]*\d[A-Z0-9]*\b|\b[A-Z]{4,}\b", re.I)

_STRUCTURAL = [
    ("메인보드", re.compile(r"메인\s*보드|마더\s*보드|main\s*board|mother\s*board|\bM/?B\b", re.I)),
    ("케이스", re.compile(r"케이스|\bcase\b", re.I)),
    ("파워", re.compile(r"파워|전원\s*공급|\bPSU\b|power\s*supply", re.I)),
]
_SPEC_PRESENT = re.compile(
    r"\b(?=[A-Za-z0-9-]*[A-Za-z])(?=[A-Za-z0-9-]*\d)[A-Za-z0-9-]{3,}\b|\b(?:ATX|M-ATX|MATX|ITX)\b|"
    r"미들\s*타워|미니\s*타워|빅\s*타워|슬림형?", re.I)
_SET_ITEM = re.compile(r"윈도우|윈도|\bwindows\b|\bMS\s*오피스|한글\s*오피스|키보드|마우스|모니터", re.I)
#: 품목명이 스스로 부품 조달임을 말하는 낱말. "부품용"·"부품비" 같은 파생도 함께 잡는다.
_PARTS_ITEM = re.compile(r"부품|자재|조립용|업그레이드|증설용?", re.I)
_SYSTEM_UNIT = re.compile(r"\d+\s*(?:대|식|세트)(?![가-힣])|\d+\s*SET\b", re.I)
_ASSEMBLY_SUPPLY = re.compile(r"쿨러|쿨링\s*팬|시스템\s*팬|써멀|서멀|그리스|케이블\s*타이|케이블타이|수랭|공랭", re.I)

# 부품 종류 신호(product-analysis.componentSignalCount) — "이게 컴퓨터인가" 판정용.
_COMPONENT_SIGNALS = [
    re.compile(r"\bCPU\b|프로세서|라이젠|ryzen|코어\s*i|core\s*i|\bxeon\b|제온|\bepyc\b", re.I),
    re.compile(r"\bGPU\b|그래픽\s*카드|\bVGA\b|RTX|GTX|\bRX\b|지포스|라데온", re.I),
    re.compile(r"메모리|\bRAM\b|\bDDR\d", re.I),
    re.compile(r"\bSSD\b|\bNVMe\b|\bHDD\b|저장\s*장치|하드", re.I),
    re.compile(r"메인\s*보드|마더\s*보드|\bM/?B\b", re.I),
    re.compile(r"파워|\bPSU\b|power\s*supply", re.I),
    re.compile(r"케이스|\bcase\b", re.I),
]


def _component_names(group: dict) -> list[str]:
    return [str((c or {}).get("name") or "") for c in (group.get("components") or [])]


def component_signal_count(text: str) -> int:
    return sum(1 for rgx in _COMPONENT_SIGNALS if rgx.search(text))


def find_system_builder(texts: list[str]) -> str:
    for text in texts:
        en = _BUILDER_EN_RE.search(str(text))
        if en:
            return en.group(0)
        ko = _BUILDER_KO_RE.search(re.sub(r"\s+", "", str(text)))
        if ko:
            return ko.group(0)
    return ""


def find_prebuilt_code(name: str = "") -> str:
    for m in _CODE_TOKEN.findall(str(name or "")):
        if len(m) < 2 or _COMPONENT_MODEL.search(m) or _GENERIC_WORD.match(m) or _MANUF_WORD.match(m) or m.isdigit():
            continue
        return m
    return ""


def structural_roles_unspecified(parts: list[str]) -> list[str] | None:
    detail = []
    for role, pattern in _STRUCTURAL:
        named = [p for p in parts if pattern.search(p)]
        if any(_SPEC_PRESENT.search(p) for p in named):
            return None   # 하나라도 규격이 박혀 있으면 부품 조달
        detail.append(f"{role}(이름만)" if named else f"{role} 없음")
    return detail


def collect_signals(group: dict) -> list[dict]:
    signals = []
    name = str(group.get("name") or "")
    parts = _component_names(group)

    sysname = _SYSTEM_ITEM.search(name)
    if sysname:
        # 가중치 2→3 (2026-08). 임계(4)를 넘기는 다른 길이 사실상 브랜드 신호(P1 제품코드·P2
        # 완본체 브랜드, 각 3점)뿐인데, **조달 규격서는 특정 브랜드를 적지 않는다**(담합 소지).
        # 그래서 "사무용 컴퓨터(본체) 30대 구매" 같은 명백한 완본체 조달이 3점에 걸려 부품
        # 조달로 처리됐다. 임계를 낮추는 대신 **브랜드 없이도 켜지는 가장 강한 신호**를 올린다 —
        # 품목명이 "본체·PC·컴퓨터·데스크탑"이면 그것은 정의상 완성된 시스템을 사는 것이다.
        # 반대 방향(부품 조달 오판)은 N1(조립 부자재 -2)·N2(세밀 -1)가 그대로 막는다.
        signals.append({"id": "P4", "weight": 3, "evidence": f'품목명이 시스템을 가리킴: "{sysname.group(0).strip()}"'})
    code = find_prebuilt_code(name)
    if code:
        signals.append({"id": "P1", "weight": 3, "code": code, "evidence": f'번들명에 완본체 제품코드: "{code}"'})

    # N1 을 먼저 본다 — P3 와 같은 증거를 반대로 읽기 때문이다.
    assembly = next((m for p in parts if (m := _ASSEMBLY_SUPPLY.search(strip_inclusion_notes(p)))), None)

    # 구조 부품 미명시(P3)는 "몸통을 통째로 산다"는 신호다. 그런데 <b>조립 부자재를 함께
    # 발주했다면</b>(N1) 같은 사실이 "직접 조립한다 — 목록이 덜 적혔을 뿐"으로 읽힌다.
    # 둘을 동시에 세면 조립 부품 발주가 +2 를 얻어 완제품 쪽으로 넘어간다(실측: "데스크탑 PC
    # 조립용 부품(쿨러·써멀 포함)" 이 임계를 넘었다). 모순되는 읽기는 하나만 채택한다.
    unspec = None if assembly else structural_roles_unspecified(parts)
    if unspec:
        signals.append({"id": "P3", "weight": 2, "evidence": f"구조 부품 미명시: {' · '.join(unspec)}"})
    builder = find_system_builder([name, *parts])
    if builder:
        signals.append({"id": "P2", "weight": 3, "evidence": f'완본체 브랜드: "{builder}"'})
    for p in parts:
        m = _SET_ITEM.search(p)
        if m:
            signals.append({"id": "P5", "weight": 1, "evidence": f'세트 납품 품목: "{m.group(0)}"'})
            break
    if len(parts) <= _MAX_COMPACT:
        signals.append({"id": "P6", "weight": 1, "evidence": f"부품 항목 {len(parts)}개로 간략함"})
    unit = _SYSTEM_UNIT.search(name)
    if unit:
        signals.append({"id": "P7", "weight": 1, "evidence": f'시스템 단위 수량: "{unit.group(0).strip()}"'})
    if assembly:
        signals.append({"id": "N1", "weight": -2, "evidence": f'조립 부자재 발주: "{assembly.group(0)}"'})
    # 품목명이 스스로 "부품"이라고 말하면 그것이 가장 직접적인 증거다. P4(품목명이 시스템)와
    # 정면으로 맞서는 자리라 같은 무게로 둔다 — "데스크탑 PC **조립용 부품**" 같은 이름에서
    # 앞말만 보고 완본체로 넘어가지 않게 한다.
    partsword = _PARTS_ITEM.search(name)
    if partsword:
        signals.append({"id": "N3", "weight": -3, "evidence": f'품목명이 부품을 가리킴: "{partsword.group(0)}"'})
    if len(parts) >= _DETAILED_COUNT:
        signals.append({"id": "N2", "weight": -1, "evidence": f"부품 항목 {len(parts)}개로 세밀함"})
    return signals


def _looks_like_computer(group: dict) -> bool:
    text = " ".join([str(group.get("name") or ""), *_component_names(group)])
    return component_signal_count(text) >= 1 or bool(_SYSTEM_ITEM.search(str(group.get("name") or "")))


def classify_prebuilt_bundle(group: dict) -> dict:
    components = group.get("components") if isinstance(group, dict) else None
    if not isinstance(components, list) or not components:
        return {"eligible": False, "reason": "no-components", "isPrebuilt": False, "score": 0, "signals": []}
    signals = collect_signals(group)
    score = sum(s["weight"] for s in signals)
    is_computer = _looks_like_computer(group)
    return {
        "eligible": True,
        "reason": "" if is_computer else "not-a-computer",
        "isPrebuilt": score >= PREBUILT_SCORE_THRESHOLD and is_computer,
        "score": score,
        "signals": signals,
    }


# ── 검색어 ───────────────────────────────────────────────────────────────────
def _clean_bundle_name(name: str = "") -> str:
    t = re.sub(r"\([^)]*\)", " ", str(name))
    t = re.sub(r"\d+\s*(?:대|식|세트|개|EA|SET)(?![가-힣])", " ", t, flags=re.I)
    return re.sub(r"\s{2,}", " ", t).strip()


def build_prebuilt_query(group: dict) -> str:
    name = str(group.get("name") or "")
    code = find_prebuilt_code(name)
    if code:
        return _clean_bundle_name(name)   # 제품코드가 완본체를 특정
    builder = find_system_builder([name])
    return _clean_bundle_name(name) if name.strip() else (builder or "")


async def find_prebuilt_comparables(payload: dict) -> dict:
    """번들 완제품 판정 + 유사 완제품 검색(다나와). 프론트 계약 PrebuiltComparablesResponse."""
    name = str(payload.get("name") or "").strip()
    components = payload.get("components") if isinstance(payload.get("components"), list) else []
    group = {"name": name, "components": components}

    verdict = classify_prebuilt_bundle(group)
    out = {
        "eligible": verdict["eligible"],
        "reason": verdict["reason"],
        "isPrebuilt": verdict["isPrebuilt"],
        "score": verdict["score"],
        "signals": verdict["signals"],
        "comparables": [],
        "requiredSpec": None,
        "query": "",
        "queryBasis": "",
        "misses": [],
        "searchStatus": "no-search",
    }
    if not verdict["isPrebuilt"]:
        return out   # 부품 조달로 판정 — 완제품 검색을 돌리지 않는다(원본과 같다)

    query = build_prebuilt_query(group)
    out["query"] = query
    out["queryBasis"] = "code" if find_prebuilt_code(name) else "name"
    if not query:
        out["searchStatus"] = "no-query"
        return out

    # 다나와에서 찾되 **완제품 PC 만** 남긴다(부품 단가와 반대 방향).
    result = await resolve_price({"itemName": query, "deadlineMs": 8000})
    quotes = [q for q in (result.get("quotes") or []) if looks_like_kind(q.get("name") or "", "완제품 PC")]
    quotes.sort(key=lambda q: q.get("priceKrw") or 0)
    out["comparables"] = [{"name": q["name"], "priceKrw": q["priceKrw"], "url": q["url"], "source": q["source"]}
                          for q in quotes[:5]]
    out["searchStatus"] = "found" if quotes else "not-found"
    return out


if __name__ == "__main__":
    # 완본체 번들 — 제품코드·브랜드·시스템명·구조부품 미명시
    pc = {"name": "게이밍 PC 본체 OptiPlex 7010", "components": [
        {"name": "AMD 라이젠5 9600X"}, {"name": "RTX 5070"}, {"name": "DDR5 32GB"}, {"name": "SSD 1TB"}]}
    v = classify_prebuilt_bundle(pc)
    assert v["isPrebuilt"], v
    assert build_prebuilt_query(pc), "완본체는 검색어가 나와야 한다"
    # 부품 조달 — 구조부품 규격 박힘 + 세밀
    parts = {"name": "서버 부품 일괄", "components": [
        {"name": "ASUS PRIME B650M-A 메인보드"}, {"name": "시소닉 750W 파워"}, {"name": "미들타워 케이스"},
        {"name": "라이젠 9600X"}, {"name": "DDR5 32GB"}, {"name": "990 PRO 2TB"}]}
    assert not classify_prebuilt_bundle(parts)["isPrebuilt"], "규격 박힌 부품 조달은 완제품 아님"
    # 컴퓨터가 아닌 것 — 모니터
    mon = {"name": "HP E27q G5 QHD 모니터", "components": [{"name": "27인치 IPS 패널"}]}
    assert not classify_prebuilt_bundle(mon)["isPrebuilt"], "모니터는 완제품 PC 가 아니다"

    # ── 브랜드 없는 완본체 조달 (2026-08) ────────────────────────────────────
    # 조달 규격서는 브랜드를 적지 않는다. P1·P2 없이도 품목명만으로 임계를 넘어야 한다.
    office = {"name": "사무용 컴퓨터(본체) 30대 구매", "components": [
        {"name": "i7-14700 이상"}, {"name": "DDR5 32GB"}, {"name": "NVMe 1TB"},
        {"name": "500W 파워"}, {"name": "미들타워 케이스"}, {"name": "B760 메인보드"}]}
    assert classify_prebuilt_bundle(office)["isPrebuilt"], "브랜드 없는 완본체 조달을 놓치면 안 된다"
    assert not any(s["id"] in ("P1", "P2") for s in collect_signals(office)), "브랜드 신호 없이 넘어야 한다"

    # 반대 방향 — 품목명이 시스템을 가리켜도 '부품'이라고 적혀 있으면 부품 조달이다.
    diy = {"name": "데스크탑 PC 조립용 부품 (쿨러, 써멀그리스 포함)", "components": [
        {"name": "라이젠 9600X"}, {"name": "RTX 5070"}, {"name": "DDR5 32GB"},
        {"name": "공랭 쿨러"}, {"name": "써멀그리스"}]}
    assert not classify_prebuilt_bundle(diy)["isPrebuilt"], "조립용 부품 발주는 완제품이 아니다"
    diy_ids = {s["id"] for s in collect_signals(diy)}
    assert "N3" in diy_ids and "N1" in diy_ids, diy_ids
    assert "P3" not in diy_ids, "조립 부자재를 발주했으면 '구조 미명시'로 읽지 않는다"
    upgrade = {"name": "컴퓨터 메모리 증설용 부품 구매", "components": [{"name": "DDR5 32GB x 40"}]}
    assert not classify_prebuilt_bundle(upgrade)["isPrebuilt"], "증설용 부품은 완제품이 아니다"
    print("app/prebuilt.py: OK")
