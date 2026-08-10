/*
 * 물품 · 용역 · 공사 구매 트렌드. 세 라우트는 경로만 다르고 응답 형태가 완전히 같아서
 * 하나의 타입으로 다룬다(lib/bid-trends.js:291).
 */
import { useQuery } from '@tanstack/react-query';
import { get } from '@/lib/apiClient';
import { toSearchParams, type CachedFlag } from './types';

export type TrendKind = 'product' | 'service' | 'construction';

export interface TrendQuery {
  fromDate?: string;
  toDate?: string;
  andTerms?: string;
  orTerms?: string;
  notTerms?: string;
  activeOnly?: string;
}

/** 트렌드 화면이 쓰는 축약 공고. 검색 결과 행과 달리 필드가 서버에서 고정돼 있다. */
export interface TrendNotice {
  bidNtceNo: string;
  bidNtceNm: string;
  ntceInsttNm: string;
  presmptPrce: number;
  bidNtceDt: string;
  bidClseDt: string;
  cntrctCnclsMthdNm: string;
  _type: string;
  _opportunityScore: number;
  _opportunitySummary: string;
  /** byDay[].notices 에만 붙는다 — 같은 제목이 며칠에 걸쳐 반복되는 건을 묶어 보여 준다. */
  _sameTitleCount?: number;
  _sameTitleAmount?: number;
}

export interface TrendBucket {
  name: string;
  count: number;
  amount: number;
}

export interface TrendDayBucket extends TrendBucket {
  notices: TrendNotice[];
}

export interface TrendResponse extends CachedFlag {
  /** 값이 없으면 서버가 '미상' 을 넣는다 — 날짜 파서에 그대로 먹이면 안 된다. */
  period: { from: string; to: string };
  summary: {
    totalCount: number;
    totalAmount: number;
    averageAmount: number;
    medianAmount: number;
    todayCount: number;
    closingSoonCount: number;
    activeOnly: boolean;
  };
  byDay: TrendDayBucket[];
  topInstitutions: TrendBucket[];
  contractMethods: TrendBucket[];
  keywords: TrendBucket[];
  /** 각각 최대 12건. */
  closingSoon: TrendNotice[];
  highValue: TrendNotice[];
}

export function fetchTrends(kind: TrendKind, query: TrendQuery = {}): Promise<TrendResponse> {
  return get<TrendResponse>(`/api/trends/${kind}?${toSearchParams({ ...query }).toString()}`);
}

export const trendKeys = {
  all: ['trends'] as const,
  byKind: (kind: TrendKind, q: TrendQuery) => ['trends', kind, q] as const,
};

export function useTrends(kind: TrendKind, query: TrendQuery = {}, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: trendKeys.byKind(kind, query),
    queryFn: () => fetchTrends(kind, query),
    enabled: options.enabled ?? true,
  });
}
