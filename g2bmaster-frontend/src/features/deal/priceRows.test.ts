/*
 * 가격표 행 모델 — 특히 **후보 접기**. 같은 자리를 두고 겨룬 행을 표에 나란히 두면 두 번
 * 사는 것처럼 보이고, 아예 빼 버리면 무엇과 겨뤘는지 알 수 없어 사람이 고를 수가 없다.
 * 그래서 이긴 행의 alternatives 로 접는다 — 이 파일이 그 계약을 지킨다.
 */
import { describe, expect, it } from 'vitest';
import {
  groupByUnit, orderTotal, priceTotal, rowsFromBreakdown, toSavedRows,
  type BreakdownEntry,
} from './priceRows';

/** AI 병합이 접은 후보(색인 vs 웹) + 백엔드가 중복으로 뺀 행이 함께 오는 응답. */
const BREAKDOWN: BreakdownEntry[] = [
  {
    category: 'System', option: 'ASUS GPU Server ESC4000A-E12', product: 'ESC4000A-E12',
    qty: 1, low: 5_258_000, role: 'base', source: 'itmaya', qtyBasis: 'evidence',
    acceptedForCost: true, evidenceInSpec: true,
  },
  {
    // 색인 행이 이겼고, 웹의 같은 CPU 가 AI 병합 단계에서 후보로 접혔다.
    category: 'Processor', option: 'Genoa 9354 DP/UP 32C/64T', product: '9354',
    qty: 2, low: 5_395_000, role: 'part', source: 'itmaya', qtyBasis: 'evidence',
    acceptedForCost: true, evidenceInSpec: true,
    alternatives: [{
      option: 'AMD EPYC 9354', product: 'EPYC 9354', qty: 2, low: 2_282_450,
      source: 'danawa', qtyBasis: 'evidence', reason: 'same-part-other-catalog',
    }],
  },
  {
    // 백엔드 검증이 같은 규격서 줄을 가리킨다고 보고 뺀 행 — 아래 RAM 행과 한 묶음(3).
    category: 'RAM', option: '128GB Registered DDR5-6400', product: null,
    qty: 1, low: 7_204_000, role: 'part', source: 'itmaya',
    acceptedForCost: true, evidenceInSpec: true, duplicateGroup: 3,
  },
  {
    category: 'Memory', option: '512GB DDR5 PC5 5600', product: null,
    qty: 1, low: 30_160_000, role: 'part', source: 'danawa',
    acceptedForCost: false, rejectReason: 'duplicate-row', evidenceInSpec: true, duplicateGroup: 3,
  },
  {
    category: 'GPU', option: 'NVIDIA TESLA H100 80GB', product: null,
    qty: 1, low: 50_000_000, role: 'part', source: 'danawa', qtyBasis: 'llm',
    acceptedForCost: false, rejectReason: 'no-evidence-in-spec', evidenceInSpec: false,
  },
];

describe('rowsFromBreakdown', () => {
  it('중복으로 뺀 행은 따로 세우지 않고 이긴 행의 후보로 접는다', () => {
    const rows = rowsFromBreakdown(BREAKDOWN);

    // 채택 3행(베어본·CPU·RAM). 중복 행은 별도 행으로 서지 않는다.
    expect(rows.map((r) => r.name)).toEqual([
      'ESC4000A-E12', '9354', '128GB Registered DDR5-6400',
    ]);
    const ram = rows[2];
    expect(ram.alternatives).toHaveLength(1);
    expect(ram.alternatives?.[0]).toMatchObject({
      name: '512GB DDR5 PC5 5600', unitPrice: 30_160_000, reason: 'duplicate-row',
    });
    // 접힌 후보는 합계에 들어가지 않는다 — 고르기 전까지는 채택된 값만 센다.
    expect(priceTotal(rows)).toBe(5_258_000 + 5_395_000 * 2 + 7_204_000);
  });

  it('AI 병합이 접은 후보(다른 카탈로그의 같은 부품)도 같은 자리에 담는다', () => {
    const cpu = rowsFromBreakdown(BREAKDOWN)[1];
    expect(cpu.source).toBe('itmaya');
    expect(cpu.alternatives).toEqual([{
      name: 'EPYC 9354', qty: 2, unitPrice: 2_282_450, source: 'danawa',
      qtyBasis: 'evidence', reason: 'same-part-other-catalog',
    }]);
  });

  it('includeRejected 면 총액에서 빠진 행도 사유와 함께 보인다 — 중복만 접힌 채로', () => {
    const rows = rowsFromBreakdown(BREAKDOWN, { includeRejected: true });
    expect(rows.map((r) => r.name)).toEqual([
      'ESC4000A-E12', '9354', '128GB Registered DDR5-6400', 'NVIDIA TESLA H100 80GB',
    ]);
    expect(rows[3].rejectReason).toBe('no-evidence-in-spec');
    expect(rows[3].qtyBasis).toBe('llm');
  });

  it('저장할 때는 고르지 않은 후보를 뺀다 — 저장되는 것은 확정한 견적이다', () => {
    const saved = toSavedRows(rowsFromBreakdown(BREAKDOWN));
    expect(saved.every((r) => !('alternatives' in r))).toBe(true);
    expect(saved[1]).toMatchObject({ name: '9354', qty: 2, unitPrice: 5_395_000 });
  });
});

