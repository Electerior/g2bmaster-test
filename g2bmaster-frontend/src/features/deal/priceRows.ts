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
}

export function rowAmount(row: PriceRow): number {
  return Math.max(0, Math.round((Number(row.qty) || 0) * (Number(row.unitPrice) || 0)));
}

export function priceTotal(rows: PriceRow[]): number {
  return rows.reduce((sum, r) => sum + rowAmount(r), 0);
}

/** estimatedUnitCost.breakdown → 편집 표 초기 행. 최저가(low)를 기본 단가로. */
export function rowsFromBreakdown(
  breakdown: Array<{
    category?: string;
    option?: string;
    product?: string | null;
    qty: number;
    low?: number | null;
    inferred?: boolean;
    role?: 'base' | 'part';
  }>,
): PriceRow[] {
  return breakdown.map((b) => ({
    // 베어본 행은 구분(category)을 '베어본'으로 못박아 부품과 한눈에 갈린다.
    category: b.role === 'base' ? '베어본' : b.category,
    name: b.product || b.option || '',
    qty: b.qty || 1,
    unitPrice: b.low ?? 0,
    inferred: b.inferred,
    role: b.role,
  }));
}
