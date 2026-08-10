/*
 * 단가 DB — 물품 시세 카탈로그(price_catalog) 조회·수정·AI 적재.
 *
 * 이 화면은 나라장터(G2B)를 부르지 않는다. 백엔드가 다나와·아이티마야·에누리에서 긁어 둔
 * 시세를 조회하고, 사람이 값을 고치거나("행 추가"·수정·삭제) AI 로 새 시세를 긁어 온다
 * ("AI로 시세 적재"). 출처 어휘(danawa/itmaya/enuri)와 분류(GPU·CPU… 자유 텍스트)는
 * 공고 검색의 어휘와 전혀 다르다 — api/price.ts 주석 참고.
 *
 * priceKrw 가 null 이면 '미확인'으로 표시한다(0 이 아니다) — PriceDbTable/priceDbRows 참고.
 */
import { useEffect, useState } from 'react';
import {
  PRICE_SOURCE_LABELS,
  PRICE_SOURCES,
  useIngestPrices,
  usePriceCatalog,
  type PriceSource,
} from '@/api/price';
import { EmptyState } from '@/components/feedback/EmptyState';
import { PanelNotice } from '@/components/feedback/Spinner';
import { StatusBar } from '@/components/table/StatusBar';
import { PriceDbTable } from '@/features/price/PriceDbTable';
import '@/features/price/price.css';

export function PriceDatabaseScreen() {
  useEffect(() => {
    document.title = '단가 DB — G2B Masters';
  }, []);

  /* ── 조회 필터 ─────────────────────────────────────────────────────────── */
  // 출처 셀렉트는 즉시 반영하고, 텍스트 입력은 확정(조회 버튼·Enter)될 때만 올린다 —
  // 한 글자씩 조회를 내보내면 요청이 낭비된다.
  const [source, setSource] = useState<PriceSource | ''>('');
  const [catDraft, setCatDraft] = useState('');
  const [qDraft, setQDraft] = useState('');
  const [applied, setApplied] = useState<{ category: string; q: string }>({ category: '', q: '' });

  const list = usePriceCatalog({
    source: source || undefined,
    category: applied.category || undefined,
    q: applied.q || undefined,
    limit: 200,
  });
  const items = list.data?.items ?? [];

  const submitFilter = (event: React.FormEvent) => {
    event.preventDefault();
    setApplied({ category: catDraft, q: qDraft });
  };

  /* ── AI 시세 적재 ──────────────────────────────────────────────────────── */
  const ingest = useIngestPrices();
  const [ingestQuery, setIngestQuery] = useState('');
  const [ingestCategory, setIngestCategory] = useState('');
  const [ingestSources, setIngestSources] = useState<Set<PriceSource>>(
    () => new Set(PRICE_SOURCES),
  );

  const toggleIngestSource = (src: PriceSource) =>
    setIngestSources((prev) => {
      const next = new Set(prev);
      if (next.has(src)) next.delete(src);
      else next.add(src);
      return next;
    });

  const submitIngest = (event: React.FormEvent) => {
    event.preventDefault();
    const query = ingestQuery.trim();
    if (!query) return;
    const sources = [...ingestSources];
    ingest.mutate({
      query,
      category: ingestCategory.trim() || undefined,
      // 하나도 안 고르면 백엔드가 세 출처 전부에서 긁는다 — 그때는 sources 를 아예 안 보낸다.
      sources: sources.length ? sources : undefined,
    });
  };

  const result = ingest.data;

  return (
    <section className="panel" aria-label="단가 DB">
      <h2 className="panel-title">단가 DB</h2>

      {/* 조회 필터 */}
      <form className="price-filter" onSubmit={submitFilter}>
        <label className="price-field">
          <span>출처</span>
          <select
            aria-label="출처 필터"
            value={source}
            onChange={(e) => setSource(e.target.value as PriceSource | '')}
          >
            <option value="">전체</option>
            {PRICE_SOURCES.map((src) => (
              <option key={src} value={src}>
                {PRICE_SOURCE_LABELS[src]}
              </option>
            ))}
          </select>
        </label>
        <label className="price-field">
          <span>분류</span>
          <input
            aria-label="분류 필터"
            placeholder="예: GPU, 서버"
            value={catDraft}
            onChange={(e) => setCatDraft(e.target.value)}
          />
        </label>
        <label className="price-field">
          <span>검색어</span>
          <input
            aria-label="단가 검색어"
            placeholder="품명·모델"
            value={qDraft}
            onChange={(e) => setQDraft(e.target.value)}
          />
        </label>
        <button type="submit" className="btn-primary">
          조회
        </button>
      </form>

      {/* AI 시세 적재 */}
      <form className="price-ingest" onSubmit={submitIngest}>
        <div className="price-ingest-head">AI로 시세 적재</div>
        <div className="price-ingest-row">
          <input
            aria-label="적재 검색어"
            className="price-ingest-query"
            placeholder="적재할 품목 (예: RTX 5090)"
            value={ingestQuery}
            onChange={(e) => setIngestQuery(e.target.value)}
          />
          <input
            aria-label="적재 분류"
            placeholder="분류(선택)"
            value={ingestCategory}
            onChange={(e) => setIngestCategory(e.target.value)}
          />
          <button type="submit" className="btn-primary" disabled={ingest.isPending || !ingestQuery.trim()}>
            {ingest.isPending ? '적재 중…' : '적재'}
          </button>
        </div>
        <div className="price-ingest-sources">
          {PRICE_SOURCES.map((src) => (
            <label key={src} className="price-ingest-check">
              <input
                type="checkbox"
                checked={ingestSources.has(src)}
                onChange={() => toggleIngestSource(src)}
              />
              {PRICE_SOURCE_LABELS[src]}
            </label>
          ))}
        </div>

        {ingest.error ? (
          <p className="price-ingest-result" role="alert">
            적재 오류: {(ingest.error as Error).message}
          </p>
        ) : result ? (
          <div className="price-ingest-result" role="status">
            {result.aiUnavailable ? (
              <strong>AI 백엔드가 꺼져 있어 적재하지 못했습니다.</strong>
            ) : (
              <>
                <strong>&quot;{result.query}&quot;</strong> — 총 {result.ingested}건 적재
                <span className="price-persource">
                  {PRICE_SOURCES.filter((src) => src in result.perSource).map((src) => (
                    <span key={src} className="price-persource-item">
                      {PRICE_SOURCE_LABELS[src]} {result.perSource[src]}건
                    </span>
                  ))}
                </span>
              </>
            )}
            {result.errors.length ? (
              <div className="price-ingest-errors">
                {result.errors.map((err, i) => (
                  <div key={`${i}-${err}`}>{err}</div>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}
      </form>

      <StatusBar
        total={list.data?.count}
        message={source ? `출처: ${PRICE_SOURCE_LABELS[source]}` : '전체 출처'}
      />

      {list.isPending ? (
        <PanelNotice>단가를 불러오는 중...</PanelNotice>
      ) : list.error ? (
        <PanelNotice empty>단가 조회 실패: {(list.error as Error).message}</PanelNotice>
      ) : items.length === 0 && !applied.category && !applied.q && !source ? (
        <EmptyState>
          아직 적재된 단가가 없습니다 — &quot;AI로 시세 적재&quot;로 시세를 긁어 오세요.
        </EmptyState>
      ) : (
        <PriceDbTable rows={items} defaultSource={source || 'danawa'} />
      )}
    </section>
  );
}