/*
 * 기종 축 — 한 규격서가 사무용 PC 20대와 워크스테이션 5대를 함께 요구한다. 부품 수량(qty)과
 * 납품 대수(unitQty)는 다른 축이고, 둘을 곱하는 곳은 합계 한 곳뿐이어야 한다.
 */
const TWO_UNITS: BreakdownEntry[] = [
  {
    category: 'CPU', option: 'Intel Core i5-14400', product: null, qty: 1, low: 250_000,
    role: 'part', acceptedForCost: true, unitId: 'A', unitLabel: '사무용 PC(A형)', unitQty: 20,
  },
  {
    category: 'RAM', option: '16GB DDR5', product: null, qty: 1, low: 90_000,
    role: 'part', acceptedForCost: true, unitId: 'A', unitLabel: '사무용 PC(A형)', unitQty: 20,
  },
  {
    // 같은 CPU 가 B형에도 들어간다 — 중복이 아니다. 기종 축이 없던 시절엔 한쪽이 삭제됐다.
    category: 'CPU', option: 'Intel Core i5-14400', product: null, qty: 1, low: 250_000,
    role: 'part', acceptedForCost: true, unitId: 'B', unitLabel: '설계용 PC(B형)', unitQty: 5,
  },
  {
    category: 'GPU', option: 'NVIDIA RTX 4000 Ada', product: null, qty: 1, low: 1_800_000,
    role: 'part', acceptedForCost: true, unitId: 'B', unitLabel: '설계용 PC(B형)', unitQty: 5,
  },
];

describe('groupByUnit', () => {
  it('기종별로 묶고 1대 단가와 소계를 따로 낸다', () => {
    const groups = groupByUnit(rowsFromBreakdown(TWO_UNITS));

    expect(groups.map((g) => g.unitId)).toEqual(['A', 'B']);
    expect(groups[0]).toMatchObject({ label: '사무용 PC(A형)', unitQty: 20, unitCost: 340_000 });
    expect(groups[0].lineTotal).toBe(340_000 * 20);
    expect(groups[1]).toMatchObject({ label: '설계용 PC(B형)', unitQty: 5, unitCost: 2_050_000 });
    expect(groups[1].lineTotal).toBe(2_050_000 * 5);
    // 편집이 원본 자리를 고칠 수 있어야 한다 — 묶어도 index 를 잃지 않는다.
    expect(groups[1].rows.map((r) => r.index)).toEqual([2, 3]);
  });

  it('부품 합(1대)과 발주 합(대수 반영)은 다른 숫자다 — 섞으면 원가가 배수로 틀린다', () => {
    const rows = rowsFromBreakdown(TWO_UNITS);
    expect(priceTotal(rows)).toBe(340_000 + 2_050_000);
    expect(orderTotal(rows)).toBe(340_000 * 20 + 2_050_000 * 5);
  });

  it('기종 축이 없는 행(예전 응답·손으로 넣은 행)은 한 묶음으로 간다', () => {
    const rows = rowsFromBreakdown(BREAKDOWN);
    const groups = groupByUnit(rows);
    expect(groups).toHaveLength(1);
    expect(groups[0].unitQty).toBe(1);
    // 대수가 1 이면 발주 합과 부품 합이 같다 — 예전 화면과 숫자가 달라지지 않는다.
    expect(orderTotal(rows)).toBe(priceTotal(rows));
  });
});
