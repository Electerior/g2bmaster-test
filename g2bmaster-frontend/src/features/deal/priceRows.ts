/*
 * 가격표 행 모델과 순수 계산. 컴포넌트(PriceTable)와 분리한다 — 같은 파일에서 함수와
 * 컴포넌트를 함께 내보내면 HMR 이 그 모듈을 컴포넌트 모듈로 못 보고 화면 상태를 날린다
 * (notReadyContext.ts·columnClass.ts 와 같은 이유).
 *
 * 행 모델은 백엔드 saved_notice.price_rows 계약과 같은 모양이라 저장 시 가공이 필요 없다.
 */

export interface PriceRow {
  /** GPU·CPU·RAM 등. 자유 입력. */
  category?: string;
  /** 품목·모델명. */
  name: string;
  qty: number;
  /** 확정 단가(원). AI 추정 최저가를 기본값으로 채우고 사람이 고친다. */
  unitPrice: number;
  /** AI 가 가격을 특정하지 못해 추정으로 채운 행인지. 표에 표식만 남긴다. */
  inferred?: boolean;
  /** 'base'(베어본/완본체 베이스) | 'part'(부품). 표에서 베어본을 구분해 보여 준다. */
  role?: 'base' | 'part';
  /**
   * 백엔드가 이 행을 총액에서 뺀 사유. 되살려 표에 넣었을 때만 채워진다 —
   * 사람이 "왜 이 부품이 빠졌는지" 모르고 합계에 넣는 것을 막는다.
   */
  rejectReason?: string;
  /** 사양→모델 탐색기가 막혀 있었다. 근거 없음과 구분해 표시한다. */
  searchUnavailable?: boolean;
  /** 'itmaya'(가격표 색인·오래됨) | 'danawa' | 'enuri'. 어느 카탈로그의 값인지. */
  source?: string;
  /** 수량 출처. 'llm' 은 규격서에서 확인되지 않은 수량이다. */
  qtyBasis?: 'evidence' | 'llm';
  /**
   * 이 자리를 두고 겨룬 다른 후보들. 두 곳에서 온다:
   *   - AI 병합 — 같은 부품을 색인과 웹이 다르게 부른 경우(`alternatives`)
   *   - 백엔드 검증 — 같은 규격서 줄을 가리켜 총액에서 뺀 행(`duplicate-row`)
   * 어느 쪽이든 <b>사람이 비교해서 고를 문제</b>라 버리지 않고 여기 담는다.
   */
  alternatives?: PriceCandidate[];
  /**
   * 어느 기종(장비 유형)의 부품인가. 한 규격서가 사무용 PC 20대와 워크스테이션 5대를
   * 함께 요구하면 기종은 둘이다.
   */
  unitId?: string;
  /** 규격서가 그 기종을 부르는 이름. 표의 묶음 머리에 쓴다. */
  unitLabel?: string;
  /**
   * 그 기종을 <b>몇 대</b> 납품하는가. `qty`(1대에 몇 개)와 <b>다른 축</b>이다.
   * 둘을 곱하는 곳은 합계 계산 한 곳뿐이어야 한다 — 어디서든 한 번 더 곱하면 원가가
   * 그 배수만큼 부풀고, 그 오차는 화면에서 정상적인 숫자로 보인다.
   */
  unitQty?: number;
}

/** 한 자리를 두고 겨루는 후보 하나. 표에서 현재 행과 자리를 맞바꿀 수 있다. */
export interface PriceCandidate {
  name: string;
  qty: number;
  unitPrice: number;
  source?: string;
  qtyBasis?: 'evidence' | 'llm';
  inferred?: boolean;
  /** 왜 후보로 밀려났는지 — 'same-part-other-catalog' | 'duplicate-row' 등. */
  reason?: string;
}

/**
 * 저장용 행 — 후보(alternatives)를 떼어낸다.
 *
 * `saved_notice.price_rows` 는 <b>사람이 확정한 견적</b>이다. 고르지 않은 후보까지 넣으면
 * 저장 JSON 이 불어나고, 백엔드가 그 JSON 으로 만드는 검색 텍스트에 채택하지 않은 상품명이
 * 섞여 저장 공고 검색이 엉뚱하게 걸린다. 비교는 화면에서 끝나는 일이다.
 */
