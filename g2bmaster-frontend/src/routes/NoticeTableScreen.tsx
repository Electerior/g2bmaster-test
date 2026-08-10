/*
 * 공고 표 화면 4종(발주 계획 · 사전 규격 · 입찰 공고 · 입찰 결과).
 * 네 화면은 컬럼과 엔드포인트만 다르고 구조가 같아서 kind 로 갈라 쓰는 한 컴포넌트다.
 *
 * 원본 search()(app.js:1241) 가 하던 일을 그대로 옮기되, 조회는 URL 조건에서 파생된다.
 * '검색' 을 눌러야만 조회가 나가던 원본과 달리 조건이 확정되는 순간 조회가 나간다 —
 * 조건이 곧 주소이므로, 그 주소를 연 사람에게는 이미 결과가 그려져 있어야 하기 때문이다.
 */
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { BidAnnounceItem, BidPlanItem, NoticeSearchQuery, PreSpecItem } from '@/api';
import { Cell, type CellActions } from '@/components/table/Cell';
import { DataTable } from '@/components/table/DataTable';
import { Pagination } from '@/components/table/Pagination';
import { PER_PAGE_ALL } from '@/components/table/perPage';
import { StatusBar } from '@/components/table/StatusBar';
import { EmptyState, PendingState } from '@/components/feedback/EmptyState';
import {
  columnsFor,
  defaultSortForKind,
  SCREENS,
  type ColumnDef,
  type NoticeTableKind,
} from '@/domain/columns';
import {
  ATTACHMENT_SCAN_READY,
  buildQuery,
  isBlockingRelevant,
  isPastOnlyAnnounceRange,
  shouldScanAttachments,
  useSearchCriteria,
  type BidType,
} from '@/features/search/useSearchCriteria';
import { useNotReady } from '@/components/feedback/notReadyContext';
import { crossSearchTo } from '@/features/notices/crossSearch';
import { highlightKeywords } from '@/features/notices/highlight';
import { rowKeyForItem, type ScannedRow } from '@/features/notices/rows';
import { useAttachmentScan } from '@/features/notices/useAttachmentScan';
import { pendingOf, useNoticeQuery } from '@/features/notices/useNoticeSearch';
import { NoticeDrawer, type DrawerSelection } from '@/features/notices/drawer/NoticeDrawer';
import '@/features/search/search.css';

const BID_TYPES: readonly BidType[] = ['', '물품', '용역', '공사'];
const TYPE_LABEL: Readonly<Record<string, string>> = {
  '': '전체',
  물품: '물품',
  용역: '용역',
  공사: '공사',
};

interface NoticeTableScreenProps {
  kind: NoticeTableKind;
}

/** 입찰 불가 조항 '?' 표식 — 원본 blockingBadge(app.js:3900). native <details> 그대로 쓴다. */
function BlockingBadge({ reasons, excerpt }: { reasons: string[]; excerpt: string }) {
  return (
    <details className="blocking-info">
      <summary title="입찰 불가 조항 사유 보기">?</summary>
      <div className="blocking-pop">
        <strong>입찰 불가 조항 감지</strong>
        <ul>
          {reasons.length ? (
            reasons.map((reason) => <li key={reason}>{reason}</li>)
          ) : (
            <li>경쟁 제한 조항 감지</li>
          )}
        </ul>
        {excerpt ? <p className="blocking-excerpt">…{excerpt}…</p> : null}
        <span className="blocking-note">
          첨부문서 전수조사 기준 — &apos;입찰 불가 조항 자동 제외&apos;를 켜면 이 공고는 목록에서
          빠집니다.
        </span>
      </div>
    </details>
  );
}

