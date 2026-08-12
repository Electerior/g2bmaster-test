"""규격서 → 부품 단가 추정. **ITMAYA 색인(원본) + 다나와 웹검색 하이브리드.**

원본 `price-estimator.js` 를 이식하고 웹검색을 폴백으로 붙였다.

  1) **ITMAYA GPU서버 가격표 색인** (`data/ITMAYA_GPU서버_가격표.xlsx` '전체옵션' 시트)
     — GPU 서버 규격서면 System·Processor·GPU·Memory·Storage 슬롯을 규칙 매칭해 합산한다.
     완제품 베이스(System)를 인식하고, GPU 장수를 파싱해 곱한다. 원본과 같은 로직이다.
  2) **다나와 웹검색 폴백** (`app.price`) — 색인이 못 잡는 것(비-GPU서버, 색인에 없는 부품)은
     규격서에서 LLM 으로 부품을 뽑아 다나와에서 실시간 조회한다.

원본이 LLM 을 안 쓴 이유는 ITMAYA 카탈로그가 정형이라 규칙으로 충분했기 때문이다. 그 정확도를
살리되, 카탈로그 밖 품목은 웹으로 넓힌다 — 두 접근의 장점을 합친다.

응답 규격은 프론트 계약 `EstimatedUnitCost`(union). `breakdown[].source` 로 색인/웹을 구분한다.
"""

from __future__ import annotations

import asyncio
import json
import re

from . import discover
from .errors import AiFailure
from .itmaya import load_index as _load_index, _num  # noqa: F401 (_num: 색인 로더와 함께 이전)
from .llm.client import lms_chat, loaded_model
from .part_resolver import derive_product_identity, quote_matches_identity
from .prebuilt import classify_prebuilt_bundle, find_prebuilt_comparables
from .price import resolve as resolve_price

# ── ITMAYA 색인 상수 (원본 price-estimator.js) ───────────────────────────────
# _load_index()/_num() 은 app/itmaya.py 로 옮겼다(멀티소스 리졸버와 공유). 여기선 import 만 한다.

#: System 제품별 GPU 슬롯 수 — 베이스 폴백에서 GPU 장수를 수용하는 가장 싼 System 을 고를 때.
SYSTEM_SLOTS = {
    "ESC4000-E11": 4, "531A-I": 2, "741GE-TNRT": 4, "ESC4000A-E12": 4,
    "ESC8000-E12": 8, "ESC8000A-E13": 8, "421GE-TNRT": 10, "821GE-TNHR": 8,
}
CORE = ["System", "Processor", "GPU", "Memory", "Storage"]
OPTIONAL = ["RAID", "NIC", "Support (OS 설치)"]

# GPU 서버 신호가 없으면 ITMAYA 카탈로그 대상이 아니다(노트북·사무기기·일반 워크스테이션 오탐 차단).
#
# **바뀐 점(2026-08):** 예전엔 "워크스테이션/workstation" 단독으로 이 카탈로그가 켜져서, 실제 사고가
# 났다 — "암호 알고리즘 구현용 워크스테이션·노트북·전력 파형 측정용 보드" 같은 일반 물품이 GPU
# 서버로 오인돼 ESC/System 섀시가 GPU·Storage 칸에 엉뚱하게 채워졌다. ITMAYA GPU 서버는 정의상
# **가속 GPU 를 단다** — 그래서 실제 가속기 모델(H100/H200/A100/L40/RTX 40·50/RTX PRO…)이나
# 명시적 "GPU 서버/가속 서버/GPU 워크스테이션" 문구가 있을 때만 켠다. 맨 워크스테이션은 제외.
_GPU_SERVER = re.compile(
    r"tesla|\bh100\b|\bh200\b|\ba100\b|\ba30\b|\ba40\b|\ba10\b|\bl40s?\b|\bl4\b|"
    r"rtx\s?40\d0|rtx\s?50\d0|rtx\s?pro\s?\d{3,4}|quadro|gpgpu|gpu\s*서버|가속\s*서버|딥\s*러닝|"
    r"deep\s*learning|추론\s*서버|ai\s*(?:학습|추론|서버)|gpu\s*(?:워크스테이션|workstation)",
    re.IGNORECASE,
)
_NON_SERVER = re.compile(
    r"노트북|랩탑|laptop|태블릿|tablet|일체형|올인원|all-?in-?one|모니터|프린터|복합기|스캐너",
    re.IGNORECASE,
)
_SERVER_CTX = re.compile(r"서버|server|랙마운트|랙\s*마운트|rack\s*mount|데이터\s*센터|워크스테이션", re.IGNORECASE)

_TOKEN_SPLIT = re.compile(r"[^a-z0-9가-힣]+")


def _tokens(s: str) -> list[str]:
    text = str(s or "").lower()
    text = re.sub(r"(\d)([a-z])", r"\1 \2", text)
    text = re.sub(r"([a-z])(\d)", r"\1 \2", text)
    return [t for t in _TOKEN_SPLIT.split(text) if len(t) >= 2]


def _score_name(qset: set[str], name: str) -> int:
    score = 0
    for tk in set(_tokens(name)):
        if tk in qset:
            score += 2 if tk[0].isdigit() else 1   # 숫자 토큰(용량·모델) 가중
    return score


def _best_in_category(index: dict, category: str, qset: set[str], min_score: int) -> dict | None:
    best, best_score = None, 0
    for opt in index["byCategory"].get(category, []):
        sc = _score_name(qset, opt["name"])
        if sc > best_score:
            best_score, best = sc, opt
    return {**best, "score": best_score} if best and best_score >= min_score else None


def _parse_gpu_count(text: str) -> int | None:
    s = str(text or "")
    for pat in (r"(\d{1,2})\s*(?:gpu|장|way|-way|ea|개|slot)",
                r"gpu\s*[:x]?\s*(\d{1,2})",
                r"(\d{1,2})\s*[×x]\s*(?:gpu|rtx|nvidia)"):
        m = re.search(pat, s, re.IGNORECASE)
        if m:
            n = int(m.group(1))
            if 1 <= n <= 16:
                return n
    return None


def _fallback_base(index: dict, gpu_count: int) -> dict | None:
    if not gpu_count:
        return None
    systems = [s for s in index["byCategory"].get("System", []) if SYSTEM_SLOTS.get(s["product"], 0) >= gpu_count]
    systems.sort(key=lambda s: s["low"])
    return {**systems[0], "score": 0, "inferred": True} if systems else None


def _is_gpu_server_text(text: str) -> bool:
    if _NON_SERVER.search(text) and not _SERVER_CTX.search(text):
        return False
    return bool(_GPU_SERVER.search(text))


def _estimate_from_itmaya(text: str) -> dict:
    """원본 estimateUnitCost 이식 — ITMAYA 색인으로 GPU 서버 단가를 합산한다."""
    index = _load_index()
    if not index:
        return {"matched": False, "reason": "price-table-unavailable"}
    if not _is_gpu_server_text(text):
        return {"matched": False, "reason": "not-gpu-server"}
    qset = set(_tokens(text))
    if not qset:
        return {"matched": False, "reason": "empty-input"}

    parsed_gpu = _parse_gpu_count(text)
    gpu_count = parsed_gpu or 1
    breakdown, low, high, has_base = [], 0, 0, False

    for cat in CORE:
        m = _best_in_category(index, cat, qset, 1 if cat == "System" else 2)
        if not m and cat == "System" and parsed_gpu:
            m = _fallback_base(index, parsed_gpu)
        if not m:
            continue
        qty = gpu_count if cat == "GPU" else 1
        low += m["low"] * qty
        high += m["high"] * qty
        if cat == "System":
            has_base = True
        breakdown.append({"category": cat, "option": m["name"], "product": m["product"],
                          "qty": qty, "low": m["low"], "high": m["high"],
                          "inferred": bool(m.get("inferred")),
                          "role": "base" if cat == "System" else "part", "source": "itmaya"})

    if not any(b["category"] in ("System", "GPU") for b in breakdown):
        return {"matched": False, "reason": "not-gpu-server", "gpuCount": parsed_gpu}

    for cat in OPTIONAL:
        m = _best_in_category(index, cat, qset, 3)   # 옵션은 확실히 언급된 것만
        if not m:
            continue
        low += m["low"]
        high += m["high"]
        breakdown.append({"category": cat, "option": m["name"], "product": m["product"],
                          "qty": 1, "low": m["low"], "high": m["high"], "inferred": False,
                          "role": "part", "source": "itmaya"})

    if not breakdown:
        return {"matched": False, "reason": "no-match", "gpuCount": gpu_count}
    return {"matched": True, "low": low, "high": high, "mid": round((low + high) / 2),
            "gpuCount": gpu_count, "hasBase": has_base, "breakdown": breakdown, "currency": "KRW"}


