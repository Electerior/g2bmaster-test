/*
 * 단가 DB 표 — 편집 가능한 카탈로그 행 + 행 추가 + 행별 시세 이력.
 *
 * 저장 공고의 가격표(PriceTable)와 달리 이 표는 **서버가 진짜 소유한 행**을 직접 고친다.
 * 수정은 useUpdatePriceRow(PUT), 삭제는 useDeletePriceRow(DELETE), 추가는 useUpsertPriceRow(POST)
 * 로 바로 나가고, 성공하면 목록이 무효화돼 다시 그려진다(api/price.ts onSuccess).
 *
 * 순수 헬퍼(초안 모델·검증·포매팅)는 priceDbRows.ts 에 있다 — HMR 분리(priceRows.ts 와 같은 이유).
 */
import { Fragment, useState } from 'react';
import {
  PRICE_SOURCE_LABELS,
  useDeletePriceRow,
  usePriceHistory,
  useUpdatePriceRow,
  useUpsertPriceRow,
  type PriceCatalogRow,
  type PriceSource,
} from '@/api/price';
import {
  blankPriceRow,
  capturedText,
  draftFromRow,
  priceText,
  toUpdateRequest,
  toUpsertRequest,
  validateDraft,
  type PriceDbRowDraft,
} from './priceDbRows';
import './price.css';

/** 편집·추가 초안의 한 칸을 그리는 입력들 — 편집행과 추가행이 같은 필드를 쓴다. */
function DraftInputs({
  draft,
  onChange,
  editingSource,
}: {
  draft: PriceDbRowDraft;
  onChange: (patch: Partial<PriceDbRowDraft>) => void;
  /** 수정은 출처를 바꿀 수 없다 — 계약(PUT)에 source 가 없다. 표시만 한다. */
  editingSource?: boolean;
}) {
  return (
    <>
      <td>
        {editingSource ? (
          <span className="price-source">{PRICE_SOURCE_LABELS[draft.source]}</span>
        ) : (
          <select
            aria-label="출처"
            value={draft.source}
            onChange={(e) => onChange({ source: e.target.value as PriceSource })}
          >
            {(Object.keys(PRICE_SOURCE_LABELS) as PriceSource[]).map((src) => (
              <option key={src} value={src}>
                {PRICE_SOURCE_LABELS[src]}
              </option>
            ))}
          </select>
        )}
      </td>
      <td>
        <input aria-label="분류" value={draft.category} onChange={(e) => onChange({ category: e.target.value })} />
      </td>
      <td>
        <input aria-label="품명" className="wide" value={draft.name} onChange={(e) => onChange({ name: e.target.value })} />
      </td>
      <td>
        <input aria-label="모델" value={draft.model} onChange={(e) => onChange({ model: e.target.value })} />
      </td>
      <td>
        <input aria-label="규격" className="wide" value={draft.spec} onChange={(e) => onChange({ spec: e.target.value })} />
      </td>
      <td className="num">
        <input
          aria-label="단가"
          type="text"
          inputMode="numeric"
          placeholder="미확인"
          value={draft.priceKrw}
          onChange={(e) => onChange({ priceKrw: e.target.value.replace(/[^0-9]/g, '') })}
        />
      </td>
      <td>
        <input aria-label="링크" value={draft.url} onChange={(e) => onChange({ url: e.target.value })} />
      </td>
      <td>
        <input aria-label="메모" value={draft.note} onChange={(e) => onChange({ note: e.target.value })} />
      </td>
    </>
  );
}

