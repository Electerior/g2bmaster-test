/*
 * AI 수주 데스크 — 상위 공고 검색과 **같은 기준**으로 검색된 공고들에 딜 분석을 붙인다.
 *
 * 예전엔 공고번호·품목·예산을 손으로 넣어 단건 분석했다. 실제 업무는 "이 조건에 맞는
 * 공고들"을 훑으며 각각 남는 장사인지 보는 것이라, 검색 결과 목록에 분석을 얹는다.
 *
 *  - 검색: useSearchCriteria(URL 조건) + useNoticeIndexSearch — 공고 통합 검색과 같은 훅.
 *          그래서 /notices 에서 쓰던 조건이 그대로 이어지고, 여기서 바꾸면 URL 에 남는다.
 *  - 분석: 결과 각 공고에 deal-analysis 를 배치로(useQueries) 붙인다. 목록은 얕게(deep=off,
 *          시가·예산) 자동 분석하고, 규격서 부품 단가까지 보려면 카드에서 deep 를 켠다.
 */
import { useEffect, useMemo, useState } from 'react';
import { useQueries } from '@tanstack/react-query';
import {
  analyzeDeal,
  BUSINESS_DIVISIONS,
  useSaveNotice,
  type BusinessDivision,
  type DealAnalysisResponse,
  type NoticeIndexItem,
  useNoticeIndexSearch,
} from '@/api';
import { TypeBadge } from '@/components/badges/Badge';
import { fmtMoney } from '@/domain/format';
import { buildNoticeIndexQuery, useSearchCriteria } from '@/features/search/useSearchCriteria';
import { EmptyState } from '@/components/feedback/EmptyState';
import { PriceTable } from '@/features/deal/PriceTable';
import { priceTotal, rowsFromBreakdown, type PriceRow } from '@/features/deal/priceRows';
import '@/features/deal/deal.css';

/** 한 페이지에 붙이는 공고 수. 카드마다 딜 분석을 돌리므로 20 으로 묶고 페이지로 넘긴다. */
const PER_PAGE = 20;

/** 검색 결과 한 건을 deal-analysis 의 item 으로. 공고 객체를 그대로 넘기는 자리다. */
function toDealItem(item: NoticeIndexItem): Record<string, unknown> {
  return {
    bidNtceNo: item.id,
    bidNtceNm: item.noticeName ?? '',
    presmptPrce: item.estimatedPrice ?? undefined,
    dtilPrdctClsfcNo: item.detailProductCode ?? undefined,
    attachmentUrls: item.attachmentUrls ?? undefined,
  };
}