# ── 웹 폴백 (LLM 부품추출 + 다나와) ──────────────────────────────────────────
# "베어본" 은 장비를 담는 몸통(섀시·본체·베이스)이다. 부품이 아니라 **base 역할**로 나간다 —
# 이 칸이 없어서 실측에서 "본체: 2U 랙마운트 서버, ASUS ESC4000A-E12" 가 메인보드로 분류됐고,
# 그 결과 웹 경로는 role="base" 행을 영영 만들지 못해 hasBase 가 항상 False 였다.
PART_CATEGORIES = ("베어본", "CPU", "GPU", "RAM", "SSD", "HDD", "메인보드", "파워", "케이스", "쿨러", "네트워크")
BASE_CATEGORY = "베어본"
_NOISE = re.compile(r"중고|리퍼|refurb|벌크|bulk|병행수입|해외구매", re.IGNORECASE)
_CATEGORY_HINT = {"RAM": "메모리", "SSD": "SSD", "HDD": "하드디스크", "메인보드": "메인보드",
                  "파워": "파워서플라이", "베어본": "베어본"}

#: 사양만 있는 행(strong=False)이 최저가를 고를 때 요구하는 **종류 낱말**. 상품명에 이 중
#: 하나도 없으면 그 종류의 물건이 아니다. 없는 카테고리는 검사하지 않는다(과잉 차단 방지).
_CATEGORY_GUARD = {
    "CPU": re.compile(r"\bCPU\b|프로세서|라이젠|ryzen|\bxeon\b|제온|\bepyc\b|코어\s*i|core\s*i", re.I),
    "GPU": re.compile(r"\bGPU\b|그래픽\s*카드|\bVGA\b|지포스|geforce|라데온|radeon|\brtx\b|\bgtx\b|"
                      r"\bquadro\b|\btesla\b|\bh100\b|\bh200\b|\ba100\b|\bl40s?\b", re.I),
    "RAM": re.compile(r"메모리|\bRAM\b|\bDIMM\b|\bDDR\d", re.I),
    "SSD": re.compile(r"\bSSD\b|\bNVMe\b|solid\s*state|솔리드", re.I),
    "HDD": re.compile(r"\bHDD\b|하드\s*디스크|hard\s*disk|\bSATA\b.*드라이브", re.I),
    "메인보드": re.compile(r"메인\s*보드|마더\s*보드|main\s*board|mother\s*board|\bM/?B\b", re.I),
    "파워": re.compile(r"파워|전원\s*공급|\bPSU\b|power\s*supply|서플라이", re.I),
    "케이스": re.compile(r"케이스|\bcase\b|타워|랙\s*마운트|rack\s*mount", re.I),
    "쿨러": re.compile(r"쿨러|쿨링|\bcooler\b|히트\s*싱크|heat\s*sink|수랭|공랭|\bfan\b|팬", re.I),
    "네트워크": re.compile(r"랜\s*카드|\bNIC\b|이더넷|ethernet|네트워크|스위치|switch|"
                       r"\d+\s*G(?:b|be|bps)?\b|\bSFP\b|\bQSFP\b", re.I),
    "베어본": re.compile(r"베어본|barebone|섀시|샤시|chassis|본체|서버\s*케이스|랙\s*마운트", re.I),
}

#: 이 카테고리에 **절대 올 수 없는** 상품. 종류 낱말(위)과 달리 양쪽 경로 모두에 건다.
#:
#: 몸통(베어본·메인보드)을 찾는데 제목에 가속기 모델이 박혀 있으면 그것은 몸통이 아니라
#: **GPU 가 꽂힌 완성 서버**다 — 실측에서 "ASUS GPU서버 ESC4000A-E12 … H100 NVL 94G"
#: 1억 905만원이 베어본 한 대 값으로 앉았다. 모델명(strong)으로 찾을 때도 걸려야 하므로
#: `_KIND_PATTERNS` 가 아니라 여기서 카테고리별로 막는다.
_ACCELERATOR = re.compile(r"\bh100\b|\bh200\b|\ba100\b|\ba30\b|\ba40\b|\bl40s?\b|\bl4\b|"
                          r"\brtx\s*pro\s*\d{3,4}\b|\brtx\s*\d{3,4}\b|\bgtx\s*\d{3,4}\b|"
                          r"\btesla\b|\bquadro\b", re.I)
_CATEGORY_EXCLUDE = {"베어본": _ACCELERATOR, "메인보드": _ACCELERATOR}

UNIT_PROMPT = """너는 조달 규격서에서 **무엇을 몇 대 만들어야 하는가**를 뽑는 분석가다.

규격서가 요구하는 장비를 **기종(unit)별로** 나눈다. 한 규격서에 사무용 PC 20대와 설계용
워크스테이션 5대가 함께 있으면 기종은 둘이다. 기종이 하나뿐이면 하나만 만든다.
기종마다 그 장비 **1대**에 들어가는 부품을 빠짐없이 적는다.

- unitId: "A", "B" 처럼 짧은 식별자
- label: 규격서가 그 기종을 부르는 이름("사무용 PC(A형)")
- unitQty: 이 기종을 **몇 대** 납품하는가. 규격서의 "30대"·"2식"이 여기 온다.
- parts[].category: 베어본, CPU, GPU, RAM, SSD, HDD, 메인보드, 파워, 케이스, 쿨러, 네트워크 중 하나
  · 베어본 = 부품을 담는 몸통(본체·섀시·베어본·랙마운트 서버 본체). 메인보드와 구분하라.
- parts[].name: 쇼핑몰에서 검색할 구체 모델명(예: "NVIDIA H200 141GB").
  규격서에 모델이 없고 사양만 있으면 **사양을 그대로** 적어라. 지어내지 마라 —
  모델 찾기는 다음 단계가 한다.
- parts[].qty: 이 기종 **1대**에 들어가는 개수다. `unitQty` 를 절대 곱하지 마라.
  총 수량은 다른 곳에서 곱한다. 여기서 곱하면 원가가 그 배수만큼 부풀려진다.
  예: 30대 규격서에 "CPU 2소켓"이면 qty 는 60 이 아니라 2 다.
- parts[].named: 규격서에 제품명·모델명이 **적혀 있으면** true, 사양만 있으면 false
- parts[].evidence: 이 부품의 근거가 된 **규격서 원문 한 줄을 그대로 복사**한다.
  요약하거나 바꿔 쓰지 마라 — 원문에 없는 문장은 근거로 인정되지 않는다.

소프트웨어·용역·설치·보증·교육은 부품이 아니다.
JSON 하나로만 답한다. 부품을 못 찾으면 {"units":[]}.
{"units":[{"unitId":"A","label":"GPU 서버","unitQty":2,
  "parts":[{"category":"GPU","name":"NVIDIA H200 141GB","qty":3,"named":true,
            "evidence":"GPU: NVIDIA H200 141GB 3장"}]}]}"""

#: 1대에 같은 부품이 이보다 많이 들어가는 일은 없다. 넘으면 발주 대수를 곱한 흔적이다.
MAX_PART_QTY = 16
#: 납품 대수 상한. 이 밖의 수는 수량 파싱이 어긋난 것으로 본다.
MAX_UNIT_QTY = 10_000
#: 규격서 원문에서 납품 대수를 읽는다 — LLM 의 `unitQty` 와 교차확인하는 데 쓴다.
_UNIT_QTY_TEXT = re.compile(r"(\d{1,4})\s*(?:대|식|세트|set)\b", re.IGNORECASE)


# ── 수량 교차검증 (규칙이 LLM 산술을 이긴다) ─────────────────────────────────
# LLM 은 수량을 반복해서 틀린다 — 실측에서 "128GB (64GB x 2)" 를 16 으로, 2식 규격서의
# "x2" 를 4 로 냈다. 수량은 **원문에 적혀 있는 값**이지 추론할 대상이 아니다. 그래서
# 근거 문장에서 직접 읽고, 읽히면 그것으로 덮어쓴다.
_QTY_X = re.compile(r"(?<![\d.])[x×]\s*(\d{1,2})\b", re.IGNORECASE)
#: `PCIe 5.0x4`·`Gen5 x16` 은 수량이 아니라 레인 수다. 직전 문맥으로 가른다.
_LANE_CTX = re.compile(r"(?:pcie|gen\s*\d)\s*\d?\.?\d?\s*$|\d\.\d\s*$", re.IGNORECASE)
_CAPACITY = re.compile(r"(\d+(?:\.\d+)?)\s*(GB|TB|MB)\b", re.IGNORECASE)
_MAX_QTY = 64


