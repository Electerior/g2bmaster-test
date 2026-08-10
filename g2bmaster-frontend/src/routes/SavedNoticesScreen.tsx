/*
 * 저장 공고 — 원본 loadSavedNotices(app.js:2575) + savedNoticeCard(2552).
 *
 * 이 화면의 요점은 **G2B API 를 부르지 않는다**는 것이다. 이미 확정한 견적을 다시 찾는
 * 자리라, 검색어는 저장된 것만 훑는다. 서버가 제목·기관·요약·메모에 더해 가격표의 품목명까지
 * 한 칸에 모아 두므로 "RTX 5090 넣었던 건"으로도 찾힌다.
 *
 * 검색어는 상단 검색창의 AND/OR 태그를 그대로 쓴다 — 이 화면만의 입력칸을 새로 만들지 않는다.
 */
import { useEffect, useState } from 'react';
import {
  useDeleteSavedNotice,
  useSaveNotice,
  useSavedNoticeDetail,
  useSavedNotices,
  type SavedNoticeRow,
} from '@/api';
import { FieldSet } from '@/components/common/FieldSet';
import { Markdown } from '@/components/markdown/Markdown';
import { PanelNotice } from '@/components/feedback/Spinner';
import { StatusBar } from '@/components/table/StatusBar';
import { useSearchCriteria } from '@/features/search/useSearchCriteria';
import { PriceTable } from '@/features/deal/PriceTable';
import { priceTotal, type PriceRow } from '@/features/deal/priceRows';
import '@/components/common/fieldset.css';
import '@/features/deal/deal.css';
import '@/features/saved/saved.css';

/** 세 자릿수 콤마. 값이 없으면 '-'. 원본 comma(app.js:3191). */
function comma(value: string | number | null | undefined): string {
  return value == null || value === '' ? '-' : Number(value).toLocaleString('en-US');
}

function amountText(value: string | number | null | undefined): string {
  return value == null ? '' : `${comma(value)}원`;
}

/** 저장 시각 'YYYY-MM-DDTHH:mm…' → 'YYYY-MM-DD HH:mm'. */
function savedAtText(value: string | null | undefined): string {
  return String(value ?? '')
    .slice(0, 16)
    .replace('T', ' ');
}

interface SavedCardProps {
  row: SavedNoticeRow;
  onDelete: (row: SavedNoticeRow) => void;
  deleting: boolean;
}

