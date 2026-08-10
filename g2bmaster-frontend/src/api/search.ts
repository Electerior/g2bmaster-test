/*
 * 공고 통합 검색 — 백엔드 로컬 색인(`bid_notice`) 단독 조회.
 * 계약 원문: 백엔드 `docs/api-contract.md` §A-2 + `docs/notice-search-index.md`.
 *
 * 기존 4종(`/api/bid-plan` 등)과 **출처가 다르다.** 그쪽은 요청마다 나라장터를 팬아웃하지만
 * 이쪽은 백엔드가 주기 적재해 둔 색인만 본다. 그래서 이 계열 응답에는
 * `_cached` · `sourceCounts` · `sourceErrors` 가 **없다** — 부분 실패라는 개념 자체가 없다.
 *
 * 계획 · 사전규격 · 입찰 · 마감이 한 목록에 섞여 온다. 단계(`category`)는 탭이 아니라 필터다.
 *
 * 주의 두 가지가 타입에 그대로 반영돼 있다.
 *  1. 백엔드 Jackson 이 `default-property-inclusion: non_null` 이라 **값이 없는 필드는 아예
 *     오지 않는다.** `null` 이 아니라 `undefined` 다 — 그래서 전부 선택 필드다.
 *  2. 적재 현황(`/status`)만 DB 컬럼명(snake_case)이 그대로 나온다. 검색·상세는 camelCase 로
 *     성형돼 오지만 그 경로는 `queryForList` 결과를 그대로 싣기 때문이다.
 *
 * 수동 적재(`POST /api/search/notices/sync`)는 앱 키가 필요한 운영 경로라 여기 넣지 않았다.
 * 프론트에는 아직 인증 헤더 배선이 없다(`lib/apiClient.ts`).
 */
import { useQuery } from '@tanstack/react-query';
import { get } from '@/lib/apiClient';
import type { PageEnvelope } from './types';
import { toSearchParams, type QueryValue } from './types';

/* ─── 값 집합 ─────────────────────────────────────────────────────────────── */

/** 조달 생애주기 단계. DB ENUM 이 한글 그대로다 — 매핑 표가 필요 없다. */
export const NOTICE_CATEGORIES = ['계획', '사전규격', '입찰', '마감'] as const;
export type NoticeCategory = (typeof NOTICE_CATEGORIES)[number];

/**
 * 공고 상태. **넷 모두 예외 상태**이고, 정상적으로 처음 올라온 공고는 값이 없다.
 * 실측 분포도 압도적 다수가 빈 값이다 — '정상'을 뜻하는 다섯 번째 값은 없다.
 */
export const NOTICE_STATES = ['정정', '재', '다시', '취소'] as const;
export type NoticeState = (typeof NOTICE_STATES)[number];

/** 업종코드. 기존 4탭의 `bidType` 에 없던 `외자` 가 여기에는 있다. */
export const BUSINESS_DIVISIONS = ['물품', '용역', '공사', '외자'] as const;
export type BusinessDivision = (typeof BUSINESS_DIVISIONS)[number];

/**
 * 정렬 키 화이트리스트. 밖의 값은 백엔드가 기본값으로 떨어뜨린다.
 *
 * **기본값이 조건에 따라 다르다** — 검색어가 있으면 `relevance`, 없으면 `created`.
 * 그래서 사용자가 고르지 않았으면 `sort` 를 **아예 보내지 않는 것**이 맞다.
 */
export const NOTICE_SORT_KEYS = [
  'relevance',
  'created',
  'close',
  'name',
  'amount',
  'updated',
] as const;
export type NoticeSortKey = (typeof NOTICE_SORT_KEYS)[number];

export function isNoticeSortKey(value: string): value is NoticeSortKey {
  return (NOTICE_SORT_KEYS as readonly string[]).includes(value);
}

/* ─── 요청 ────────────────────────────────────────────────────────────────── */

