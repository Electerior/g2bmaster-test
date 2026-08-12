/*
 * 후보 비교·교체 — 두 카탈로그가 같은 부품을 다르게 부를 때 어느 쪽이 맞는지는 사람이
 * 판단할 문제다. 화면은 고르는 수단만 주고 값을 대신 정하지 않는다.
 */
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { PriceTable } from './PriceTable';
import type { PriceRow } from './priceRows';

const ROW: PriceRow = {
  category: 'CPU', name: '9354', qty: 2, unitPrice: 5_395_000, role: 'part', source: 'itmaya',
  qtyBasis: 'evidence',
  alternatives: [{
    name: 'EPYC 9354', qty: 2, unitPrice: 2_282_450, source: 'danawa',
    qtyBasis: 'evidence', reason: 'same-part-other-catalog',
  }],
};

describe('PriceTable 후보 비교', () => {
  it('후보를 고르면 현재 행과 자리를 맞바꾼다 — 되돌릴 수 있어야 한다', () => {
    const onChange = vi.fn();
    render(<PriceTable rows={[ROW]} onChange={onChange} />);

    const pick = screen.getByLabelText('9354 후보 선택');
    expect(pick).toHaveTextContent('다른 후보 1개와 비교…');
    // 고르기 전에 비교할 재료가 다 보인다 — 이름·단가·소스·수량.
    expect(pick).toHaveTextContent('EPYC 9354 · 2,282,450원 · danawa · 2개');

    fireEvent.change(pick, { target: { value: '0' } });

    const [next] = onChange.mock.calls.at(-1) ?? [];
    expect(next[0]).toMatchObject({ name: 'EPYC 9354', unitPrice: 2_282_450, source: 'danawa' });
    // 밀어내지 않고 바꿔 끼운다 — 원래 값이 후보 자리로 들어가 되돌릴 수 있다.
    expect(next[0].alternatives).toEqual([
      expect.objectContaining({ name: '9354', unitPrice: 5_395_000, source: 'itmaya' }),
    ]);
  });

  it('읽기 전용에서는 바꾸지 못하고 후보를 사유와 함께 보여 준다', () => {
    render(<PriceTable rows={[ROW]} readOnly />);
    expect(screen.queryByLabelText('9354 후보 선택')).toBeNull();
    expect(screen.getByText(/EPYC 9354 · 2,282,450원 · danawa · 2개 — 다른 카탈로그의 같은 부품/))
      .toBeTruthy();
  });

  it('규격서에서 확인되지 않은 수량은 표식을 단다 — AI 가 센 값이다', () => {
    render(<PriceTable rows={[{ ...ROW, qtyBasis: 'llm', alternatives: [] }]} readOnly />);
    expect(screen.getByText('수량미확인')).toBeTruthy();
  });
});

/*
 * 기종 축 — "부품 9종"만 적힌 표는 존재하지 않는 장비 한 대의 목록으로 읽힌다.
 * 무엇을 몇 대 만드는지가 표에서 바로 보여야 한다.
 */
const UNIT_ROWS: PriceRow[] = [
  {
    category: '베어본', name: 'ESC4000A-E12', qty: 1, unitPrice: 5_258_000, role: 'base',
    unitId: 'A', unitLabel: 'GPU 서버', unitQty: 2,
  },
  {
    category: 'GPU', name: 'RTX PRO 6000', qty: 4, unitPrice: 20_000_000, role: 'part',
    unitId: 'A', unitLabel: 'GPU 서버', unitQty: 2,
  },
  {
    category: 'CPU', name: 'i5-14400', qty: 1, unitPrice: 250_000, role: 'part',
    unitId: 'B', unitLabel: '사무용 PC', unitQty: 20,
  },
];

describe('PriceTable 기종 축', () => {
  it('기종마다 대수·1대 단가·소계를 머리에 세우고, 발주 합계를 따로 낸다', () => {
    render(<PriceTable rows={UNIT_ROWS} readOnly />);

    expect(screen.getByText('GPU 서버')).toBeTruthy();
    expect(screen.getByText('사무용 PC')).toBeTruthy();
    expect(screen.getByText('2대')).toBeTruthy();
    expect(screen.getByText('20대')).toBeTruthy();
    // 부품 합(1대 기준)과 발주 합(대수 반영)은 다른 줄에 있어야 한다.
    expect(screen.getByText('부품 합계')).toBeTruthy();
    const orderTotal = (5_258_000 + 20_000_000 * 4) * 2 + 250_000 * 20;
    expect(screen.getByText(orderTotal.toLocaleString('ko-KR') + '원')).toBeTruthy();
  });

  it('베어본이 있으면 구성 형태를 배지로 알린다 — 몸통에 부품을 더한 구성이다', () => {
    render(<PriceTable rows={UNIT_ROWS} readOnly />);
    expect(screen.getByText('베어본+부품')).toBeTruthy();
  });

  it('같은 구성의 완제품 후보를 부품 합 옆에 붙인다 — 어느 쪽이 싼지 비교하려면 같이 있어야 한다', () => {
    render(
      <PriceTable
        rows={UNIT_ROWS}
        readOnly
        units={[{
          unitId: 'B', label: '사무용 PC', form: 'prebuilt',
          prebuilt: {
            isPrebuilt: true,
            comparables: [{ name: '한성컴퓨터 TFG', priceKrw: 890_000, url: 'https://x' }],
          },
        }]}
      />,
    );
    expect(screen.getByText('완제품')).toBeTruthy();
    expect(screen.getByText('한성컴퓨터 TFG')).toBeTruthy();
  });

  it('기종이 하나이고 대수가 1 이면 예전 그대로 평평한 표다', () => {
    render(<PriceTable rows={[ROW]} readOnly />);
    expect(screen.getByText('합계')).toBeTruthy();
    expect(screen.queryByText('발주 합계 (기종별 대수 반영)')).toBeNull();
  });
});
