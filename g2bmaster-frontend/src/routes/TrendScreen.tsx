/*
 * 물품 · 용역 · 공사 구매 트렌드 — 원본 loadBidTrend(app.js:2413) + renderBidTrend(2363).
 * 세 화면은 제목과 빠른 키워드만 다르고 나머지가 같다.
 *
 * 원본은 응답을 currentTrendData 에 담아 두고, 표시 옵션(21/60/전체 · 날짜/건수/금액)이
 * 바뀌면 재조회 없이 renderBidTrend 를 다시 불렀다. 그 동작을 유지한다 — 여기서는
 * TanStack Query 캐시가 currentTrendData 자리를 대신하고, 옵션은 그냥 지역 state 다.
 */
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTrends, type TrendBucket, type TrendKind, type TrendNotice, type TrendQuery } from '@/api';
import { StatusBar } from '@/components/table/StatusBar';
import { PanelNotice } from '@/components/feedback/Spinner';
import { SCREENS, type ScreenKind } from '@/domain/columns';
import { calcDday, fmtDisplayDatetime, toG2bDate, trendFullMoney, trendMoney } from '@/domain/format';
import { useSearchCriteria } from '@/features/search/useSearchCriteria';
import { crossSearchTo } from '@/features/notices/crossSearch';
import { TrendDayTable } from '@/features/trends/TrendDayTable';
import {
  trendDayLimitLabel,
  trendDaySortLabel,
  type TrendDayLimit,
  type TrendDaySort,
} from '@/features/trends/trendDays';
import '@/features/trends/trend.css';

const KIND_TO_SCREEN: Readonly<Record<TrendKind, ScreenKind>> = {
  product: 'product-trend',
  service: 'service-trend',
  construction: 'construction-trend',
};

interface TrendScreenProps {
  kind: TrendKind;
}

/** 순위 목록 — 원본 trendRankList(app.js:2339). */
function RankList({
  items,
  amount = false,
  onKeyword,
}: {
  items: readonly TrendBucket[];
  amount?: boolean;
  /** 주면 항목이 버튼이 되어 그 키워드로 검색한다(키워드 TOP 전용). */
  onKeyword?: (keyword: string) => void;
}) {
  if (!items.length) return <div className="panel-empty">데이터가 없습니다.</div>;
  return (
    <ol className="trend-rank-list">
      {items.map((item, i) => {
        const name = item.name || '미상';
        const value = amount
          ? trendMoney(item.amount)
          : `${Number(item.count || 0).toLocaleString()}건`;
        return (
          <li key={`${name}-${i}`}>
            {onKeyword ? (
              <button type="button" className="trend-keyword-btn" onClick={() => onKeyword(name)}>
                {name}
              </button>
            ) : (
              <span>{name}</span>
            )}
            <strong>{value}</strong>
          </li>
        );
      })}
    </ol>
  );
}

/** 공고 목록 — 원본 trendNoticeList(app.js:2349). */
function NoticeList({
  items,
  onOpenNotice,
}: {
  items: readonly TrendNotice[];
  onOpenNotice: (notice: TrendNotice) => void;
}) {
  if (!items.length) return <div className="panel-empty">대상 공고가 없습니다.</div>;
  return (
    <div className="trend-notice-list">
      {items.map((notice, i) => {
        const dday = calcDday(notice.bidClseDt);
        return (
          <div className="trend-notice" key={`${notice.bidNtceNo}-${i}`}>
            <div>
              <button
                type="button"
                className="trend-open-btn"
                onClick={() => onOpenNotice(notice)}
              >
                {notice.bidNtceNm || '-'}
              </button>
              <p>
                {notice.ntceInsttNm || '-'} · {trendFullMoney(notice.presmptPrce)}
              </p>
            </div>
            <span>{dday ? dday.text : fmtDisplayDatetime(String(notice.bidClseDt || ''))}</span>
          </div>
        );
      })}
    </div>
  );
}