export interface NoticeIndexQuery {
  /** 검색창 한 줄. 공백으로 나눈 낱말을 모두 포함(AND). `andTerms` 와 함께 오면 합쳐서 걸린다. */
  q?: string;
  /** 3단 태그 입력. 공백·콤마 구분. */
  andTerms?: string;
  orTerms?: string;
  notTerms?: string;
  category?: NoticeCategory;
  state?: NoticeState;
  division?: BusinessDivision;
  /** 포함 검색. 지역 제한 없는 공고(전국)가 **함께** 나온다 — 계약 §4.3. */
  region?: string;
  /** 기관명. 공고기관·수요기관 둘 다에서 찾는다. */
  insttNm?: string;
  insttCd?: string;
  dmndInsttCd?: string;
  /** 세부품명번호 **접두** 일치 — `4110` 으로 상위 분류를 훑을 수 있다. */
  detailProductCode?: string;
  /** 사전규격 → 입찰공고 교차 이동. */
  beforeSpecRgstNo?: string;
  officerName?: string;
  /** 공고일 구간. `YYYY-MM-DD` — 기존 4종의 `YYYYMMDD` 와 형식이 다르다. */
  fromDate?: string;
  toDate?: string;
  /** 마감일 구간. */
  closeFrom?: string;
  closeTo?: string;
  /** `'true'` 면 마감 전만. 문자열이다 — 불리언 false 는 파라미터를 붙이지 않는 것으로 표현한다. */
  activeOnly?: 'true';
  /** 추정가격 구간(원). */
  minAmount?: number;
  maxAmount?: number;
  sort?: NoticeSortKey;
  dir?: 'asc' | 'desc';
  page?: number;
  /** 1..500 으로 클램프된다. 기존 4종의 `'all'` 같은 문자열은 받지 않는다. */
  perPage?: number;
}

/** 한 페이지 상한. 백엔드 `BidNoticeIndexRepository.MAX_LIMIT` 와 같은 값. */
export const NOTICE_MAX_PER_PAGE = 500;

/* ─── 응답 ────────────────────────────────────────────────────────────────── */

/** 물품목록 한 줄. 원본은 캐럿(`^`) 문자열이고 백엔드가 JSON 으로 펴서 준다. */
export interface NoticeProduct {
  seq?: number;
  code?: string;
  name?: string;
}

/** 세부 가격 표. 값이 없는 칸은 아예 오지 않는다. */
export interface NoticePriceDetail {
  assignedBudget?: number;
  estimatedPrice?: number;
  unitPrice?: number;
  quantity?: number;
  unit?: string;
  vat?: number;
}

/** 첨부파일. **문자열이 아니라 값**으로 온다 — 화면이 JSON.parse 를 부를 일이 없다. */
export interface NoticeAttachment {
  name?: string;
  url?: string;
}

/**
 * 검색 결과 한 건. ERD 22개 + 표시용 `noticeName` + 서버 계산분(`dday`·`estimatedPrice`).
 *
 * 화면이 반드시 지켜야 하는 규칙 셋:
 *  - `region` 의 **빈 문자열은 '전국'** 이다(지역 제한 없음). 지역으로 좁혀도 전국 공고가 함께
 *    오므로, '전국'이라고 적지 않으면 사용자가 필터 오작동으로 오해한다.
 *  - **공고기관과 수요기관이 다른 건이 흔하다**(조달청 대행). 다를 때는 둘 다 보여준다.
 *    사전규격은 기관 **코드가 없고 이름만** 있으므로 표시 기준은 언제나 이름이다.
 *  - `lowestBidRate` 는 **백분율 그대로**다(88.000 = 88%). 100을 곱하지 말 것.
 */
export interface NoticeIndexItem {
  /** 공고번호. 계획은 조달요청번호, 사전규격은 사전규격등록번호다. */
  id: string;
  noticeOrder?: string | null;
  noticeName?: string | null;
  category?: NoticeCategory | null;
  state?: NoticeState | null;
  businessDivision?: BusinessDivision | null;
  region?: string | null;

  demandInstitutionCode?: string | null;
  demandInstitutionName?: string | null;
  noticeInstitutionCode?: string | null;
  noticeInstitutionName?: string | null;

  beforeSpecRgstNo?: string | null;
  productList?: NoticeProduct[] | null;
  detailProductCode?: string | null;
  lowestBidRate?: number | null;
  priceDetail?: NoticePriceDetail | null;

  /** ISO-8601 (`2026-08-06T09:12:00`). 백엔드가 타임스탬프 직렬화를 꺼 두었다. */
  createdDate?: string | null;
  closeDate?: string | null;
  /** **색인에 반영된 때**이지 원본 변경일시가 아니다. */
  updatedAt?: string | null;

  officerName?: string | null;
  officerContact?: string | null;

  /** 적재기가 절대 덮어쓰지 않는 칸. */
  aiSummary?: string | null;
  attachmentUrls?: NoticeAttachment[] | null;
  sourceUrl?: string | null;

  /** 목록은 300자 미리보기. 상세는 `noticeBody` 전문이 함께 온다. */
  bodyPreview?: string | null;
  noticeBody?: string | null;
  /** 전문검색일 때만. */
  relevance?: number;