function SavedCard({ row, onDelete, deleting }: SavedCardProps) {
  // 실추정가(부가세 포함) − 견적 합계. 양수면 그 구성으로 남고, 음수면 남지 않는다.
  const gap =
    row.real_estimate != null && row.price_total != null
      ? Number(row.real_estimate) - Number(row.price_total)
      : null;

  // 저장된 가격표 열기·편집·재저장. 목록엔 합계·행수만 있어 상세를 따로 부른다.
  const [showTable, setShowTable] = useState(false);
  const [rows, setRows] = useState<PriceRow[] | null>(null);
  const detail = useSavedNoticeDetail(row.bid_ntce_no, row.bid_ntce_ord, { enabled: showTable });
  const save = useSaveNotice();
  useEffect(() => {
    if (detail.data && rows === null) {
      const loaded = Array.isArray(detail.data.price_rows) ? detail.data.price_rows : [];
      setRows(loaded as unknown as PriceRow[]);
    }
  }, [detail.data, rows]);
  const hasTable = (row.price_row_count || 0) > 0;
  const onSaveTable = () => {
    if (!rows) return;
    save.mutate({
      bidNtceNo: row.bid_ntce_no,
      bidNtceOrd: row.bid_ntce_ord,
      title: row.title ?? '',
      insttNm: row.instt_nm ?? undefined,
      amount: row.amount ?? undefined,
      note: row.note ?? undefined,
      priceRows: rows as unknown as Array<Record<string, unknown>>,
      priceTotal: priceTotal(rows),
    });
  };

  return (
    <div className="saved-card">
      <div className="saved-card-head">
        <strong className="saved-card-title">{row.title || row.bid_ntce_no || '-'}</strong>
        <div className="saved-card-actions">
          {hasTable ? (
            <button type="button" className="deal-save" onClick={() => setShowTable((v) => !v)}>
              {showTable ? '표 닫기' : `가격표 ${row.price_row_count}행`}
            </button>
          ) : null}
          <button
            type="button"
            className="saved-del-btn"
            title="저장 목록에서 지웁니다"
            disabled={deleting}
            onClick={() => onDelete(row)}
          >
            {deleting ? '삭제 중…' : '삭제'}
          </button>
        </div>
      </div>

      <div className="ds-grid">
        <FieldSet
          primary={{ label: '발주기관', value: row.instt_nm }}
          secondary={{ label: '입찰마감', value: row.bid_clse_dt }}
        />
        <FieldSet
          primary={{ label: '추정가', value: amountText(row.amount) }}
          secondary={{
            label: '실추정가 (부가세 10% 포함)',
            value: amountText(row.real_estimate),
          }}
        />
        <FieldSet
          primary={{ label: '견적 합계', value: amountText(row.price_total) }}
          secondary={{ label: '가격표 행', value: `${row.price_row_count || 0}행` }}
        />
      </div>

      <div className="saved-gap">
        {gap == null ? null : (
          <span className={`prod-gap ${gap >= 0 ? 'under' : 'over'}`}>
            {gap >= 0 ? '여유' : '초과'} {comma(Math.abs(gap))}
          </span>
        )}
      </div>

      {/* 저장된 가격표 — 열면 상세를 부르고, 편집 후 다시 저장한다. */}
      {showTable ? (
        detail.isPending ? (
          <p className="meta">가격표 불러오는 중…</p>
        ) : detail.error ? (
          <p className="meta" role="alert">가격표를 불러오지 못했습니다.</p>
        ) : (
          <>
            <PriceTable rows={rows ?? []} onChange={setRows} />
            <div className="saved-table-actions">
              <button type="button" className="btn-primary" onClick={onSaveTable}
                disabled={save.isPending || !rows}>
                {save.isPending ? '저장 중…' : save.isSuccess ? '저장됨' : '가격표 저장'}
              </button>
            </div>
          </>
        )
      ) : null}

      {/* 저장 당시 AI 요약 앞부분. 첨부에서 나온 비신뢰 텍스트라 위생 처리를 거친다. */}
      <div className="saved-summary">
        <Markdown>{String(row.summary_preview ?? '').slice(0, 300)}</Markdown>
      </div>

      <div className="saved-meta">
        저장 {savedAtText(row.updated_at)} · 공고번호 {row.bid_ntce_no}
      </div>
    </div>
  );
}

export function SavedNoticesScreen() {
  const { criteria } = useSearchCriteria();
  const deleteMutation = useDeleteSavedNotice();

  useEffect(() => {
    document.title = '저장 공고 — G2B Masters';
  }, []);

  const q = [...criteria.andTerms, ...criteria.orTerms, criteria.insttNm].filter(Boolean).join(' ');
  const saved = useSavedNotices({ q });
  const items = saved.data?.items ?? [];

  return (
    <section className="panel" aria-label="저장 공고">
      <StatusBar
        message={
          q
            ? `저장한 공고에서 "${q}"를 찾습니다 (API 조회 없음).`
            : '담아 둔 공고입니다 (API 조회 없음).'
        }
      />

      {saved.isPending ? (
        <PanelNotice>저장한 공고를 불러오는 중...</PanelNotice>
      ) : saved.error ? (
        <PanelNotice empty>저장 공고 조회 실패: {saved.error.message}</PanelNotice>
      ) : items.length === 0 ? (
        <PanelNotice empty>
          {q
            ? '조건에 맞는 저장 공고가 없습니다.'
            : '아직 담아 둔 공고가 없습니다 — AI 수주 데스크에서 카드의 [저장]을 누르세요.'}
        </PanelNotice>
      ) : (
        <>
          <div className="saved-count">
            저장 공고 {items.length}건{q ? ` · "${q}"` : ''}
          </div>
          <div className="saved-list">
            {items.map((row) => (
              <SavedCard
                key={`${row.bid_ntce_no}-${row.bid_ntce_ord}`}
                row={row}
                deleting={
                  deleteMutation.isPending &&
                  deleteMutation.variables?.bidNtceNo === row.bid_ntce_no
                }
                onDelete={(target) =>
                  deleteMutation.mutate({
                    bidNtceNo: target.bid_ntce_no,
                    ord: target.bid_ntce_ord ?? '',
                  })
                }
              />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