export function DealRadarScreen() {
  useEffect(() => {
    document.title = 'AI 수주 데스크 — G2B Masters';
  }, []);

  const { criteria, setCriteria } = useSearchCriteria();
  // deep: 규격서 첨부까지 열어 부품 단가를 추정(느림). 목록 배치는 기본 얕게 돌린다.
  const [deep, setDeep] = useState(false);
  const [term, setTerm] = useState(criteria.andTerms.join(' '));
  /*
   * 공고별 재검토 카운터. useQueries 는 staleTime:Infinity 라 같은 키면 캐시만 돌려준다 —
   * 이 토큰을 키에 넣어야 '재검토'가 새 키를 만들어 실제로 다시 조회한다. 토큰이 0 보다
   * 크면(=재검토된 공고) forceRefresh 를 실어 서버가 저장된 결과까지 무시하고 새로 분석한다.
   */
  const [refreshTokens, setRefreshTokens] = useState<Record<string, number>>({});

  const query = useMemo(() => buildNoticeIndexQuery(criteria, { perPage: PER_PAGE }), [criteria]);

  const search = useNoticeIndexSearch(query);
  const items = search.data?.items ?? [];

  // 각 공고에 딜 분석을 배치로 붙인다. staleTime 무한 — 같은 공고를 다시 긁지 않는다.
  const analyses = useQueries({
    queries: items.map((item) => {
      const token = refreshTokens[item.id] ?? 0;
      return {
        queryKey: ['deal-analysis', item.id, deep, token],
        queryFn: () =>
          analyzeDeal({
            item: toDealItem(item),
            deep,
            include: { opening: false },
            // 재검토된 공고만 서버 캐시를 우회한다. 첫 조회는 저장된 결과를 그대로 쓴다.
            forceRefresh: token > 0,
          }),
        staleTime: Infinity,
        retry: false,
      };
    }),
  });

  const reanalyze = (id: string) =>
    setRefreshTokens((prev) => ({ ...prev, [id]: (prev[id] ?? 0) + 1 }));

  // 검색·필터가 바뀌면 1페이지로 되돌린다 — 3페이지에서 새 검색을 하면 결과가 없어 보인다.
  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setCriteria({ andTerms: term.split(/[\s,]+/).filter(Boolean), pageNo: 1 });
  };

  // 구분 필터 — criteria.bidType 에 바로 얹는다(buildNoticeIndexQuery 가 division 으로 옮긴다).
  // 필터일 뿐이다: 딜 분석 자체는 지금처럼 그대로 돈다(물품 외 구분은 백엔드가 미매칭으로 답한다).
  const onDivision = (value: '' | BusinessDivision) => setCriteria({ bidType: value, pageNo: 1 });

  // 페이지 이동. 카드마다 딜 분석을 붙이는 비싼 화면이라 한 번에 20건씩만 본다 — '다음 20개'.
  const totalCount = search.data?.totalCount ?? items.length;
  const totalPages = Math.max(1, Math.ceil(totalCount / PER_PAGE));
  const pageNo = Math.min(criteria.pageNo, totalPages);
  const goPage = (p: number) => {
    setCriteria({ pageNo: Math.min(Math.max(1, p), totalPages) });
    window.scrollTo({ top: 0 });
  };

  return (
    <section className="panel" aria-label="AI 수주 데스크">
      <h2 className="panel-title">AI 수주 데스크</h2>

      <form className="deal-search" onSubmit={submit}>
        <input
          className="deal-search-input"
          value={term}
          onChange={(e) => setTerm(e.target.value)}
          placeholder="검색어 (예: GPU 서버, 노트북) — 공고 검색과 같은 기준"
        />
        <label className="deal-deep">
          <input type="checkbox" checked={deep} onChange={(e) => setDeep(e.target.checked)} />
          Deep — 규격서 부품 단가까지 (느림)
        </label>
        <button type="submit" className="btn-primary">검색</button>
      </form>

      <div className="deal-division" role="group" aria-label="구분 필터">
        <span className="deal-division-label">구분</span>
        <button
          type="button"
          className={criteria.bidType === '' ? 'deal-division-btn active' : 'deal-division-btn'}
          aria-pressed={criteria.bidType === ''}
          onClick={() => onDivision('')}
        >
          전체
        </button>
        {BUSINESS_DIVISIONS.map((division) => (
          <button
            key={division}
            type="button"
            className={criteria.bidType === division ? 'deal-division-btn active' : 'deal-division-btn'}
            aria-pressed={criteria.bidType === division}
            onClick={() => onDivision(division)}
          >
            {division}
          </button>
        ))}
      </div>

      {search.isPending ? (
        <div className="empty-msg">검색 중…</div>
      ) : search.error ? (
        <div className="empty-msg" role="alert">검색 오류: {(search.error as Error).message}</div>
      ) : items.length === 0 ? (
        <EmptyState>조건에 맞는 공고가 없습니다. 검색어를 바꿔 보세요.</EmptyState>
      ) : (
        <>
          <p className="meta deal-count">
            {totalCount}건 중 {(pageNo - 1) * PER_PAGE + 1}–{(pageNo - 1) * PER_PAGE + items.length}건에 딜 분석을 붙였습니다
            {totalPages > 1 ? ` · ${pageNo}/${totalPages} 페이지` : ''}
            {deep ? ' (Deep — 규격서 부품 단가 포함, 느립니다)' : ''}
          </p>
          <div className="deal-cards">
            {items.map((item, i) => (
              <DealCard
                key={item.id}
                item={item}
                analysis={analyses[i]?.data}
                pending={analyses[i]?.isPending ?? true}
                error={analyses[i]?.error as Error | undefined}
                onReanalyze={() => reanalyze(item.id)}
              />
            ))}
          </div>
          <DealPager page={pageNo} totalPages={totalPages} onPage={goPage} />
          <p className="meta deal-hint">
            💡 카드의 <strong>저장</strong>으로 담은 공고는 <code>tools/deal-analysis.sh backfill</code> 로
            일괄 deep 분석되어 DB(<code>deal_analysis_result</code>)에 저장됩니다.
          </p>
        </>
      )}
    </section>
  );
}

