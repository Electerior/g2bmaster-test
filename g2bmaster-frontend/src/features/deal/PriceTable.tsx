/*
 * 편집 가능한 가격표 — AI 가 뽑은 부품 단가(estimatedUnitCost.breakdown)를 사람이 확정한다.
 *
 * AI 추정은 시작점일 뿐이다. 사람이 단가·수량을 고치고 행을 더하거나 빼서 "이 공고의 견적"을
 * 만든다. 확정한 표는 저장 공고(saved_notice.price_rows)에 저장돼, 나중에 그대로 다시 열린다.
 *
 * 행 모델(PriceRow)은 백엔드 price_rows 계약과 같은 모양이다 — 저장할 때 가공이 필요 없다.
 */
import { Fragment, useMemo } from 'react';
import { fmtMoney } from '@/domain/format';
import {
  REJECT_LABEL, groupByUnit, priceTotal, rowAmount,
  type PriceCandidate, type PriceRow, type UnitGroup,
} from './priceRows';

/** 후보가 왜 밀려났는지 → 짧은 한국어. */
const CANDIDATE_REASON: Record<string, string> = {
  'same-part-other-catalog': '다른 카탈로그의 같은 부품',
  'duplicate-row': '규격서의 같은 줄을 가리킴',
};

/** 후보 한 줄의 설명 — 이름 · 단가 · 소스 · 수량. 고르기 전에 비교할 재료를 다 준다. */
function candidateLabel(c: PriceCandidate): string {
  const bits = [c.name, fmtMoney(c.unitPrice), c.source ?? '', `${c.qty}개`];
  return bits.filter(Boolean).join(' · ');
}

/** 기종 하나에 대한 부가 정보 — 구성 형태와 같은 구성의 완제품 후보. AI 가 기종별로 낸다. */
export interface UnitMeta {
  unitId: string;
  label?: string;
  /** 'prebuilt'(완본체를 산다) | 'barebone-plus-parts'(몸통 + 부품) | 'parts'(부품 조달). */
  form?: string;
  prebuilt?: {
    isPrebuilt: boolean;
    comparables?: Array<{ name: string; priceKrw: number; url: string; source?: string }>;
  };
}

const FORM_LABEL: Record<string, string> = {
  prebuilt: '완제품',
  'barebone-plus-parts': '베어본+부품',
  parts: '부품',
};

/**
 * 기종 머리 — 이름 · 구성 형태 · 대수 · 1대 단가 · 소계, 그리고 같은 구성의 완제품 후보.
 *
 * 완제품 후보를 여기 붙이는 이유: 부품을 다 합친 값 바로 옆에 있어야 "부품으로 사는 게 나은가,
 * 완본체로 사는 게 나은가"를 비교할 수 있다. 다른 화면에 두면 아무도 비교하지 않는다.
 */
function unitHead(group: UnitGroup, units: UnitMeta[] | undefined, readOnly: boolean) {
  const meta = units?.find((u) => u.unitId === group.unitId);
  const hasBase = group.rows.some(({ row }) => row.role === 'base');
  const form = meta?.form ?? (hasBase ? 'barebone-plus-parts' : undefined);
  const comparables = meta?.prebuilt?.comparables ?? [];
  return (
    <>
      <tr className="price-unit-head">
        <td colSpan={2}>
          <span className="price-unit-name">
            {group.label || meta?.label || `기종 ${group.unitId || '-'}`}
          </span>
          {form ? <span className="price-unit-form">{FORM_LABEL[form] ?? form}</span> : null}
        </td>
        <td className="num">{group.unitQty}대</td>
        <td className="num">{fmtMoney(group.unitCost)}</td>
        <td className="num strong">{fmtMoney(group.lineTotal)}</td>
        {readOnly ? null : <td />}
      </tr>
      {comparables.length ? (
        <tr className="price-unit-prebuilt">
          <td colSpan={readOnly ? 5 : 6}>
            같은 구성의 완제품 후보:{' '}
            {comparables.slice(0, 3).map((c, k) => (
              <span key={k}>
                {k ? ' · ' : ''}
                <a href={c.url} target="_blank" rel="noreferrer">{c.name}</a> {fmtMoney(c.priceKrw)}
              </span>
            ))}
          </td>
        </tr>
      ) : null}
    </>
  );
}

