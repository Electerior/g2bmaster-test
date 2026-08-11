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
}

export function rowAmount(row: PriceRow): number {
  return Math.max(0, Math.round((Number(row.qty) || 0) * (Number(row.unitPrice) || 0)));
}

export function priceTotal(rows: PriceRow[]): number {
  return rows.reduce((sum, r) => sum + rowAmount(r), 0);
}

/** 행이 총액에서 빠진 사유 → 표에 띄울 짧은 한국어 표식. */
export const REJECT_LABEL: Record<string, string> = {
  'no-evidence-in-spec': '근거없음',
  'category-conflict': '중복',
  'zero-priced-row': '0원',
  'inferred-row': '추정',
  unpriced: '가격없음',
};

/**
 * estimatedUnitCost.breakdown → 편집 표 초기 행. 최저가(low)를 기본 단가로.
 *
 * <b>백엔드가 총액에서 뺀 행({@code acceptedForCost === false})은 기본으로 채우지 않는다.</b>
 * 규격서와 대조해 근거가 없다고 판정된 부품을 표에 미리 넣어 두면, 사람이 그대로 저장해
 * 근거 없는 합계가 `saved_notice.price_rows` 에 눌러앉는다. 필요하면 화면에서 되살린다.
 */
export function rowsFromBreakdown(
  breakdown: Array<{
    category?: string;
    option?: string;
    product?: string | null;
    qty: number;
    low?: number | null;
    inferred?: boolean;
    role?: 'base' | 'part';
    acceptedForCost?: boolean;
    rejectReason?: string;
    evidenceInSpec?: boolean;
    searchUnavailable?: boolean;
  }>,
  { includeRejected = false }: { includeRejected?: boolean } = {},
): PriceRow[] {
  return breakdown
    .filter((b) => includeRejected || b.acceptedForCost !== false)
    .map((b) => ({
      // 베어본 행은 구분(category)을 '베어본'으로 못박아 부품과 한눈에 갈린다.
      category: b.role === 'base' ? '베어본' : b.category,
      name: b.product || b.option || '',
      qty: b.qty || 1,
      unitPrice: b.low ?? 0,
      inferred: b.inferred,
      role: b.role,
      rejectReason: b.acceptedForCost === false ? b.rejectReason : undefined,
      searchUnavailable: b.searchUnavailable,
    }));
}