export function toSavedRows(rows: PriceRow[]): Array<Record<string, unknown>> {
  return rows.map(({ alternatives: _alternatives, ...keep }) => keep as Record<string, unknown>);
}

export function rowAmount(row: PriceRow): number {
  return Math.max(0, Math.round((Number(row.qty) || 0) * (Number(row.unitPrice) || 0)));
}

/** 행 금액의 단순 합 — <b>장비 1대 기준</b>이다. 납품 대수는 여기 들어가지 않는다. */
export function priceTotal(rows: PriceRow[]): number {
  return rows.reduce((sum, r) => sum + rowAmount(r), 0);
}

/** 표에서 한 기종을 이루는 묶음. `rows[].index` 는 원본 배열의 자리다(편집이 그 자리를 고친다). */
export interface UnitGroup {
  unitId: string;
  label: string;
  unitQty: number;
  rows: Array<{ row: PriceRow; index: number }>;
  /** 이 기종 1대의 부품 합. */
  unitCost: number;
  /** 이 기종 전체 = 1대 단가 × 대수. */
  lineTotal: number;
}

/**
 * 행을 기종별로 묶는다. 기종 축이 없는 행(예전 응답·사람이 손으로 넣은 행)은 한 묶음으로 간다.
 *
 * <p>원본 순서를 지킨다 — 표는 이 순서로 그려지고 편집은 원본 자리(index)를 고친다.
 */
export function groupByUnit(rows: PriceRow[]): UnitGroup[] {
  const groups = new Map<string, UnitGroup>();
  rows.forEach((row, index) => {
    const key = row.unitId ?? '';
    let group = groups.get(key);
    if (!group) {
      group = {
        unitId: key,
        label: row.unitLabel ?? '',
        unitQty: Math.max(1, Number(row.unitQty) || 1),
        rows: [],
        unitCost: 0,
        lineTotal: 0,
      };
      groups.set(key, group);
    }
    group.rows.push({ row, index });
  });
  for (const group of groups.values()) {
    group.unitCost = group.rows.reduce((sum, r) => sum + rowAmount(r.row), 0);
    group.lineTotal = group.unitCost * group.unitQty;
  }
  return [...groups.values()];
}

/**
 * 발주 전체 금액 = Σ(기종 1대 단가 × 그 기종 대수).
 *
 * <p>{@link priceTotal} 과 다르다. 그쪽은 1대 값이고 이쪽은 납품 전체다 — 기종이 하나이고
 * 대수가 1 이면 둘이 같아진다.
 */
export function orderTotal(rows: PriceRow[]): number {
  return groupByUnit(rows).reduce((sum, g) => sum + g.lineTotal, 0);
}

/** 행이 총액에서 빠진 사유 → 표에 띄울 짧은 한국어 표식. */
export const REJECT_LABEL: Record<string, string> = {
  'no-evidence-in-spec': '근거없음',
  'duplicate-row': '중복',
  // 옛 이름. 저장된 분석 결과(saved_notice)에 남아 있어 계속 읽어 준다.
  'category-conflict': '중복',
  'zero-priced-row': '0원',
  'inferred-row': '추정',
  unpriced: '가격없음',
  // 값 위생 — 1대에 들어갈 수 없는 수량(발주 대수를 곱한 흔적)과 망가진 단가.
  'part-qty-out-of-range': '수량이상',
  'bad-price': '가격이상',
};

/**
 * estimatedUnitCost.breakdown → 편집 표 초기 행. 최저가(low)를 기본 단가로.
 *
 * <b>백엔드가 총액에서 뺀 행({@code acceptedForCost === false})은 기본으로 채우지 않는다.</b>
 * 규격서와 대조해 근거가 없다고 판정된 부품을 표에 미리 넣어 두면, 사람이 그대로 저장해
 * 근거 없는 합계가 `saved_notice.price_rows` 에 눌러앉는다. 필요하면 화면에서 되살린다.
 */