export function TrendScreen({ kind }: TrendScreenProps) {
  const screen = SCREENS[KIND_TO_SCREEN[kind]];
  const trendCfg = screen.kind === 'trend' ? screen.trend : null;
  const title = trendCfg?.title ?? screen.label;
  const itemLabel = trendCfg?.itemLabel ?? '공고';
  const quickKeywords = trendCfg?.quickKeywords ?? [];

  const navigate = useNavigate();
  const location = useLocation();
  const { criteria, setCriteria } = useSearchCriteria();

  // 표시 옵션은 URL 에 올리지 않는다 — 조회에 영향을 주지 않는 순수 표시 설정이고,
  // 주소가 길어질수록 공유가 어려워진다.
  const [dayLimit, setDayLimit] = useState<TrendDayLimit>('21');
  const [daySort, setDaySort] = useState<TrendDaySort>('date');

  useEffect(() => {
    document.title = `${title} — G2B Masters`;
  }, [title]);

  const query = useMemo<TrendQuery>(
    () => ({
      fromDate: criteria.fromDate ? toG2bDate(criteria.fromDate) : undefined,
      toDate: criteria.toDate ? toG2bDate(criteria.toDate) : undefined,
      andTerms: criteria.andTerms.join(','),
      orTerms: criteria.orTerms.join(','),
      notTerms: criteria.notTerms.join(','),
    }),
    [criteria.fromDate, criteria.toDate, criteria.andTerms, criteria.orTerms, criteria.notTerms],
  );

  const trends = useTrends(kind, query);
  const data = trends.data;

  const openNotice = (notice: TrendNotice) => {
    // 원본 crossTabSearch('bid-announce', [], no, type) 와 같다 — 번호로 바로 찾아간다.
    navigate(
      crossSearchTo(
        'bid-announce',
        { bidNtceNo: notice.bidNtceNo, bidType: notice._type },
        location.search,
      ),
    );
  };

  const applyKeyword = (keyword: string) => {
    // 원본은 AND 한 칸만 남기고 나머지를 비웠다 — 빠른 키워드는 "이것만 보기"라는 뜻이다.
    setCriteria({ andTerms: [keyword], orTerms: [], notTerms: [], fileKeywords: [] });
  };

  const statusMessage = (): ReactNode => {
    if (trends.error) return `${title} 오류: ${trends.error.message}`;
    if (!data) return `최근 ${itemLabel} 공고를 집계하고 있습니다.`;
    const keywords = [...criteria.andTerms, ...criteria.orTerms];
    const period = data.period ?? { from: criteria.fromDate, to: criteria.toDate };
    return (
      <>
        {title} | {period.from || '-'} ~ {period.to || '-'}
        {keywords.length ? ` · 키워드: ${keywords.join(', ')}` : ''}
        {data._cached ? ' | 캐시' : ''}
      </>
    );
  };

  const summary = data?.summary;

  return (
    <section className="panel" aria-label={title}>
      <StatusBar error={Boolean(trends.error)} message={statusMessage()} />

      {trends.isPending ? (
        <PanelNotice>{title} 집계 중...</PanelNotice>
      ) : !data ? (
        <PanelNotice empty>{title}를 불러오지 못했습니다.</PanelNotice>
      ) : (
        <div className="trend-panel">
          <div className="trend-summary-grid">
            <div className="trend-card">
              <span>{itemLabel} 공고</span>
              <strong>{Number(summary?.totalCount ?? 0).toLocaleString()}</strong>
              <small>건</small>
            </div>
            <div className="trend-card">
              <span>총 추정가격</span>
              <strong>{trendMoney(summary?.totalAmount)}</strong>
              <small>{trendFullMoney(summary?.totalAmount)}</small>
            </div>
            <div className="trend-card">
              <span>평균 추정가격</span>
              <strong>{trendMoney(summary?.averageAmount)}</strong>
              <small>중앙값 {trendMoney(summary?.medianAmount)}</small>
            </div>
            <div className="trend-card">
              <span>마감 7일 이내</span>
              <strong>{Number(summary?.closingSoonCount ?? 0).toLocaleString()}</strong>
              <small>건</small>
            </div>
          </div>

          <div className="trend-quick">
            {quickKeywords.map((keyword) => (
              <button
                key={keyword}
                type="button"
                className="trend-keyword-btn"
                onClick={() => applyKeyword(keyword)}
              >
                {keyword}
              </button>
            ))}
          </div>

          <div className="trend-layout">
            <section className="trend-section trend-wide">
              <h3>
                일자별 {itemLabel} 공고{' '}
                <small>
                  {trendDayLimitLabel(dayLimit)} / {trendDaySortLabel(daySort)}
                </small>
              </h3>
              <TrendDayTable
                byDay={data.byDay ?? []}
                limit={dayLimit}
                sort={daySort}
                onLimitChange={setDayLimit}
                onSortChange={setDaySort}
                onOpenNotice={openNotice}
              />
            </section>
            <section className="trend-section">
              <h3>키워드 TOP</h3>
              <RankList items={data.keywords ?? []} onKeyword={applyKeyword} />
            </section>
            <section className="trend-section">
              <h3>발주기관 TOP</h3>
              <RankList items={data.topInstitutions ?? []} />
            </section>
            <section className="trend-section">
              <h3>계약방법</h3>
              <RankList items={data.contractMethods ?? []} />
            </section>
            <section className="trend-section trend-wide">
              <h3>마감임박 {itemLabel}</h3>
              <NoticeList items={data.closingSoon ?? []} onOpenNotice={openNotice} />
            </section>
            <section className="trend-section trend-wide">
              <h3>고액 {itemLabel} 공고</h3>
              <NoticeList items={data.highValue ?? []} onOpenNotice={openNotice} />
            </section>
          </div>
        </div>
      )}
    </section>
  );
}