def _capacity_gb(match: re.Match) -> float:
    unit = match.group(2).upper()
    factor = 1024.0 if unit == "TB" else (1.0 if unit == "GB" else 1 / 1024)
    return round(float(match.group(1)) * factor, 3)


def qty_from_evidence(evidence: str, name: str, category: str) -> int | None:
    """근거 문장에서 수량을 읽는다. 못 읽으면 None(= LLM 값을 그대로 둔다).

    용량이 걸린 부품은 한 걸음 더 본다. `"128GB … (64GB x 2)"` 에서 `x 2` 는 **모듈 개수**라
    이름이 모듈(64GB)이면 2 가 맞지만, 이름이 총량(128GB)이면 그 안에 이미 2 가 들어 있어
    1 이다. `x` 바로 앞의 용량과 이름의 <b>대표 용량</b>(맨 앞에 적힌 것)을 맞춰 둘을 가른다.

    대표 용량으로 보는 이유: 카탈로그 이름은 총량과 구성을 함께 적는다
    (`"128GB DDR5 … (64GB x2)"`). 이름에 나오는 <b>아무</b> 용량이나 맞춰 보면 이런 이름이
    모듈로 읽혀 킷 하나를 두 번 세게 된다 — 실측에서 128GB 킷이 256GB 로 불어났다.
    """
    text = str(evidence or "")
    picked = None
    for match in _QTY_X.finditer(text):
        if not _LANE_CTX.search(text[:match.start()]):
            picked = match          # 마지막 것이 이긴다(수량 표기는 뒤에 붙는다)
    if picked is None:
        # 몸통은 규격서가 개수를 안 적으면 한 대다. 부품은 알 수 없으므로 건드리지 않는다.
        return 1 if category == BASE_CATEGORY else None
    qty = int(picked.group(1))
    if not 1 <= qty <= _MAX_QTY:
        return None
    headline = next((_capacity_gb(m) for m in _CAPACITY.finditer(str(name or ""))), None)
    before = [_capacity_gb(m) for m in _CAPACITY.finditer(text[:picked.start()])]
    if headline is not None and before and before[-1] != headline:
        return 1                    # 이름이 총량을 가리킨다 — 곱하면 두 번 세는 것이다
    return qty


def _parse_json(text: str, opener: str, closer: str):
    cleaned = re.sub(r"^```(?:json)?|```$", "", text.strip(), flags=re.MULTILINE).strip()
    start, end = cleaned.find(opener), cleaned.rfind(closer)
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(cleaned[start:end + 1])
    except ValueError:
        return None


def unit_qty_from_spec(spec_text: str) -> list[int]:
    """규격서 원문에서 읽히는 납품 대수 후보. LLM 의 `unitQty` 와 교차확인하는 데만 쓴다."""
    found = [int(m.group(1)) for m in _UNIT_QTY_TEXT.finditer(str(spec_text or ""))]
    return [n for n in found if 1 <= n <= MAX_UNIT_QTY]


def repair_part_qty(qty: int, unit_qty: int) -> tuple[int, str]:
    """1대 기준을 벗어난 수량을 되돌린다 → (수량, 근거).

    LLM 은 발주 대수를 부품 수량에 곱해 보내는 사고를 반복한다(실측: 30대 규격서에 qty 30).
    **추측으로 1 을 넣지 않는다** — `unitQty` 로 나누어떨어지면 그 곱셈을 되돌리는 것이
    규칙이고(30 ÷ 30 = 1), 안 떨어지면 알 수 없으므로 그 사실을 남긴다. 모르는 수를 1 로
    채우면 원가가 조용히 줄어든다.
    """
    if 1 <= qty <= MAX_PART_QTY:
        return qty, "llm"
    if unit_qty > 1 and qty % unit_qty == 0 and 1 <= qty // unit_qty <= MAX_PART_QTY:
        return qty // unit_qty, "unit-divided"
    return max(1, min(qty, MAX_PART_QTY)), "clamped"


def _normalize_part(raw: dict, unit_qty: int) -> dict | None:
    name = str(raw.get("name") or "").strip()
    cat = str(raw.get("category") or "").strip()
    if not name or cat not in PART_CATEGORIES:
        return None
    try:
        qty = max(1, int(raw.get("qty") or 1))
    except (TypeError, ValueError):
        qty = 1
    # `named`·`evidence` 는 우리가 판정에 쓰지 않는다 — 백엔드가 규격서 원문과 대조할
    # 재료다(경계 계약 §4: AI 가 자기 응답을 자기가 검증하면 검증이 아니다).
    evidence = str(raw.get("evidence") or "").strip()[:300]
    # 수량은 규칙이 이긴다 — 원문에 적힌 개수를 읽을 수 있으면 LLM 값을 덮어쓴다.
    from_spec = qty_from_evidence(evidence, name, cat)
    qty, basis = (from_spec, "evidence") if from_spec is not None else repair_part_qty(qty, unit_qty)
    return {"category": cat, "name": name, "qty": qty, "qtyBasis": basis,
            "named": bool(raw.get("named")), "evidence": evidence}


async def _extract_units(spec_text: str) -> list[dict]:
    """규격서 → 기종별 부품 목록. **여기서 LLM 의 일은 끝난다** — 이후는 전부 규칙이다."""
    model = await loaded_model()
    response = await lms_chat({
        "model": model,
        "messages": [{"role": "system", "content": UNIT_PROMPT}, {"role": "user", "content": spec_text[:12000]}],
        "temperature": 0, "max_tokens": 2500,
    })
    payload = _parse_json(response["choices"][0]["message"]["content"], "{", "}")
    raw_units = (payload or {}).get("units")
    if not isinstance(raw_units, list):
        # 기종 구조를 못 냈으면 예전 계약(부품 배열)일 수 있다 — 한 기종으로 받아 준다.
        flat = _parse_json(response["choices"][0]["message"]["content"], "[", "]")
        raw_units = [{"unitId": "A", "unitQty": 1, "parts": flat}] if isinstance(flat, list) else []

    spec_qtys = set(unit_qty_from_spec(spec_text))
    units = []
    for index, raw in enumerate(u for u in raw_units if isinstance(u, dict)):
        try:
            unit_qty = int(raw.get("unitQty") or 1)
        except (TypeError, ValueError):
            unit_qty = 1
        qty_basis = "llm"
        if not 1 <= unit_qty <= MAX_UNIT_QTY:
            unit_qty, qty_basis = 1, "clamped"
        elif spec_qtys and unit_qty in spec_qtys:
            # 규격서 원문에 그 대수가 실제로 적혀 있다 — 확인된 수량이다.
            qty_basis = "spec"
        elif spec_qtys:
            qty_basis = "unconfirmed"

        parts = [p for p in (_normalize_part(raw_part, unit_qty)
                             for raw_part in (raw.get("parts") or []) if isinstance(raw_part, dict))
                 if p is not None]
        if not parts:
            continue
        units.append({
            "unitId": str(raw.get("unitId") or chr(ord("A") + index))[:8],
            "label": str(raw.get("label") or "").strip()[:120],
            "unitQty": unit_qty, "unitQtyBasis": qty_basis, "parts": parts,
        })
    return units


async def _resolve_spec_only(part: dict) -> dict:
    """사양만 있는 부품(설계 2번)을 탐색으로 모델명까지 끌어올린다.

    쇼핑몰 검색창은 사양을 못 읽는다. 탐색이 모델을 찾아내면 그 이름으로 값을 묻고,
    못 찾으면 원래 이름 그대로 둔다 — 이 단계는 <b>이름을 바꿀 뿐 가격을 만들지 않는다</b>.
    """
    # 판단 기준은 `named` 하나다. `derive_product_identity(...)["strong"]` 을 쓰면 안 된다 —
    # 사양 문자열에서 `DDR5`·`SP5`·`2U` 를 모델명으로 오인해(실측) 탐색이 통째로 건너뛰어진다.
    # 규격서가 제품명을 적었는지는 규격서를 읽은 쪽만 안다.
    if part.get("named") or not discover.enabled():
        return part
    try:
        found = await discover.discover_model(part["name"], part["category"])
    except AiFailure:
        return part          # 탐색 실패는 가격 실패가 아니다
    if found.get("status") == "search-unavailable":
        # 탐색기가 막혔다. 이름을 못 바꾼 채 진행하되 그 사실을 남긴다 — 남기지 않으면
        # "규격서에 없는 부품"과 "탐색이 죽어서 못 찾은 부품"이 화면에서 구분되지 않는다.
        return {**part, "searchUnavailable": True,
                "suspendedEngines": found.get("suspendedEngines", [])}
    if found.get("status") != "found" or not found.get("model"):
        return part
    return {**part, "name": found["model"], "discoveredFrom": part["name"],
            "discovery": {"model": found["model"], "votes": found.get("modelVotes", 0),
                          "shopLinks": found.get("shopLinks", [])[:3]}}


