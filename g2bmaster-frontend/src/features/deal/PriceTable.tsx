/*
 * 편집 가능한 가격표 — AI 가 뽑은 부품 단가(estimatedUnitCost.breakdown)를 사람이 확정한다.
 *
 * AI 추정은 시작점일 뿐이다. 사람이 단가·수량을 고치고 행을 더하거나 빼서 "이 공고의 견적"을
 * 만든다. 확정한 표는 저장 공고(saved_notice.price_rows)에 저장돼, 나중에 그대로 다시 열린다.
 *
 * 행 모델(PriceRow)은 백엔드 price_rows 계약과 같은 모양이다 — 저장할 때 가공이 필요 없다.
 */
import { useMemo } from 'react';
import { fmtMoney } from '@/domain/format';
import { REJECT_LABEL, priceTotal, rowAmount, type PriceRow } from './priceRows';

export function PriceTable({
  rows,
  onChange,
  readOnly = false,
}: {
  rows: PriceRow[];
  onChange?: (rows: PriceRow[]) => void;
  readOnly?: boolean;
}) {
  const total = useMemo(() => priceTotal(rows), [rows]);

  const patch = (i: number, next: Partial<PriceRow>) =>
    onChange?.(rows.map((r, idx) => (idx === i ? { ...r, ...next } : r)));
  const removeRow = (i: number) => onChange?.(rows.filter((_, idx) => idx !== i));
  const addRow = () => onChange?.([...rows, { category: '', name: '', qty: 1, unitPrice: 0 }]);

  return (
    <div className="price-table-wrap">
      <table className="price-table">
        <thead>
          <tr>
            <th>구분</th><th>품목</th><th className="num">수량</th>
            <th className="num">단가(원)</th><th className="num">금액(원)</th>
            {readOnly ? null : <th aria-label="삭제" />}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className={[row.role === 'base' ? 'base' : '', row.inferred ? 'inferred' : '',
              row.rejectReason ? 'rejected' : '']
              .filter(Boolean).join(' ')}>
              <td>
                {readOnly ? (row.category ?? '') : (
                  <input value={row.category ?? ''} onChange={(e) => patch(i, { category: e.target.value })}
                    aria-label="구분" />
                )}
              </td>
              <td>
                {readOnly ? row.name : (
                  <input className="wide" value={row.name} onChange={(e) => patch(i, { name: e.target.value })}
                    aria-label="품목" />
                )}
                {row.inferred ? <span className="price-inferred" title="AI 가 가격을 특정하지 못해 추정한 값">추정</span> : null}
                {row.rejectReason ? (
                  <span className="price-rejected"
                    title="백엔드가 규격서와 대조해 총액에서 뺀 행이다. 합계에 넣으려면 근거를 직접 확인할 것.">
                    {REJECT_LABEL[row.rejectReason] ?? row.rejectReason}
                  </span>
                ) : null}
                {row.searchUnavailable ? (
                  <span className="price-rejected"
                    title="사양으로 모델을 찾는 검색이 일시 차단돼 있었다. 규격서에 없어서가 아니다.">
                    탐색불가
                  </span>
                ) : null}
              </td>
              <td className="num">
                {readOnly ? row.qty : (
                  <input type="number" min={0} value={row.qty}
                    onChange={(e) => patch(i, { qty: Number(e.target.value) })} aria-label="수량" />
                )}
              </td>
              <td className="num">
                {readOnly ? fmtMoney(row.unitPrice) : (
                  <input type="number" min={0} value={row.unitPrice}
                    onChange={(e) => patch(i, { unitPrice: Number(e.target.value) })} aria-label="단가" />
                )}
              </td>
              <td className="num strong">{fmtMoney(rowAmount(row))}</td>
              {readOnly ? null : (
                <td>
                  <button type="button" className="price-row-del" onClick={() => removeRow(i)}
                    aria-label="행 삭제">×</button>
                </td>
              )}
            </tr>
          ))}
          {rows.length === 0 ? (
            <tr><td colSpan={readOnly ? 5 : 6} className="price-empty">부품이 없습니다.</td></tr>
          ) : null}
        </tbody>
        <tfoot>
          <tr>
            <td colSpan={readOnly ? 4 : 4} className="num">합계</td>
            <td className="num strong">{fmtMoney(total)}</td>
            {readOnly ? null : <td />}
          </tr>
        </tfoot>
      </table>
      {readOnly ? null : (
        <button type="button" className="price-add" onClick={addRow}>+ 행 추가</button>
      )}
    </div>
  );
}