export function PriceTable({
  rows,
  onChange,
  readOnly = false,
  units,
}: {
  rows: PriceRow[];
  onChange?: (rows: PriceRow[]) => void;
  readOnly?: boolean;
  /** 기종별 부가 정보. 없으면 표는 부품만 보여 준다(예전과 같다). */
  units?: UnitMeta[];
}) {
  const total = useMemo(() => priceTotal(rows), [rows]);
  const groups = useMemo(() => groupByUnit(rows), [rows]);
  /*
   * 기종 축을 <b>필요할 때만</b> 그린다. 기종이 하나이고 대수가 1 이면 예전과 똑같은 평평한
   * 표다 — 축이 없는 공고(부품 일괄 발주·사람이 손으로 만든 표)에 없는 구조를 씌우지 않는다.
   */
  const showUnits = groups.length > 1 || groups.some((g) => g.unitQty > 1);
  const orderSum = useMemo(() => groups.reduce((sum, g) => sum + g.lineTotal, 0), [groups]);

  const patch = (i: number, next: Partial<PriceRow>) =>
    onChange?.(rows.map((r, idx) => (idx === i ? { ...r, ...next } : r)));
  const removeRow = (i: number) => onChange?.(rows.filter((_, idx) => idx !== i));
  const addRow = () => onChange?.([...rows, { category: '', name: '', qty: 1, unitPrice: 0 }]);

  /*
   * 후보를 고른다 — 지금 행과 자리를 **맞바꾼다**. 밀어내지 않고 바꿔 끼우기 때문에
   * 몇 번이든 되돌릴 수 있다. 어느 카탈로그가 맞는지는 사람이 판단할 문제이므로
   * 화면은 고르는 수단만 주고 값을 대신 정하지 않는다.
   */
  const chooseCandidate = (i: number, pick: number) => {
    const row = rows[i];
    const candidates = row.alternatives ?? [];
    const chosen = candidates[pick];
    if (!chosen) return;
    const current: PriceCandidate = {
      name: row.name, qty: row.qty, unitPrice: row.unitPrice,
      source: row.source, qtyBasis: row.qtyBasis, inferred: row.inferred,
      reason: chosen.reason,
    };
    patch(i, {
      name: chosen.name,
      qty: chosen.qty,
      unitPrice: chosen.unitPrice,
      source: chosen.source,
      qtyBasis: chosen.qtyBasis,
      inferred: chosen.inferred,
      alternatives: candidates.map((c, k) => (k === pick ? current : c)),
    });
  };

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
          {groups.map((group) => (
            <Fragment key={group.unitId}>
              {showUnits ? unitHead(group, units, readOnly) : null}
              {group.rows.map(({ row, index: i }) => (
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
                {row.qtyBasis === 'llm' ? (
                  <span className="price-inferred" title="수량을 규격서 원문에서 확인하지 못했다. AI 가 센 값이다.">
                    수량미확인
                  </span>
                ) : null}
                {row.source ? <span className="price-source">{row.source}</span> : null}
                {/* 같은 자리를 두고 겨룬 후보들. 카탈로그마다 값이 다르므로 사람이 고른다. */}
                {row.alternatives?.length ? (
                  readOnly ? (
                    <ul className="price-alts">
                      {row.alternatives.map((c, k) => (
                        <li key={k}>
                          {candidateLabel(c)}
                          {c.reason ? ` — ${CANDIDATE_REASON[c.reason] ?? c.reason}` : ''}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <select
                      className="price-alt-pick"
                      aria-label={`${row.name} 후보 선택`}
                      value=""
                      onChange={(e) => {
                        if (e.target.value !== '') chooseCandidate(i, Number(e.target.value));
                      }}
                    >
                      <option value="">다른 후보 {row.alternatives.length}개와 비교…</option>
                      {row.alternatives.map((c, k) => (
                        <option key={k} value={k}>{candidateLabel(c)}</option>
                      ))}
                    </select>
                  )
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
            </Fragment>
          ))}
          {rows.length === 0 ? (
            <tr><td colSpan={readOnly ? 5 : 6} className="price-empty">부품이 없습니다.</td></tr>
          ) : null}
        </tbody>
        <tfoot>
          <tr>
            {/* 기종이 여럿이면 "1대 합계"라는 것이 없다 — 그 자리에 부품 합만 적고
                발주 전체는 아래 줄에 따로 낸다. 둘을 한 숫자로 섞으면 어느 쪽인지 알 수 없다. */}
            <td colSpan={4} className="num">{showUnits ? '부품 합계' : '합계'}</td>
            <td className="num strong">{fmtMoney(total)}</td>
            {readOnly ? null : <td />}
          </tr>
          {showUnits ? (
            <tr className="price-order-total">
              <td colSpan={4} className="num">발주 합계 (기종별 대수 반영)</td>
              <td className="num strong">{fmtMoney(orderSum)}</td>
              {readOnly ? null : <td />}
            </tr>
          ) : null}
        </tfoot>
      </table>
      {readOnly ? null : (
        <button type="button" className="price-add" onClick={addRow}>+ 행 추가</button>
      )}
    </div>
  );
}