#: 베어본을 색인에서 채택할 최소 점수. 실측 점수: ESC4000A-E12 6 · ESC8000-E12 5 ·
#: 421GE-TNRT 4. 반면 모델 없는 질의("ASUS GPU 서버")는 2 에 그친다 — 그 사이를 자른다.
_BASE_INDEX_MIN_SCORE = 3


def _base_from_index(part: dict) -> dict | None:
    """베어본(몸통)은 **색인을 먼저** 본다. 쇼핑몰에는 몸통만 파는 매물이 거의 없다.

    실측: "ASUS ESC4000A-E12" 를 다나와에서 찾으면 GPU 가 꽂힌 완성 서버(1억 905만원)만
    나온다. 그것을 걸러내면 값이 아예 안 잡히고, 안 걸러내면 섀시 한 대 값이 1억이 된다.
    ITMAYA 카탈로그에는 같은 모델이 **베어본 단가**(5,258,000원)로 들어 있다 — 정형 카탈로그가
    있는 품목에서 웹 검색으로 내려가는 것은 정확도를 버리는 일이다.

    색인 단가는 파일이라 신선하지 않다(`source="itmaya"` 가 그 사실을 나른다).
    """
    index = _load_index()
    if not index:
        return None
    match = _best_in_category(index, "System", set(_tokens(part["name"])), _BASE_INDEX_MIN_SCORE)
    if not match:
        return None
    return {"category": part["category"], "option": part["name"], "product": match["product"],
            "qty": part["qty"], "low": match["low"], "high": match["high"],
            "inferred": False, "role": "base", "source": "itmaya",
            "named": bool(part.get("named")), "evidence": part.get("evidence", ""),
            "qtyBasis": part.get("qtyBasis", "llm"),
            "discoveredFrom": part.get("discoveredFrom"), "discovery": part.get("discovery"),
            "searchUnavailable": bool(part.get("searchUnavailable"))}