function DealCard({
  item,
  analysis,
  pending,
  error,
  onReanalyze,
}: {
  item: NoticeIndexItem;
  analysis: DealAnalysisResponse | undefined;
  pending: boolean;
  error: Error | undefined;
  onReanalyze: () => void;
}) {
  const market = analysis?.market;
  const est = analysis?.estimatedUnitCost;
  const save = useSaveNotice();
  // 나라장터 상세가 있는 건만 링크로. 사전규격·계획은 원문 URL 이 없어 그냥 글자로 둔다(합성 금지).
  const title = item.noticeName ?? item.id;
  // 값 있는 첨부만 — 계약상 url 없이 name 만 오는 항목이 섞인다.
  const attachments = (item.attachmentUrls ?? []).filter((file) => file?.url);

  // 편집 가능한 가격표. AI breakdown 을 초기값으로 두고 사람이 확정한다.
  const [editing, setEditing] = useState(false);
  const [rows, setRows] = useState<PriceRow[]>([]);
  // 분석 결과가 오면 표 초기값을 채운다(사람이 아직 안 건드렸을 때만).
  useEffect(() => {
    if (est && est.matched && rows.length === 0) {
      setRows(rowsFromBreakdown(est.breakdown));
    }
  }, [est, rows.length]);

  const onSave = () =>
    save.mutate({
      bidNtceNo: item.id,
      bidNtceOrd: item.noticeOrder ?? '00',
      title: item.noticeName ?? '',
      insttNm: item.noticeInstitutionName ?? item.demandInstitutionName ?? undefined,
      amount: item.estimatedPrice ?? undefined,
      // 사람이 확정한 가격표를 저장 공고에 함께 넣는다(saved_notice.price_rows).
      priceRows: rows as unknown as Array<Record<string, unknown>>,
      priceTotal: priceTotal(rows),
      // raw 에 공고 원본을 담아 두면 backfill 이 첨부 URL 까지 열어 부품 단가를 추정한다.
      raw: item as unknown as Record<string, unknown>,
    });

  return (
    <div className="deal-card">
      <div className="deal-card-head">
        <h3 className="deal-card-title">
          {item.sourceUrl ? (
            <a href={item.sourceUrl} target="_blank" rel="noopener noreferrer">
              {title}
            </a>
          ) : (
            title
          )}
        </h3>
        <div className="deal-card-actions">
          {item.dday != null ? <span className="deal-dday">D-{item.dday}</span> : null}
          <button
            type="button"
            className="deal-save"
            onClick={onReanalyze}
            disabled={pending}
            title="이 공고의 딜 분석을 서버에서 다시 돌립니다"
          >
            {pending ? '분석 중…' : '재검토'}
          </button>
          {est && est.matched ? (
            <button type="button" className="deal-save" onClick={() => setEditing((v) => !v)}>
              {editing ? '표 닫기' : '가격표'}
            </button>
          ) : null}
          <button type="button" className="deal-save" onClick={onSave} disabled={save.isPending || save.isSuccess}>
            {save.isSuccess ? '저장됨' : save.isPending ? '저장 중…' : '저장'}
          </button>
        </div>
      </div>
      <div className="deal-card-meta">
        <span>{item.noticeInstitutionName ?? item.demandInstitutionName ?? '—'}</span>
        {item.category ? <span className="deal-chip">{item.category}</span> : null}
        {item.businessDivision ? <TypeBadge value={item.businessDivision} /> : null}
      </div>

      <dl className="deal-stats">
        <Stat label="추정가격" value={fmtMoney(item.estimatedPrice)} />
        {pending ? (
          <Stat label="분석" value="분석 중…" />
        ) : error ? (
          <Stat label="분석" value="실패" />
        ) : (
          <>
            <Stat
              label="예상 낙찰가"
              value={market && market.sampleCount > 0 ? fmtMoney(market.expectedAward) : '표본 없음'}
            />
            {rows.length ? (
              <Stat label="가격표 합계" value={fmtMoney(priceTotal(rows))} highlight />
            ) : est && est.matched ? (
              <Stat label="부품 단가(중앙)" value={fmtMoney(est.mid)} highlight />
            ) : null}
          </>
        )}
      </dl>

      {est && est.matched ? (
        <p className="meta deal-parts">
          부품 {est.breakdown.filter((b) => b.role !== 'base').length}종
          {est.hasBase ? ' · 베어본 포함' : ''}
          {est.gpuCount ? ` · GPU ${est.gpuCount}장` : ''}
          {est.allPriced === false ? ' · 일부 가격 미확인' : ''}
          {est.prebuilt?.isPrebuilt && est.prebuilt.comparables?.length
            ? ` · 완제품 후보 ${est.prebuilt.comparables.length}건(최저 ${fmtMoney(est.prebuilt.comparables[0]?.priceKrw)})`
            : ''}
        </p>
      ) : est && !est.matched ? (
        <p className="meta">부품 단가: {est.reason}</p>
      ) : null}

      {editing ? (
        <>
          <PriceTable rows={rows} onChange={setRows} />
          <p className="meta">단가·수량을 고치거나 행을 더해 견적을 확정하세요. &apos;저장&apos;하면 이 표가 공고에 함께 저장됩니다.</p>
        </>
      ) : null}

      {/* 첨부 — 값 있는 것만 다운로드 링크로(IndexNoticeDrawer 와 같은 패턴). */}
      {attachments.length ? (
        <div className="deal-attachments">
          <span className="deal-attachments-label">첨부 {attachments.length}건</span>
          <ul className="attachment-list">
            {attachments.map((file, i) => (
              <li key={`${file.url}-${i}`}>
                <a href={file.url} target="_blank" rel="noopener noreferrer">
                  {file.name || file.url}
                </a>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function Stat({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className={highlight ? 'deal-stat highlight' : 'deal-stat'}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

/**
 * 수주 데스크 페이지 이동 — 이전/다음(다음 20개)과 10개 블록 창의 페이지 번호.
 * 페이지당 건수 선택은 두지 않는다: 카드마다 딜 분석을 돌리므로 20 고정이 비용상 안전하다.
 */
const PAGER_BLOCK = 10;
function DealPager({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  if (totalPages <= 1) {
    return null;
  }
  const block = Math.floor((page - 1) / PAGER_BLOCK);
  const start = block * PAGER_BLOCK + 1;
  const end = Math.min(start + PAGER_BLOCK - 1, totalPages);
  const pages: number[] = [];
  for (let p = start; p <= end; p += 1) pages.push(p);

  return (
    <nav className="deal-pager" aria-label="페이지 이동">
      <button type="button" disabled={page === 1} onClick={() => onPage(page - 1)} aria-label="이전 20개">
        ‹ 이전
      </button>
      {pages.map((p) => (
        <button
          key={p}
          type="button"
          className={p === page ? 'active' : undefined}
          aria-current={p === page ? 'page' : undefined}
          onClick={() => onPage(p)}
        >
          {p}
        </button>
      ))}
      <button
        type="button"
        disabled={page === totalPages}
        onClick={() => onPage(page + 1)}
        aria-label="다음 20개"
      >
        다음 ›
      </button>
    </nav>
  );
}