/** 행별 시세 이력 — 펼칠 때만 마운트되므로 usePriceHistory 가 그때 한 번 부른다. */
function PriceHistoryPanel({ row }: { row: PriceCatalogRow }) {
  const history = usePriceHistory({ catalogId: row.id });
  const items = history.data?.items ?? [];

  return (
    <div className="price-history">
      {history.isPending ? (
        <p className="meta">이력 불러오는 중…</p>
      ) : history.error ? (
        <p className="meta" role="alert">이력을 불러오지 못했습니다: {(history.error as Error).message}</p>
      ) : items.length === 0 ? (
        <p className="meta">기록된 시세 이력이 없습니다.</p>
      ) : (
        <table className="price-history-table">
          <thead>
            <tr>
              <th>수집 시각</th>
              <th className="num">단가</th>
              <th>메모</th>
              <th>링크</th>
            </tr>
          </thead>
          <tbody>
            {items.map((entry) => (
              <tr key={entry.id}>
                <td>{capturedText(entry.capturedAt)}</td>
                <td className="num">{priceText(entry.priceKrw)}</td>
                <td>{entry.note ?? '—'}</td>
                <td>
                  {entry.url ? (
                    <a href={entry.url} target="_blank" rel="noopener noreferrer">
                      열기 ↗
                    </a>
                  ) : (
                    '—'
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

interface PriceDbTableProps {
  rows: PriceCatalogRow[];
  /** 새 행 기본 출처 — 필터에서 고른 출처를 이어받는다. */
  defaultSource: PriceSource;
}

const COL_COUNT = 9;

export function PriceDbTable({ rows, defaultSource }: PriceDbTableProps) {
  const update = useUpdatePriceRow();
  const remove = useDeletePriceRow();
  const upsert = useUpsertPriceRow();

  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState<PriceDbRowDraft>(() => blankPriceRow(defaultSource));
  const [openHistory, setOpenHistory] = useState<Set<number>>(new Set());
  const [adding, setAdding] = useState(false);
  const [newDraft, setNewDraft] = useState<PriceDbRowDraft>(() => blankPriceRow(defaultSource));
  const [formError, setFormError] = useState<string | null>(null);

  const startEdit = (row: PriceCatalogRow) => {
    setEditingId(row.id);
    setDraft(draftFromRow(row));
    setFormError(null);
  };

  const saveEdit = () => {
    if (editingId == null) return;
    const err = validateDraft(draft);
    if (err) {
      setFormError(err);
      return;
    }
    update.mutate(
      { id: editingId, ...toUpdateRequest(draft) },
      { onSuccess: () => setEditingId(null) },
    );
  };

  const toggleHistory = (id: number) =>
    setOpenHistory((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const startAdd = () => {
    setNewDraft(blankPriceRow(defaultSource));
    setAdding(true);
    setFormError(null);
  };

  const submitAdd = () => {
    const err = validateDraft(newDraft);
    if (err) {
      setFormError(err);
      return;
    }
    upsert.mutate(toUpsertRequest(newDraft), {
      onSuccess: () => {
        setAdding(false);
        setNewDraft(blankPriceRow(defaultSource));
      },
    });
  };

  return (
    <div className="price-db-wrap">
      {formError ? (
        <p className="price-form-error" role="alert">
          {formError}
        </p>
      ) : null}
      <table className="price-db-table">
        <thead>
          <tr>
            <th>출처</th>
            <th>분류</th>
            <th>품명</th>
            <th>모델</th>
            <th>규격</th>
            <th className="num">단가(원)</th>
            <th>링크</th>
            <th>메모</th>
            <th aria-label="관리" />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const editing = editingId === row.id;
            const historyOpen = openHistory.has(row.id);
            return (
              <Fragment key={row.id}>
                <tr>
                  {editing ? (
                    <DraftInputs
                      draft={draft}
                      editingSource
                      onChange={(patch) => setDraft((prev) => ({ ...prev, ...patch }))}
                    />
                  ) : (
                    <>
                      <td>
                        <span className="price-source">{PRICE_SOURCE_LABELS[row.source]}</span>
                      </td>
                      <td>{row.category || '—'}</td>
                      <td className="price-name">{row.name || '—'}</td>
                      <td>{row.model || '—'}</td>
                      <td className="price-spec" title={row.specPreview}>
                        {row.specPreview || '—'}
                      </td>
                      {/* null 은 '미확인' — 절대 0 으로 그리지 않는다. */}
                      <td className={row.priceKrw == null ? 'num price-unknown' : 'num strong'}>
                        {priceText(row.priceKrw)}
                      </td>
                      <td>
                        {row.url ? (
                          <a href={row.url} target="_blank" rel="noopener noreferrer">
                            열기 ↗
                          </a>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td>{row.note || '—'}</td>
                    </>
                  )}
                  <td className="price-actions">
                    {editing ? (
                      <>
                        <button type="button" className="price-btn" onClick={saveEdit} disabled={update.isPending}>
                          {update.isPending ? '저장 중…' : '저장'}
                        </button>
                        <button type="button" className="price-btn" onClick={() => setEditingId(null)}>
                          취소
                        </button>
                      </>
                    ) : (
                      <>
                        <button type="button" className="price-btn" onClick={() => startEdit(row)}>
                          수정
                        </button>
                        <button
                          type="button"
                          className="price-btn"
                          onClick={() => remove.mutate(row.id)}
                          disabled={remove.isPending && remove.variables === row.id}
                        >
                          삭제
                        </button>
                        <button
                          type="button"
                          className={historyOpen ? 'price-btn active' : 'price-btn'}
                          aria-expanded={historyOpen}
                          onClick={() => toggleHistory(row.id)}
                        >
                          이력
                        </button>
                      </>
                    )}
                  </td>
                </tr>
                {historyOpen ? (
                  <tr className="price-history-row">
                    <td colSpan={COL_COUNT}>
                      <PriceHistoryPanel row={row} />
                    </td>
                  </tr>
                ) : null}
              </Fragment>
            );
          })}

          {rows.length === 0 && !adding ? (
            <tr>
              <td colSpan={COL_COUNT} className="price-db-empty">
                조건에 맞는 단가가 없습니다.
              </td>
            </tr>
          ) : null}

          {adding ? (
            <tr className="price-add-row">
              <DraftInputs
                draft={newDraft}
                onChange={(patch) => setNewDraft((prev) => ({ ...prev, ...patch }))}
              />
              <td className="price-actions">
                <button type="button" className="price-btn" onClick={submitAdd} disabled={upsert.isPending}>
                  {upsert.isPending ? '추가 중…' : '추가'}
                </button>
                <button type="button" className="price-btn" onClick={() => setAdding(false)}>
                  취소
                </button>
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>

      {adding ? null : (
        <button type="button" className="price-db-add" onClick={startAdd}>
          + 행 추가
        </button>
      )}
    </div>
  );
}
