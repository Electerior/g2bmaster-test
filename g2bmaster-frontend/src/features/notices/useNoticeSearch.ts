/*
 * 공고 조회 훅 — 원본 search()(app.js:1241) 의 조회 부분.
 *
 * 원본은 `searchSeq` 카운터로 "늦게 도착한 응답이 화면을 덮어쓰는 것"을 막았다. 조건이
 * queryKey 가 된 지금은 그 장치가 필요 없다: 조건이 바뀌면 키가 바뀌고, 옛 키의 응답은
 * 그 키의 캐시에만 들어간다. → searchSeq 는 이식하지 않는다(spec §2).
 */
import { useQuery } from '@tanstack/react-query';
import {
  fetchBidAnnounce,
  fetchBidPlan,
  fetchBidResult,
  fetchPreSpec,
  type DecoratedRow,
  type NoticeSearchQuery,
} from '@/api';
import type { NoticeTableKind } from '@/domain/columns';
import type { PendingStatus } from '@/components/feedback/EmptyState';

/**
 * 네 응답의 공통 형태.
 *
 * `status`/`message`/`link` 는 타입에 없다 — 서버가 **HTTP 200 과 함께** 온보딩 안내를
 * 이 형태로 돌려주기 때문이다(원본 app.js:1286). 오류가 아니라 정상 응답의 한 종류다.
 */
export interface NoticeEnvelope {
  items: DecoratedRow[];
  totalCount: number;
  pageNo: number;
  numOfRows: number;
  _cached?: boolean;
  sourceCounts?: Record<string, number>;
  sourceErrors?: Array<{ source: string; [key: string]: unknown }>;
  status?: string;
  message?: string;
  link?: string;
}

type Fetcher = (query: NoticeSearchQuery) => Promise<NoticeEnvelope>;

const FETCHERS: Readonly<Record<NoticeTableKind, Fetcher>> = {
  'bid-plan': fetchBidPlan,
  'pre-spec': fetchPreSpec,
  'bid-announce': fetchBidAnnounce,
  'bid-result': fetchBidResult,
};

export function useNoticeQuery(
  kind: NoticeTableKind,
  query: NoticeSearchQuery,
  options: { enabled?: boolean } = {},
) {
  return useQuery({
    queryKey: ['notices', kind, query],
    queryFn: () => FETCHERS[kind](query),
    enabled: options.enabled ?? true,
  });
}

/** 응답이 온보딩 안내인지. 맞으면 표 대신 안내 화면을 그린다. */
export function pendingOf(
  data: NoticeEnvelope | undefined,
): { status: PendingStatus; message: string; link?: string } | null {
  if (!data) return null;
  if (data.status !== 'pending-apply' && data.status !== 'pending-approval') return null;
  return { status: data.status, message: data.message ?? '', link: data.link };
}