  /** 남은 **일수**(시각이 아니라). 마감이 없는 계획 단계는 오지 않는다. */
  dday?: number | null;
  /** `priceDetail.estimatedPrice` 를 꺼내 둔 것(정렬·표시용). */
  estimatedPrice?: number | null;
}

export type NoticeSearchResponse = PageEnvelope<NoticeIndexItem>;

export interface NoticeFacetBucket {
  value: string;
  count: number;
}

/** 같은 조건에서 각 분류가 몇 건인지. 필터 칩에 건수를 붙이는 데 쓴다. */
export interface NoticeFacets {
  category?: NoticeFacetBucket[];
  division?: NoticeFacetBucket[];
  region?: NoticeFacetBucket[];
  state?: NoticeFacetBucket[];
}

/** 출처별 적재 현황. **DB 컬럼명 그대로** 온다. */
export interface NoticeSyncState {
  source: string;
  watermark?: string | null;
  last_run_at?: string | null;
  last_success_at?: string | null;
  /** 실패는 사유가 그대로 적힌다 — 가짜 `ok` 를 남기지 않는 것이 백엔드의 규칙이다. */
  last_result?: string | null;
  last_row_count?: number | null;
  consecutive_failures?: number | null;
}

export interface NoticeIndexSummaryRow {
  category?: NoticeCategory | null;
  count?: number;
  last_indexed_at?: string | null;
}

export interface NoticeIndexStatus {
  sources?: NoticeSyncState[];
  summary?: NoticeIndexSummaryRow[];
  knownSources?: string[];
}

/* ─── 함수 ────────────────────────────────────────────────────────────────── */

function qs(query: NoticeIndexQuery): string {
  return toSearchParams(query as Record<string, QueryValue>).toString();
}

export function fetchNoticeSearch(query: NoticeIndexQuery): Promise<NoticeSearchResponse> {
  return get<NoticeSearchResponse>(`/api/search/notices?${qs(query)}`);
}

export function fetchNoticeFacets(query: NoticeIndexQuery): Promise<NoticeFacets> {
  return get<NoticeFacets>(`/api/search/notices/facets?${qs(query)}`);
}

export function fetchNoticeIndexStatus(): Promise<NoticeIndexStatus> {
  return get<NoticeIndexStatus>('/api/search/notices/status');
}

/** 상세 한 건. 색인에 없으면 404 + `{ error: '색인에 없는 공고입니다: …' }`. */
export function fetchNoticeDetail(id: string): Promise<NoticeIndexItem> {
  return get<NoticeIndexItem>(`/api/search/notices/${encodeURIComponent(id)}`);
}

/* ─── 쿼리 훅 ─────────────────────────────────────────────────────────────── */

export const noticeIndexKeys = {
  all: ['notice-index'] as const,
  search: (q: NoticeIndexQuery) => ['notice-index', 'search', q] as const,
  facets: (q: NoticeIndexQuery) => ['notice-index', 'facets', q] as const,
  status: () => ['notice-index', 'status'] as const,
  detail: (id: string) => ['notice-index', 'detail', id] as const,
};

export function useNoticeIndexSearch(query: NoticeIndexQuery, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: noticeIndexKeys.search(query),
    queryFn: () => fetchNoticeSearch(query),
    enabled: options.enabled ?? true,
  });
}

/**
 * 패싯은 목록과 **같은 조건**으로 부른다(페이지·정렬만 빼고). 조건이 갈라지면 화면이
 * "12건"이라 써 놓고 3건을 보여주게 된다 — 백엔드가 한 곳에서 WHERE 를 만드는 것과 같은 이유다.
 */
export function useNoticeIndexFacets(query: NoticeIndexQuery, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: noticeIndexKeys.facets(query),
    queryFn: () => fetchNoticeFacets(query),
    enabled: options.enabled ?? true,
  });
}

/** 색인이 언제 것인지 화면에 적기 위한 것. 자주 바뀌지 않으므로 5분은 묵혀 둔다. */
export function useNoticeIndexStatus() {
  return useQuery({
    queryKey: noticeIndexKeys.status(),
    queryFn: fetchNoticeIndexStatus,
    staleTime: 5 * 60_000,
    retry: false,
  });
}

export function useNoticeDetail(id: string | null) {
  return useQuery({
    queryKey: noticeIndexKeys.detail(id ?? ''),
    queryFn: () => fetchNoticeDetail(id as string),
    enabled: Boolean(id),
    staleTime: 5 * 60_000,
  });
}