async def _price_part(part: dict) -> dict:
    if part["category"] == BASE_CATEGORY:
        indexed = _base_from_index(part)
        if indexed:
            return indexed
    hint = _CATEGORY_HINT.get(part["category"], "")
    query = f"{part['name']} {hint}".strip() if hint and hint not in part["name"] else part["name"]
    result = await resolve_price({"itemName": query, "deadlineMs": 8000})

    # 사양 대조(part_resolver) — 완제품 PC·중고·렌탈·액세서리, 그리고 요청과 다른 용량을 버린다.
    # 이게 없으면 "DDR5 512GB" 검색에 데스크탑 완제품이 최저가로 앉는다(원본이 고친 문제).
    identity = derive_product_identity(part["name"])
    banned = _CATEGORY_EXCLUDE.get(part["category"])
    verified = []
    for q in result.get("quotes") or []:
        if not isinstance(q.get("priceKrw"), int) or q["priceKrw"] <= 0:
            continue
        if banned is not None and banned.search(q.get("name") or ""):
            continue
        verdict = quote_matches_identity({"title": q.get("name"), "price": q["priceKrw"], "url": q.get("url")}, identity)
        if verdict["ok"]:
            verified.append(q)

    # 모델을 특정 못 하는 순수 사양(strong=False)은 대조가 느슨하다 — 그땐 노이즈(중고 등)만 걸러
    # 참고값으로 쓰되 inferred 로 표시한다. 모델이 뚜렷하면(strong) 검증 통과분만 신뢰한다.
    #
    # 단, **그 종류의 물건이 맞는지**는 반드시 본다. 사양 문자열에는 숫자와 단위밖에 없어서
    # 같은 숫자를 가진 전혀 다른 물건이 최저가 자리에 앉는다 — 실측에서 "2000W 이상 이중화
    # 전원공급장치"의 최저가가 **2000W 근적외선 램프 30,690원**이었다. 종류 낱말 하나를
    # 요구하는 것만으로 이 부류가 통째로 걸러진다.
    if not verified and not identity["strong"]:
        guard = _CATEGORY_GUARD.get(part["category"])
        verified = [q for q in (result.get("quotes") or [])
                    if isinstance(q.get("priceKrw"), int) and q["priceKrw"] > 0
                    and not _NOISE.search(q.get("name") or "")
                    and (banned is None or not banned.search(q.get("name") or ""))
                    and (guard is None or guard.search(q.get("name") or ""))]

    role = "base" if part["category"] == BASE_CATEGORY else "part"
    if not verified:
        # 가격을 못 찾은 행은 어떤 소스가 이겼는지 알 수 없다 — source=None(가격도 None).
        return {"category": part["category"], "option": part["name"], "product": None,
                "qty": part["qty"], "low": None, "high": None, "inferred": True,
                "role": role, "source": None}
    prices = sorted(q["priceKrw"] for q in verified)
    cheapest = min(verified, key=lambda q: q["priceKrw"])
    # 대표값은 **중앙값**이다. `high` 는 "같은 부품의 비싼 값"이 아니라 <b>검색어에 걸린 아무
    # 물건의 최고가</b>다 — 실측에서 "i5-14400" 이 223,000~1,853,730원으로 잡혔고(뒤쪽은 그
    # CPU 가 든 다른 상품), 백엔드가 그 중간값 103만원을 CPU 한 개 단가로 썼다. 중앙값은
    # 그 꼬리에 끌려가지 않는다.
    mid = (prices[len(prices) // 2] if len(prices) % 2
           else (prices[len(prices) // 2 - 1] + prices[len(prices) // 2]) // 2)
    # 최저가를 낸 소스를 그대로 실어 _merge_estimates 의 priceSource 가 정확하게 나오게 한다
    # (멀티소스 리졸버는 danawa·enuri·itmaya 어디서든 후보를 줄 수 있다).
    return {"category": part["category"], "option": part["name"], "product": cheapest["name"],
            "qty": part["qty"], "low": prices[0], "high": prices[-1], "mid": mid,
            "inferred": not identity["strong"], "role": role,
            "source": cheapest.get("source", "danawa"),
            # 백엔드가 규격서 원문과 대조할 재료. 우리는 판정하지 않는다.
            "named": bool(part.get("named")), "evidence": part.get("evidence", ""),
            "qtyBasis": part.get("qtyBasis", "llm"),
            "discoveredFrom": part.get("discoveredFrom"), "discovery": part.get("discovery"),
            "searchUnavailable": bool(part.get("searchUnavailable"))}


def _unit_text(unit: dict) -> str:
    """이 기종만의 문구. 색인 매칭을 기종 안에 가둘 때 쓴다 — 다른 기종의 낱말이 새면
    사무용 PC 규격서 한 줄 때문에 GPU 서버 섀시가 딸려 들어온다(실측 오탐과 같은 모양)."""
    parts = unit.get("parts") or []
    return " ".join([str(unit.get("label") or ""),
                     *(str(p.get("name") or "") for p in parts),
                     *(str(p.get("evidence") or "") for p in parts)])


async def _build_unit(unit: dict, spec_text: str, item_name: str, single: bool) -> dict:
    """한 기종을 완성한다 — 사양→모델 승격 · 가격 조회 · 완제품 판정 · 베어본 명시.

    이 함수가 사용자가 요구한 네 가지를 한 기종 안에서 모두 만든다:
      1) 사양밖에 없으면 그 사양에 해당하는 **부품(모델명)을 찾아 명시**한다(`_resolve_spec_only`).
      2) 그 이름으로 **값을 조회**한다(`_price_part` → 다나와·에누리·색인).
      3) 같은 구성의 **완제품이 있으면 함께 싣는다**(`find_prebuilt_comparables`).
      4) **베어본이 잡히면 몸통으로 세우고** 나머지 부품을 그 아래 나열한다(`role="base"`).
    """
    # 설계 2번 — 사양만 적힌 부품은 값을 묻기 전에 모델명부터 알아낸다.
    # 이 단계가 없으면 사양 문자열이 그대로 쇼핑몰 검색어가 되어 엉뚱한 물건이 최저가로 앉는다.
    parts = list(await asyncio.gather(*(_resolve_spec_only(p) for p in unit["parts"])))

    # 완제품 판정 — 이 구성이 "완본체를 사는 것"이면 부품 합보다 완제품 최저가가 맞다.
    # 부품 가격 조회와 병렬로 돌린다(둘 다 다나와를 치지만 독립적이다).
    group_name = (unit.get("label") or item_name
                  or (spec_text.strip().split("\n", 1)[0][:80] if spec_text else ""))
    web_rows, prebuilt = await asyncio.gather(
        asyncio.gather(*(_price_part(p) for p in parts)),
        find_prebuilt_comparables({"name": group_name,
                                   "components": [{"name": p["name"]} for p in parts]}),
    )

    # ITMAYA 색인(정형·결정적)이 이 기종을 알아보면 함께 세운다. 기종이 하나뿐이면 규격서
    # 전문으로 보고, 여럿이면 그 기종의 문구로만 본다.
    #
    # 부품 목록의 출처는 **규격서(LLM)뿐**이다. 색인은 그 부품의 값을 대주는 카탈로그로만
    # 쓴다(`index_adds_parts=False`) — 예외는 규격서에서 부품을 하나도 못 뽑은 경우로,
    # 그때는 색인이 유일한 출처이므로 슬롯을 채우게 둔다.
    itmaya = _estimate_from_itmaya(spec_text if single else _unit_text(unit))
    rows = _merge_rows(list(itmaya.get("breakdown") or []) if itmaya.get("matched") else [],
                       list(web_rows), index_adds_parts=not parts)

    base = next((r for r in rows if r.get("role") == "base"), None)
    priced = [r for r in rows if r.get("low") is not None]
    low, high = _sum_priced(rows)
    mid = _sum_mid(rows)
    sources = {r.get("source") for r in rows if r.get("source")}
    unit_qty = unit["unitQty"]

    # 구성 형태 — 화면과 백엔드가 "이걸 어떻게 사는 물건인가"를 한 낱말로 알 수 있어야 한다.
    form = ("prebuilt" if prebuilt.get("isPrebuilt")
            else "barebone-plus-parts" if base is not None
            else "parts")
    return {
        "unitId": unit["unitId"], "label": unit.get("label", ""),
        "unitQty": unit_qty, "unitQtyBasis": unit.get("unitQtyBasis", "llm"),
        "form": form,
        # 1대 단가. `mid` 는 행 대표값(중앙값)의 합이지 low·high 의 중간이 아니다.
        "low": low, "high": high, "mid": mid,
        # 이 기종 전체(= 1대 단가 × 대수)
        "lineLow": low * unit_qty, "lineHigh": high * unit_qty, "lineMid": mid * unit_qty,
        "hasBase": base is not None,
        "baseProduct": (base or {}).get("product") or (base or {}).get("option") or None,
        "allPriced": bool(rows) and len(priced) == len(rows),
        "gpuCount": sum(r["qty"] for r in rows if _canon_cat(r.get("category")) == "gpu"),
        "priceSource": "hybrid" if len(sources) > 1 else (next(iter(sources), None) or "danawa"),
        "parts": rows,
        # 완제품 후보는 **버리지 않고 함께 싣는다** — 부품 합과 어느 쪽이 싼지는 사람이 본다.
        "prebuilt": {
            "isPrebuilt": prebuilt.get("isPrebuilt", False),
            "score": prebuilt.get("score", 0),
            "reason": prebuilt.get("reason", ""),
            "comparables": prebuilt.get("comparables", []),
        },
    }


# ── 병합 (베어본 + 부품) ─────────────────────────────────────────────────────
# 카테고리 이름이 색인(Processor/Memory/Storage)과 웹(CPU/RAM/SSD/HDD)에서 다르다.
# 같은 부품을 두 번 세지 않으려면 하나의 축으로 모아 비교해야 한다.
_CANON_CAT = {
    "processor": "cpu", "cpu": "cpu",
    "gpu": "gpu", "vga": "gpu", "그래픽카드": "gpu",
    "memory": "ram", "ram": "ram", "메모리": "ram",
    "storage": "storage", "ssd": "storage", "hdd": "storage", "저장장치": "storage",
    # 색인의 System 과 웹의 베어본은 같은 자리(장비 몸통)다 — 한쪽으로 모아야 중복 계상을 막는다.
    "system": "base", "베어본": "base", "본체": "base", "chassis": "base", "barebone": "base",
}


def _canon_cat(cat: str) -> str:
    return _CANON_CAT.get(str(cat or "").strip().lower(), str(cat or "").strip().lower())


def _dedup_key(row: dict) -> tuple[str, str]:
    """(정규 카테고리, 모델) — 같은 부품을 색인·웹 양쪽에서 중복 계상하지 않기 위한 키."""
    cat = _canon_cat(row.get("category"))
    name = str(row.get("option") or row.get("product") or "")
    ident = derive_product_identity(name)
    model = (ident.get("model") or "").lower() or re.sub(r"[^a-z0-9가-힣]+", "", name.lower())
    return cat, model


_MODEL_TOKEN = re.compile(r"[a-z0-9]{3,}")


def _model_tokens(row: dict) -> set[str]:
    """행 이름에서 숫자 낀 토큰(모델코드·용량). 카탈로그마다 이름이 달라도 이건 겹친다."""
    name = f"{row.get('option') or ''} {row.get('product') or ''}".lower()
    return {t for t in _MODEL_TOKEN.findall(name) if any(c.isdigit() for c in t)}


def _sum_priced(rows: list[dict]) -> tuple[int, int]:
    low = sum(r["low"] * r["qty"] for r in rows if r.get("low") is not None)
    high = sum((r["high"] if r.get("high") is not None else r["low"]) * r["qty"]
               for r in rows if r.get("low") is not None)
    return low, high


def row_mid(row: dict) -> int:
    """행의 대표 단가. 소스가 중앙값을 실어 보냈으면 그것을, 아니면 최저·최고의 중간을 쓴다.

    중간값을 쓰면 검색 잡음(같은 검색어에 걸린 비싼 다른 물건)이 그대로 단가가 된다 —
    `_price_part` 주석의 실측이 그 예다. 중앙값이 있으면 언제나 그쪽이 낫다.
    """
    if row.get("low") is None:
        return 0
    mid = row.get("mid")
    if isinstance(mid, int) and mid > 0:
        return mid
    high = row["high"] if row.get("high") is not None else row["low"]
    return (row["low"] + high) // 2


def _sum_mid(rows: list[dict]) -> int:
    return sum(row_mid(r) * r["qty"] for r in rows if r.get("low") is not None)


def _merge_rows(itmaya_rows: list[dict], web_rows: list[dict],
                index_adds_parts: bool = True) -> list[dict]:
    """색인(ITMAYA)과 웹(다나와·에누리)을 **한 기종의** 부품 목록으로 합친다.

    같은 부품이 양쪽에 있으면 색인(정형·결정적)을 신뢰해 하나만 남기고, 진 쪽은
    `alternatives` 로 접어 화면에서 비교할 수 있게 한다.

    <b>기종 안에서만 접는다.</b> 호출부가 기종별로 부르므로, 다른 기종에 같은 CPU 가 들어가도
    서로를 지우지 않는다 — 예전에는 축이 없어 A형·B형의 같은 부품이 중복으로 삭제됐다.

    <p><b>`index_adds_parts=False` 면 색인은 값만 대주고 부품을 <i>만들지</i> 않는다.</b>
    `_estimate_from_itmaya` 는 GPU 서버 신호가 켜지면 System·Processor·GPU·Memory·Storage
    슬롯을 <b>전부</b> 채운다. 그 결과가 규격서와 무관하게 기종에 얹히면 없는 부품이 생긴다 —
    실측: "설계용 워크스테이션(RTX 4000 Ada)" 기종에 랙서버 섀시 615만원과 Xeon 2소켓
    3,403만원이 딸려 들어와 1대 단가가 5,255만원이 됐다(규격서는 둘 다 요구하지 않았다).
    부품 목록의 출처는 규격서뿐이다. 색인은 그 부품의 값을 대주는 카탈로그다.
    """
    # 색인 우선(베어본·정형 부품) → 웹으로 색인이 못 담은 부품을 채운다.
    merged: list[dict] = []
    folded_index_rows: set[int] = set()   # 웹 행과 짝이 맞은 색인 행(= 규격서가 요구한 부품)
    for row in itmaya_rows:
        if not any(_dedup_key(k) == _dedup_key(row) for k in merged):
            merged.append(row)

    # 웹 행은 **모델 문자열이 같은지**로 거르면 안 된다. 두 카탈로그가 같은 부품을 다르게
    # 부르기 때문이다 — 실측에서 색인의 "Genoa 9354 DP/UP 32C/64T" 와 웹의 "AMD EPYC 9354"
    # 가 서로 다른 모델로 읽혀 **CPU·RAM·스토리지가 각각 두 번 계상**됐다(총액 +40%).
    # 이름은 달라도 **모델코드·용량 토큰**은 겹친다("9354"). 같은 정규 카테고리 안에서
    # 그 토큰이 하나라도 겹치면 같은 부품으로 본다.
    #
    # 토큰 겹침은 **소스가 다를 때만** 적용한다. 한 소스 안의 서로 다른 부품(OS용 SSD·데이터용
    # SSD)까지 접으면 정상적인 다중 부품이 사라진다 — 그건 이 병합이 풀 문제가 아니다.
    for row in web_rows:
        key = _dedup_key(row)
        tokens = _model_tokens(row)
        twin = next((k for k in merged
                     if _dedup_key(k) == key
                     or (k.get("source") != row.get("source")
                         and _canon_cat(k.get("category")) == _canon_cat(row.get("category"))
                         and tokens & _model_tokens(k))), None)
        if twin is not None:
            folded_index_rows.add(id(twin))
            # **접되 버리지는 않는다.** 어느 카탈로그가 맞는지는 사람이 판단할 문제다 —
            # 색인은 정형이지만 오래됐고(stale), 웹은 신선하지만 엉뚱한 매물을 물 수 있다.
            # 접힌 후보를 alternatives 로 실어 화면에서 비교·교체할 수 있게 한다.
            # 값·소스·수량이 똑같은 후보는 고를 이유가 없다(같은 부품이 두 경로로 들어온 것).
            # 비교할 것이 없는 줄을 화면에 세우면 진짜 선택지가 묻힌다.
            same = (row.get("source") == twin.get("source") and row.get("low") == twin.get("low")
                    and row.get("qty") == twin.get("qty"))
            if not same:
                twin.setdefault("alternatives", []).append({
                    "option": row.get("option"), "product": row.get("product"),
                    "qty": row.get("qty"), "low": row.get("low"), "high": row.get("high"),
                    "source": row.get("source"), "evidence": row.get("evidence", ""),
                    "qtyBasis": row.get("qtyBasis", "llm"), "named": bool(row.get("named")),
                    "reason": "same-part-other-catalog",
                })
            # 접기 전에 웹 행이 가진 **근거**를 색인 행으로 옮긴다. 색인 이름은 카탈로그 표기라
            # 규격서 문구와 겹치지 않아, 근거 없이 두면 백엔드 대조에서 통째로 떨어진다.
            if row.get("evidence") and not twin.get("evidence"):
                twin["evidence"] = row["evidence"]
                twin["named"] = bool(row.get("named"))
            # 값은 색인(정형·결정적)을 쓰되, **수량은 규격서 원문에서 읽은 쪽**이 이긴다.
            # 색인 행의 수량은 규칙 추정이라 "x 4" 같은 표기를 놓친다(실측: GPU 4장 → 1).
            #
            # 웹 행의 수량을 그대로 옮기면 안 된다 — 그 수는 **웹 행의 이름 기준**이다.
            # 색인 이름으로 다시 읽어야 한다: 웹이 모듈(64GB)로 2 를 셌어도 색인 이름이
            # 킷(128GB)이면 1 이다(실측: 128GB 킷이 256GB 로 불어났다).
            if row.get("evidence") and twin.get("qtyBasis") != "evidence":
                adopted = qty_from_evidence(row["evidence"], str(twin.get("option") or ""),
                                            str(twin.get("category") or ""))
                if adopted is not None:
                    twin["qty"] = adopted
                    twin["qtyBasis"] = "evidence"
            continue
        merged.append(row)

    # 규격서가 요구하지 않은 색인 행은 뺀다(위 docstring). 웹 행과 짝이 맞은 색인 행만 남는다 —
    # 그 행들은 "규격서가 요구한 부품을 색인 값으로 매긴 것"이라 근거를 갖는다.
    if not index_adds_parts:
        merged = [r for r in merged
                  if r.get("source") != "itmaya" or id(r) in folded_index_rows]

    # 베어본이 맨 위로 오게 정렬(base → part). 나머지 순서는 유지.
    merged.sort(key=lambda r: 0 if r.get("role") == "base" else 1)
    return merged


def _merge_estimates(itmaya: dict, web: dict) -> dict:
    """봉투 단위 병합 — 기종 축이 없던 시절의 계약. `_merge_rows` 의 얇은 껍데기다."""
    itmaya_rows = list(itmaya.get("breakdown") or []) if itmaya.get("matched") else []
    web_rows = list(web.get("breakdown") or []) if web.get("matched") else []
    if not itmaya_rows and not web_rows:
        # 둘 다 실패 — 더 구체적인 사유(웹)를 우선하되 gpuCount 는 살린다.
        reason = web.get("reason") or itmaya.get("reason") or "부품을 식별하지 못했습니다."
        out = {"matched": False, "reason": reason}
        if itmaya.get("gpuCount") or web.get("gpuCount"):
            out["gpuCount"] = itmaya.get("gpuCount") or web.get("gpuCount")
        return out

    merged = _merge_rows(itmaya_rows, web_rows)
    low, high = _sum_priced(merged)
    has_base = any(r.get("role") == "base" for r in merged)
    gpu_count = sum(r["qty"] for r in merged if _canon_cat(r.get("category")) == "gpu")
    # 가격을 낸 행의 소스만 센다 — 가격 없는 행(source=None)은 priceSource 를 흐리면 안 된다.
    sources = {r.get("source") for r in merged if r.get("source")}
    price_source = "hybrid" if len(sources) > 1 else (next(iter(sources), None) or "danawa")

    out = {
        "matched": True, "low": low, "high": high, "mid": (low + high) // 2,
        "gpuCount": gpu_count, "hasBase": has_base, "breakdown": merged,
        "allPriced": all(r.get("low") is not None for r in merged),
        "currency": "KRW", "priceSource": price_source,
    }
    if isinstance(web.get("prebuilt"), dict):
        out["prebuilt"] = web["prebuilt"]   # 완제품 판정·후보는 웹 경로가 만든다
    return out


def _envelope(units: list[dict]) -> dict:
    """기종들 → 공고 하나의 봉투.

    <b>`low`·`high`·`mid` 는 예나 지금이나 "1대 단가"다.</b> 백엔드가
    `원가 = 단가 × 수량`(DealCalculator)으로 쓰기 때문에 이 뜻을 바꾸면 조용히 두 번 곱해진다.
    기종이 여럿이면 단일 단가라는 것이 원래 없으므로 **총액을 총 대수로 나눈 혼합 단가**를
    그 자리에 넣고, 진짜 총액은 `totalLow`·`totalMid`·`totalHigh` 로 따로 싣는다.
    """
    total_units = sum(u["unitQty"] for u in units) or 1
    total_low = sum(u["lineLow"] for u in units)
    total_high = sum(u["lineHigh"] for u in units)
    total_mid = sum(u["lineMid"] for u in units)
    # 평평한 목록 — 예전 계약(breakdown[])을 그대로 쓰는 화면·검증기를 위해 남긴다.
    # 각 행에 어느 기종의 것인지(`unitId`)를 달아 보내 기종 축을 잃지 않게 한다.
    flat = [{**row, "unitId": unit["unitId"], "unitLabel": unit.get("label", ""),
             "unitQty": unit["unitQty"]}
            for unit in units for row in unit["parts"]]
    sources = {r.get("source") for r in flat if r.get("source")}
    return {
        "matched": True,
        "low": total_low // total_units, "high": total_high // total_units,
        "mid": total_mid // total_units,
        "totalLow": total_low, "totalHigh": total_high, "totalMid": total_mid,
        "totalUnits": total_units,
        "gpuCount": sum(u["gpuCount"] for u in units),
        "hasBase": any(u["hasBase"] for u in units),
        "allPriced": all(u["allPriced"] for u in units),
        "units": units,
        "breakdown": flat,
        "currency": "KRW",
        "priceSource": "hybrid" if len(sources) > 1 else (next(iter(sources), None) or "danawa"),
        # 봉투 수준의 완제품 판정은 **기종이 하나일 때만** 뜻이 있다. 여럿이면 기종마다 다르므로
        # 화면이 `units[].prebuilt` 를 봐야 한다.
        "prebuilt": units[0]["prebuilt"] if len(units) == 1 else {
            "isPrebuilt": False, "score": 0, "reason": "multi-unit", "comparables": []},
    }


async def estimate_unit_cost(payload: dict) -> dict:
    """규격서 텍스트 → 공고 하나의 부품·가격 구성.

    LLM 은 **기종별 부품 목록**만 만든다. 그 뒤는 전부 규칙이다 —
    사양→모델 탐색, 값 조회, 완제품 후보, 베어본 명시, 색인 병합, 합산.
    """
    spec_text = str(payload.get("specText") or "").strip()
    if not spec_text:
        return {"matched": False, "reason": "규격서 텍스트가 없습니다."}

    # `itemName`(공고명)은 완제품 판정의 핵심 입력이다 — 백엔드가 실어 보내는데 여기서 버리면
    # 판정이 규격서 **첫 줄**("물품구매 규격서")로 이뤄져 신호가 하나도 안 켜진다(실측 score 0).
    item_name = str(payload.get("itemName") or "").strip()

    units_raw = await _extract_units(spec_text)
    if not units_raw:
        # 부품을 못 뽑아도 색인이 GPU 서버를 알아볼 수 있다 — 그 경로는 살려 둔다.
        itmaya = _estimate_from_itmaya(spec_text)
        if not itmaya.get("matched"):
            return {"matched": False,
                    "reason": "규격서에서 가격을 매길 하드웨어 부품을 찾지 못했습니다."}
        units_raw = [{"unitId": "A", "label": item_name, "unitQty": 1,
                      "unitQtyBasis": "llm", "parts": []}]

    built = list(await asyncio.gather(*(
        _build_unit(unit, spec_text, item_name, single=len(units_raw) == 1) for unit in units_raw)))
    units = [u for u in built if u["parts"]]
    if not units:
        return {"matched": False, "reason": "부품을 식별했지만 어디에서도 가격을 찾지 못했습니다.",
                "gpuCount": sum(p["qty"] for u in units_raw for p in u["parts"]
                                if p["category"] == "GPU")}
    return _envelope(units)


if __name__ == "__main__":
    # ── 수량 교차검증 ────────────────────────────────────────────────────────
    q = qty_from_evidence
    assert q("CPU: AMD EPYC 9354 32Core 3.25GHz x2", "AMD EPYC 9354", "CPU") == 2
    assert q("GPU: NVIDIA RTX PRO 6000 96GB GDDR7 x 4", "NVIDIA RTX PRO 6000 96GB", "GPU") == 4
    assert q("NVMe: 7.68TB NVMe ENT x4 (데이터용)", "7.68TB NVMe ENT", "SSD") == 4
    # 모듈 개수: 이름이 모듈(64GB)이면 2, 총량(128GB)이면 이미 곱해져 있으니 1
    ram = "RAM: 128GB Registered ECC DDR5-6400 (64GB x 2)"
    assert q(ram, "64GB Registered ECC DDR5-6400", "RAM") == 2, "모듈 이름 → 개수"
    assert q(ram, "128GB Registered ECC DDR5-6400", "RAM") == 1, "총량 이름 → 1(두 번 세지 않는다)"
    # 카탈로그 이름은 총량과 구성을 함께 적는다 — 대표 용량(맨 앞)이 총량이면 킷 하나다.
    assert q(ram, "128GB DDR5 Registered ECC PC5 6400 (64GB x2)", "RAM") == 1, "킷 이름 → 1"
    # PCIe 레인 표기는 수량이 아니다
    assert q("M.2 NVMe PCIe 5.0x4 (128GT/s), TLC, 4TB", "4TB NVMe", "SSD") is None
    assert q("PCIe Gen5 x16 슬롯", "NVMe SSD", "SSD") is None
    # 몸통은 개수가 없으면 한 대. 부품은 모르면 건드리지 않는다(None → LLM 값 유지)
    assert q("본체: 2U 랙마운트 서버, ASUS ESC4000A-E12", "ASUS ESC4000A-E12", "베어본") == 1
    assert q("네트워크: 10GbE 2port, 1GbE 4port", "10GbE 2port", "네트워크") is None
    assert q("", "무엇이든", "CPU") is None
    assert q("서버 x999", "서버", "CPU") is None, "터무니없는 수량은 읽지 않는다"

    idx = _load_index()
    assert idx and "System" in idx["byCategory"], "ITMAYA 색인 로드 실패"

    # ── 베어본은 색인을 먼저 본다 ─────────────────────────────────────────────
    base = _base_from_index({"category": "베어본", "name": "ASUS ESC4000A-E12", "qty": 1})
    assert base and base["role"] == "base" and base["source"] == "itmaya", base
    assert base["product"] == "ESC4000A-E12" and base["low"] > 0, base
    # 모델이 없는 뭉뚱그린 질의는 색인에서 아무 섀시나 집어 오지 않는다
    assert _base_from_index({"category": "베어본", "name": "ASUS GPU 서버", "qty": 1}) is None
    assert _base_from_index({"category": "베어본", "name": "사무용 컴퓨터 본체", "qty": 1}) is None
    # GPU 서버 규격서 → ITMAYA 경로
    r = _estimate_from_itmaya("GPU 서버, NVIDIA H200 8장, ESC8000-E12, DDR5 512GB")
    print("ITMAYA:", r.get("matched"), r.get("mid"), "gpuCount", r.get("gpuCount"))
    assert _parse_gpu_count("H200 8장") == 8
    assert not _is_gpu_server_text("사무용 노트북 30대")
    # 회귀(2026-08): 맨 '워크스테이션' 만으론 GPU 서버 아님 — 암호 워크스테이션·노트북·보드 오탐 차단
    assert not _is_gpu_server_text("암호 알고리즘 구현용 워크스테이션, 노트북 및 전력 파형 측정용 보드 구매")
    assert not _is_gpu_server_text("워크스테이션 2대, 노트북 3대")
    # 진짜 가속기가 있으면 여전히 켜진다
    assert _is_gpu_server_text("GPU 워크스테이션 H100 4장")
    assert _is_gpu_server_text("딥러닝 서버 RTX PRO 6000 x8")
    # 오탐이던 물품은 ITMAYA 경로를 아예 타지 않는다(웹 부품추출로 감)
    assert not _estimate_from_itmaya("암호 알고리즘 워크스테이션, 노트북, 전력 파형 측정 보드").get("matched")

    # ── 병합: 베어본(색인) + 부품(웹) 함께 나열, 중복 없음 ──────────────────────
    itmaya = {"matched": True, "breakdown": [
        {"category": "System", "option": "ESC8000-E12", "product": "ESC8000-E12", "qty": 1,
         "low": 10_000_000, "high": 10_000_000, "role": "base", "source": "itmaya"},
        {"category": "GPU", "option": "NVIDIA H200 141GB", "product": "H200", "qty": 8,
         "low": 5_000_000, "high": 5_000_000, "role": "part", "source": "itmaya"},
    ]}
    web = {"matched": True, "breakdown": [
        {"category": "GPU", "option": "NVIDIA H200 141GB", "product": "다나와 H200", "qty": 8,
         "low": 4_900_000, "high": 5_100_000, "role": "part", "source": "danawa"},   # 색인과 중복
        {"category": "SSD", "option": "삼성 990 PRO 4TB", "product": "990 PRO", "qty": 2,
         "low": 500_000, "high": 600_000, "role": "part", "source": "danawa"},        # 색인이 못 담은 부품
    ], "prebuilt": {"isPrebuilt": False}}
    # 카탈로그마다 이름이 달라도(색인 "Genoa 9354 …" vs 웹 "AMD EPYC 9354") 같은 부품이면 접힌다.
    cross = _merge_estimates(
        {"matched": True, "breakdown": [
            {"category": "Processor", "option": "Genoa 9354 DP/UP 32C/64T 3.25G 256M 280W SP5",
             "product": "9354", "qty": 1, "low": 5_395_000, "high": 5_395_000, "role": "part",
             "source": "itmaya"},
            {"category": "GPU", "option": "NVIDIA Blackwell RTX PRO 6000 MAX-Q", "product": "RTX PRO 6000",
             "qty": 1, "low": 19_298_000, "high": 19_298_000, "role": "part", "source": "itmaya"},
        ]},
        {"matched": True, "breakdown": [
            {"category": "CPU", "option": "AMD EPYC 9354", "product": "EPYC 9354", "qty": 2,
             "low": 2_282_450, "high": 2_282_450, "role": "part", "source": "danawa",
             "qtyBasis": "evidence", "evidence": "CPU: AMD EPYC 9354 32Core x2", "named": True},
            {"category": "GPU", "option": "NVIDIA RTX PRO 6000 Blackwell 96GB", "product": "RTX PRO 6000",
             "qty": 4, "low": 21_299_990, "high": 21_299_990, "role": "part", "source": "danawa",
             "qtyBasis": "evidence", "evidence": "GPU: NVIDIA RTX PRO 6000 x 4", "named": True},
            {"category": "SSD", "option": "960GB NVMe ENT", "product": "PM983", "qty": 2,
             "low": 114_190, "high": 114_190, "role": "part", "source": "danawa"},
        ]})
    cpu_rows = [r for r in cross["breakdown"] if _canon_cat(r["category"]) == "cpu"]
    assert len(cpu_rows) == 1, f"이름이 달라도 같은 CPU 는 한 번만 세야 한다: {cpu_rows}"
    assert cpu_rows[0]["low"] == 5_395_000, "값은 색인(정형)을 쓴다"
    assert cpu_rows[0]["qty"] == 2 and cpu_rows[0]["qtyBasis"] == "evidence", "수량은 원문이 이긴다"
    assert cpu_rows[0]["evidence"], "근거는 색인 행으로 옮겨진다 — 없으면 백엔드 대조에서 떨어진다"
    alts = cpu_rows[0]["alternatives"]
    assert len(alts) == 1 and alts[0]["source"] == "danawa", alts
    assert alts[0]["low"] == 2_282_450 and alts[0]["qty"] == 2, "접힌 후보는 제 값·제 수량으로 남는다"
    gpu_rows = [r for r in cross["breakdown"] if _canon_cat(r["category"]) == "gpu"]
    assert len(gpu_rows) == 1 and gpu_rows[0]["qty"] == 4, gpu_rows
    assert any(_canon_cat(r["category"]) == "storage" for r in cross["breakdown"]), "겹치지 않는 부품은 남는다"

    # 색인은 값을 대줄 뿐 부품을 만들지 않는다 — 규격서가 요구하지 않은 슬롯은 얹히지 않는다.
    # 실측 회귀: "설계용 워크스테이션(RTX 4000 Ada)" 에 랙서버 섀시·Xeon 2소켓이 딸려 들어와
    # 1대 단가가 5,255만원이 됐다.
    priced_only = _merge_rows(itmaya["breakdown"], web["breakdown"], index_adds_parts=False)
    assert not any(r["source"] == "itmaya" and _canon_cat(r["category"]) == "base"
                   for r in priced_only), "규격서가 안 부른 섀시는 얹히지 않는다"
    gpu_only = [r for r in priced_only if _canon_cat(r["category"]) == "gpu"]
    assert len(gpu_only) == 1 and gpu_only[0]["source"] == "itmaya", "요구한 부품은 색인 값으로 남는다"
    assert gpu_only[0]["alternatives"][0]["source"] == "danawa", "진 후보는 비교용으로 남는다"
    assert any(_canon_cat(r["category"]) == "storage" for r in priced_only), "웹 전용 부품도 남는다"

    m = _merge_estimates(itmaya, web)
    assert m["matched"] and m["hasBase"], m
    assert m["breakdown"][0]["role"] == "base", "베어본이 맨 위"
    assert len([x for x in m["breakdown"] if _canon_cat(x["category"]) == "gpu"]) == 1, "GPU 중복 제거"
    assert any(_canon_cat(x["category"]) == "storage" for x in m["breakdown"]), "웹 부품(SSD) 합류"
    assert m["gpuCount"] == 8 and m["priceSource"] == "hybrid", m
    assert m["low"] == 10_000_000 + 5_000_000 * 8 + 500_000 * 2, m["low"]
    assert "prebuilt" in m
    # 베어본 없이 웹 부품만 있을 때도 동작
    only_web = _merge_estimates({"matched": False, "reason": "not-gpu-server"}, web)
    assert only_web["matched"] and not only_web["hasBase"], only_web
    # 둘 다 실패
    both_fail = _merge_estimates({"matched": False, "reason": "not-gpu-server"},
                                 {"matched": False, "reason": "부품 없음"})
    assert not both_fail["matched"] and both_fail["reason"] == "부품 없음", both_fail

    # ── 기종 축: 발주 대수와 1대 부품 수량은 다른 축이다 ─────────────────────────
    assert unit_qty_from_spec("사무용 PC 30대 구매") == [30]
    assert unit_qty_from_spec("가. A형 20대\n나. B형 5대") == [20, 5]
    assert unit_qty_from_spec("GPU 서버 2식 납품") == [2]
    assert unit_qty_from_spec("메모리 대역폭 확보") == [], "'대'가 낱말 안에 있으면 대수가 아니다"
    # LLM 이 발주 대수를 부품 수량에 곱해 보내는 사고 — 나누어떨어지면 되돌린다
    assert repair_part_qty(30, 30) == (1, "unit-divided"), "30대 × 1개 → 1"
    assert repair_part_qty(60, 30) == (2, "unit-divided"), "30대 × 2개 → 2"
    assert repair_part_qty(2, 30) == (2, "llm"), "정상 범위는 건드리지 않는다"
    assert repair_part_qty(40, 30)[1] == "clamped", "안 나누어떨어지면 모르는 것이다"
    # 수량 근거가 원문에 있으면 그것이 이긴다(교차검증이 LLM 산술을 이긴다)
    p = _normalize_part({"category": "CPU", "name": "AMD EPYC 9354", "qty": 60,
                         "evidence": "CPU: AMD EPYC 9354 x2", "named": True}, 30)
    assert p["qty"] == 2 and p["qtyBasis"] == "evidence", p
    p2 = _normalize_part({"category": "GPU", "name": "H200", "qty": 16, "evidence": ""}, 8)
    assert p2["qty"] == 16 and p2["qtyBasis"] == "llm", p2
    assert _normalize_part({"category": "소프트웨어", "name": "Windows 11", "qty": 1}, 1) is None

    # ── 봉투: 1대 단가와 총액을 섞지 않는다 ──────────────────────────────────────
    def _unit(uid, qty, low, high, **kw):
        return {"unitId": uid, "label": kw.get("label", ""), "unitQty": qty, "unitQtyBasis": "spec",
                "form": kw.get("form", "parts"), "low": low, "high": high, "mid": (low + high) // 2,
                "lineLow": low * qty, "lineHigh": high * qty, "lineMid": (low + high) // 2 * qty,
                "hasBase": kw.get("hasBase", False), "baseProduct": None, "allPriced": True,
                "gpuCount": 0, "priceSource": "danawa",
                "parts": [{"category": "CPU", "option": "i5-14400", "qty": 1, "low": low,
                           "high": high, "role": "part", "source": "danawa"}],
                "prebuilt": {"isPrebuilt": False, "score": 0, "reason": "", "comparables": []}}

    env = _envelope([_unit("A", 20, 447_500, 447_500), _unit("B", 5, 2_795_000, 2_795_000)])
    assert env["totalMid"] == 447_500 * 20 + 2_795_000 * 5, env["totalMid"]
    assert env["totalUnits"] == 25
    assert env["mid"] == env["totalMid"] // 25, "기종이 여럿이면 mid 는 혼합 단가다"
    assert len(env["units"]) == 2, "기종은 접히지 않는다"
    assert {r["unitId"] for r in env["breakdown"]} == {"A", "B"}, "평평한 목록도 기종을 잃지 않는다"
    # 기종이 하나면 예전 뜻 그대로 — mid 는 1대 단가고, 총액은 그 × 대수다
    one = _envelope([_unit("A", 30, 400_000, 500_000)])
    assert one["mid"] == 450_000 and one["totalMid"] == 450_000 * 30, one
    assert one["prebuilt"]["isPrebuilt"] is False
    print("app/estimate.py: OK")