export interface BreakdownEntry {
  category?: string;
  option?: string;
  product?: string | null;
  qty: number;
  low?: number | null;
  inferred?: boolean;
  role?: 'base' | 'part';
  source?: string;
  qtyBasis?: 'evidence' | 'llm';
  acceptedForCost?: boolean;
  rejectReason?: string;
  evidenceInSpec?: boolean;
  /**
   * 무엇으로 근거가 확인됐나. `unverifiable` 은 <b>근거가 없다는 뜻이 아니다</b> —
   * 라벨에 대조할 식별자(용량·모델코드)가 없어 판정 자체를 못 한 것이다("미들타워 케이스").
   */
  evidenceBasis?: 'quote' | 'token' | 'unverifiable' | 'none';
  searchUnavailable?: boolean;
  duplicateGroup?: number;
  /** 기종 축 — AI 가 기종별로 부품을 보내면 채워진다. */
  unitId?: string;
  unitLabel?: string;
  unitQty?: number;
  alternatives?: Array<{
    option: string;
    product?: string | null;
    qty: number;
    low?: number | null;
    source?: string;
    qtyBasis?: 'evidence' | 'llm';
    reason?: string;
  }>;
}

function toRow(b: BreakdownEntry): PriceRow {
  return {
    // 베어본 행은 구분(category)을 '베어본'으로 못박아 부품과 한눈에 갈린다.
    category: b.role === 'base' ? '베어본' : b.category,
    name: b.product || b.option || '',
    qty: b.qty || 1,
    unitPrice: b.low ?? 0,
    inferred: b.inferred,
    role: b.role,
    source: b.source,
    qtyBasis: b.qtyBasis,
    rejectReason: b.acceptedForCost === false ? b.rejectReason : undefined,
    searchUnavailable: b.searchUnavailable,
    unitId: b.unitId,
    unitLabel: b.unitLabel,
    unitQty: b.unitQty,
    alternatives: (b.alternatives ?? []).map((a) => ({
      name: a.product || a.option || '',
      qty: a.qty || 1,
      unitPrice: a.low ?? 0,
      source: a.source,
      qtyBasis: a.qtyBasis,
      reason: a.reason ?? 'same-part-other-catalog',
    })),
  };
}

export function rowsFromBreakdown(
  breakdown: BreakdownEntry[],
  { includeRejected = false }: { includeRejected?: boolean } = {},
): PriceRow[] {
  /*
   * 백엔드가 중복으로 뺀 행(duplicate-row)은 **따로 세우지 않고 이긴 행의 후보로 접는다.**
   * 같은 자리를 두고 겨룬 것이라 표에 나란히 두면 두 번 사는 것처럼 보이고, 아예 빼 버리면
   * 무엇과 겨뤘는지 알 수 없어 사람이 고를 수가 없다. AI 병합이 만든 alternatives 와
   * 같은 자리에 담아 화면에서 한 가지 방법으로 비교하게 한다.
   */
  const dupLosers = new Map<number, BreakdownEntry[]>();
  for (const b of breakdown) {
    if (b.acceptedForCost === false && b.rejectReason === 'duplicate-row' && b.duplicateGroup != null) {
      dupLosers.set(b.duplicateGroup, [...(dupLosers.get(b.duplicateGroup) ?? []), b]);
    }
  }

  return breakdown
    .filter((b) => !(b.rejectReason === 'duplicate-row' && b.duplicateGroup != null && b.acceptedForCost === false))
    .filter((b) => includeRejected || b.acceptedForCost !== false)
    .map((b) => {
      const row = toRow(b);
      const folded = b.duplicateGroup != null ? (dupLosers.get(b.duplicateGroup) ?? []) : [];
      return folded.length
        ? {
            ...row,
            alternatives: [
              ...(row.alternatives ?? []),
              ...folded.map(
                (f): PriceCandidate => ({
                  name: f.product || f.option || '',
                  qty: f.qty || 1,
                  unitPrice: f.low ?? 0,
                  source: f.source,
                  qtyBasis: f.qtyBasis,
                  inferred: f.inferred,
                  reason: f.rejectReason ?? 'duplicate-row',
                }),
              ),
            ],
          }
        : row;
    });
}