export function NoticeTableScreen({ kind }: NoticeTableScreenProps) {
  const screen = SCREENS[kind];
  const columns = columnsFor(kind);
  const navigate = useNavigate();
  const location = useLocation();
  const { criteria, setCriteria, setPage } = useSearchCriteria();
  const [selection, setSelection] = useState<DrawerSelection | null>(null);
  const { notify } = useNotReady();

  useEffect(() => {
    document.title = `${screen.label} — G2B Masters`;
  }, [screen.label]);

  /*
   * 파일 키워드는 검색창에서 막혀 있지만 조건의 출처는 URL 이다 — 예전에 공유된 링크에는
   * 아직 `?file=...` 이 남아 있다. 지금은 백엔드에 스캔 표면이 없어 그 조건이 적용되지
   * 않으므로, 조용히 무시하지 않고 준비 중임을 알린다. 사용자가 친 조건이 소리 없이
   * 사라지면 "키워드를 넣었는데 왜 다 나오지" 가 된다.
   */
  const fileKeywordsIgnored = !ATTACHMENT_SCAN_READY && criteria.fileKeywords.length > 0;
  useEffect(() => {
    if (fileKeywordsIgnored) notify('첨부문서 전수조사');
  }, [fileKeywordsIgnored, notify]);

  // 화면을 옮기면 서랍은 닫는다 — 옛 공고의 서랍이 새 표 위에 남으면 안 된다.
  useEffect(() => setSelection(null), [kind]);

  // 정렬은 URL 에 없으면 화면 기본값을 쓴다(원본 defaultSortForTab).
  const sort = useMemo(
    () =>
      criteria.sortKey
        ? { key: criteria.sortKey, dir: criteria.sortDir }
        : defaultSortForKind(kind),
    [criteria.sortKey, criteria.sortDir, kind],
  );

  // 첨부 전수조사가 필요한 검색이면 페이지가 아니라 후보 전체를 받는다(pageNo=0).
  const scanMode = shouldScanAttachments(criteria, kind);

  const baseQuery = useMemo(() => {
    const query = buildQuery(criteria, kind, scanMode ? { pageNo: 0 } : {});
    query.sortKey = sort.key;
    query.sortDir = sort.dir;
    // 서버는 '전체'를 문자열 'all' 로 받는다. 숫자로 보내면 500건에서 잘린다(server.js:1015).
    if (criteria.perPage >= PER_PAGE_ALL) query.perPage = 'all';
    return query;
  }, [criteria, kind, scanMode, sort]);

  const main = useNoticeQuery(kind, baseQuery);
  const pending = pendingOf(main.data);

  /*
   * 마감 공고 포함 재조회 — 원본 app.js:1303.
   * '마감 전만'이 켜진 채 키워드 검색을 했는데 0건이면, 사용자가 알고 싶은 것은 "그런 공고가
   * 없다"가 아니라 "이미 마감됐다"인 경우가 많다. 조용히 activeOnly 없이 한 번 더 조회하고,
   * 결과가 있으면 그것을 보여주며 재조회했음을 알린다.
   * 원본은 이때 state.activeOnly 를 꺼 버렸지만 여기서는 조건을 건드리지 않는다 —
   * URL 은 사용자의 의도이고, 재조회는 화면의 판단이다.
   *
   * **원본과 다른 점 하나**: 원본은 조건에 `!attachmentScan` 이 붙어 있었는데, 입찰 공고에서는
   * shouldScanAttachments 가 늘 참이라(isBlockingRelevant 가 참) 이 분기가 한 번도 실행되지
   * 않는 죽은 코드였다. 여기서는 그 조건을 빼 실제로 동작하게 한다 — 첨부 전수조사 경로에서도
   * "후보가 0건"이면 사용자가 알고 싶은 것은 똑같기 때문이다.
   */
  const hasKeywordQuery =
    criteria.andTerms.length > 0 ||
    criteria.orTerms.length > 0 ||
    Boolean(criteria.insttNm) ||
    Boolean(criteria.corpNm);
  const wantsRetry =
    kind === 'bid-announce' &&
    criteria.activeOnly &&
    !criteria.crossBidNtceNo &&
    hasKeywordQuery &&
    !pending &&
    main.isSuccess &&
    Number(main.data?.totalCount ?? 0) === 0;

  const retryQuery = useMemo<NoticeSearchQuery>(() => {
    const query = buildQuery(criteria, kind, {
      activeOnly: false,
      ...(scanMode ? { pageNo: 0 } : {}),
    });
    query.sortKey = sort.key;
    query.sortDir = sort.dir;
    if (criteria.perPage >= PER_PAGE_ALL) query.perPage = 'all';
    return query;
  }, [criteria, kind, scanMode, sort]);

  const retry = useNoticeQuery(kind, retryQuery, { enabled: wantsRetry });
  const includedClosedFallback = wantsRetry && Number(retry.data?.totalCount ?? 0) > 0;
  const data = includedClosedFallback ? retry.data : main.data;
  const query = includedClosedFallback ? retry : main;

  const scan = useAttachmentScan({
    kind,
    query: baseQuery,
    items: data?.items,
    fileKeywords: criteria.fileKeywords,
    excludeBlockingClauses: criteria.excludeBlockingClauses,
    scanBlocking: isBlockingRelevant(kind, criteria.mode),
    enabled: scanMode && !pending && Boolean(data?.items?.length),
  });

  const rows: ScannedRow[] = scanMode ? (scan.data?.rows ?? []) : (data?.items ?? []);
  const loading =
    query.isPending || (scanMode && scan.isPending && Boolean(data?.items?.length));

  const totalPages = Math.max(1, Math.ceil((data?.totalCount ?? 0) / (data?.numOfRows || 20)));

  /* ─── 셀 동작 ──────────────────────────────────────────────────────────── */
  const actions: CellActions = {
    openNotice: (row) => setSelection({ variant: 'notice', item: row as BidAnnounceItem }),
    openPlan: (row) => setSelection({ variant: 'plan', item: row as BidPlanItem }),
    openSpec: (row) => setSelection({ variant: 'spec', item: row as PreSpecItem }),
    crossSearch: (target, seed) => navigate(crossSearchTo(target, seed, location.search)),
  };

  const renderCell = (row: ScannedRow, column: ColumnDef, columnIndex: number): ReactNode => {
    // 발주 계획의 '조달요청명'은 컬럼 정의에 fmt 가 없지만 서랍을 여는 링크다(원본 app.js:3943).
    const fmt = kind === 'bid-plan' && column.key === 'bizNm' ? 'plan-link' : column.fmt;
    const cell = <Cell value={row[column.key]} fmt={fmt} row={row} actions={actions} />;
    if (columnIndex === 0 && row._blocking) {
      return (
        <>
          <BlockingBadge reasons={row._blocking.reasons} excerpt={row._blocking.excerpt} />
          {cell}
        </>
      );
    }
    return cell;
  };

  const renderSubRow = (row: ScannedRow, colSpan: number): ReactNode => {
    if (!row._fileExcerpt) return null;
    const keywords = row._matchedKeywords ?? [];
    return (
      <tr className="file-excerpt-row">
        <td colSpan={colSpan}>
          📄{' '}
          {keywords.map((keyword) => (
            <mark key={keyword} className="file-kw-mark">
              {keyword}
            </mark>
          ))}{' '}
          — {highlightKeywords(row._fileExcerpt, keywords)}
        </td>
      </tr>
    );
  };

  /* ─── 상태 줄 ──────────────────────────────────────────────────────────── */
  const period =
    criteria.fromDate && criteria.toDate
      ? { from: criteria.fromDate, to: criteria.toDate }
      : undefined;

  const renderStatusBar = (): ReactNode => {
    if (query.error) return <StatusBar error message={`오류: ${query.error.message}`} />;
    if (pending) return null;

    if (scanMode) {
      // 재조회가 아직 돌고 있으면 "후보 없음"을 띄우지 않는다 — 곧 결과가 올 수 있다.
      if (!query.isPending && !(wantsRetry && retry.isPending) && data && data.items.length === 0) {
        return (
          <StatusBar
            error
            period={period}
            message={
              <>
                📄 파일 검색 후보가 없습니다.{' '}
                {kind === 'bid-result'
                  ? '입찰 결과 탭은 첨부파일 검색 대상이 아닙니다.'
                  : '기간을 넓히거나 발주기관/공고명 조건을 줄여보세요.'}
              </>
            }
          />
        );
      }
      if (scan.progress) {
        return (
          <StatusBar
            message={`📄 첨부문서 전수조사 중... ${scan.progress.done}/${scan.progress.total}건 (${scan.progress.tasks.join(' · ')})`}
          />
        );
      }
      if (scan.error) {
        return <StatusBar error message={`📄 파일 스캔 오류: ${scan.error.message}`} />;
      }
      if (scan.data) {
        const notes: string[] = [];
        if (includedClosedFallback) notes.push('마감 공고 포함 재조회');
        if (criteria.excludeBlockingClauses) {
          notes.push(
            `입찰 불가 ${scan.data.exclusionCount}건 자동 제외` +
              (scan.data.reasonNote ? ` (${scan.data.reasonNote})` : ''),
          );
        }
        if (criteria.fileKeywords.length) {
          notes.push(`파일 내 ${scan.data.matchCount}건 매칭 (${criteria.fileKeywords.join(', ')})`);
        }
        return (
          <StatusBar
            message={
              `📄 ${notes.join(' | ')} | ${scan.data.scanned}/${scan.data.total}건 전수조사 완료` +
              (scan.data.cacheHits ? ` | 캐시 ${scan.data.cacheHits}건 재사용` : '')
            }
          />
        );
      }
      return null;
    }

    if (!data) return null;

    const notes: string[] = [];
    // 조회 기간이 통째로 과거면 buildQuery 가 activeOnly 를 스스로 뺀다 — 그 사실을 알린다.
    if (criteria.activeOnly && hasKeywordQuery && isPastOnlyAnnounceRange(criteria, kind)) {
      notes.push('마감 공고 포함 조회');
    }
    if (includedClosedFallback) notes.push('마감 공고 포함 재조회');

    const counts = data.sourceCounts;
    return (
      <StatusBar
        total={data.totalCount}
        period={period}
        page={{ current: criteria.pageNo, total: totalPages }}
        bidType={criteria.bidType || undefined}
        notes={notes}
        sources={
          kind === 'bid-announce' && counts
            ? {
                g2b: Number(counts.g2b ?? 0),
                privateG2b: Number(counts['private-g2b'] ?? 0),
                d2b: Number(counts.d2b ?? 0),
              }
            : undefined
        }
        sourceErrorCount={kind === 'bid-announce' ? data.sourceErrors?.length : undefined}
      />
    );
  };

  return (
    <section className="panel" aria-label={screen.label}>
      <div className="result-topbar">
        <div className="topbar-left">
          {renderStatusBar()}
          <div className="type-filter" aria-label="구분 필터">
            <span className="type-filter-label">구분</span>
            {BID_TYPES.map((type) => (
              <button
                key={type || 'all'}
                type="button"
                className={criteria.bidType === type ? 'type-filter-btn active' : 'type-filter-btn'}
                onClick={() => setCriteria({ bidType: type })}
              >
                {TYPE_LABEL[type]}
              </button>
            ))}
          </div>
          {/* '마감 전만'은 입찰 공고에서만 뜻이 있다 — 나머지 화면에는 마감 개념이 없다. */}
          {kind === 'bid-announce' ? (
            <label className="filter-check">
              <input
                type="checkbox"
                checked={criteria.activeOnly}
                onChange={(e) => setCriteria({ activeOnly: e.target.checked })}
              />{' '}
              마감 전 공고만 보기
            </label>
          ) : null}
        </div>
      </div>

      {pending ? (
        <PendingState status={pending.status} message={pending.message} link={pending.link} />
      ) : (
        <>
          <DataTable<ScannedRow>
            columns={columns}
            rows={rows}
            rowKey={(row, index) => rowKeyForItem(row, index, kind)}
            sort={sort}
            onSort={(key) =>
              setCriteria({
                sortKey: key,
                // 같은 컬럼을 다시 누르면 방향만 뒤집는다 — 원본 elHead 클릭 위임과 동일.
                sortDir: sort.key === key && sort.dir === 'asc' ? 'desc' : 'asc',
              })
            }
            renderCell={renderCell}
            rowClassName={(row) =>
              [row._fileExcerpt ? 'file-match-row' : '', row._blocking ? 'blocking-row' : '']
                .filter(Boolean)
                .join(' ') || undefined
            }
            renderSubRow={renderSubRow}
            loading={loading}
            // 구분(물품/용역/공사)·검색어를 바꿔 결과가 갈릴 때 부드럽게 전환한다(정렬·페이지 제외).
            transitionKey={[
              kind,
              criteria.bidType,
              criteria.andTerms.join(','),
              criteria.orTerms.join(','),
              criteria.notTerms.join(','),
              criteria.insttNm,
              criteria.corpNm,
            ].join('|')}
            empty={rows.length === 0 && !loading ? <EmptyState /> : null}
          />

          {/* 전수조사 결과는 이미 전 건을 받은 상태라 페이지가 없다(원본도 숨겼다). */}
          {!scanMode && rows.length > 0 ? (
            <Pagination
              page={criteria.pageNo}
              totalPages={totalPages}
              perPage={criteria.perPage}
              onPage={(nextPage) => {
                setPage(nextPage);
                window.scrollTo({ top: 0, behavior: 'smooth' });
              }}
              onPerPage={(perPage) => setCriteria({ perPage })}
            />
          ) : null}
        </>
      )}

      <NoticeDrawer selection={selection} onClose={() => setSelection(null)} />
    </section>
  );
}
