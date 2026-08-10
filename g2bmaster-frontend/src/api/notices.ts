/*
 * 공고 검색 4종(발주계획 · 사전규격 · 입찰공고 · 입찰결과) + 첨부 스캔 + 개찰 조회.
 *
 * G2B 원본 행은 필드가 수십 개고 오퍼레이션마다 다르다. 컬럼 정의(domain/columns.ts)가
 * 쓰는 키만 명시하고 나머지는 인덱스 시그니처로 통과시킨다 — 전부 적어 두면 서버가 필드
 * 하나 추가할 때마다 타입이 거짓말을 하게 된다.
 */
import { useQuery, useMutation } from '@tanstack/react-query';
import { get, post } from '@/lib/apiClient';
import type {
  AnalysisQueue,
  CachedFlag,
  FileEntry,
  ItemAnalysisState,
  PageEnvelope,
} from './types';
import { toSearchParams, type QueryValue } from './types';

/* ─── 행 ─────────────────────────────────────────────────────────────────── */

/** 검색 파이프라인이 모든 행에 덧붙이는 파생 필드. 이름이 `_` 로 시작하는 것이 그 표식. */
export interface DecoratedRow {
  _type?: string;
  _source?: 'g2b' | 'd2b' | 'private-g2b' | string;
  _sourceLabel?: string;
  _noticeStatus?: string;
  _isCancelled?: boolean;
  _opportunityScore?: number;
  _opportunityGrade?: string;
  _opportunityAction?: string;
  _opportunitySummary?: string;
  _opportunityReasons?: string[];
  _specLockSummary?: string;
  _contractSummary?: string;
  _requirementSummary?: string;
  _simScore?: number | null;
  _analysis?: ItemAnalysisState;
  fileEntries?: FileEntry[];
  [key: string]: unknown;
}

export interface BidPlanItem extends DecoratedRow {
  bsnsDivNm?: string;
  bizNm?: string;
  orderInsttNm?: string;
  prdctClsfcNoNm?: string;
  prdctClsfcNo?: string;
  sumOrderAmt?: string | number;
  cntrctMthdNm?: string;
  nticeDt?: string;
  prcrmntReqInfoUrl?: string;
  prcrmntReqNo?: string;
  orderPlanUntyNo?: string;
}

export interface PreSpecItem extends DecoratedRow {
  bsnsDivNm?: string;
  prdctClfcNo?: string;
  prdctClsfcNoNm?: string;
  prdctClsfcNo?: string;
  ntceInsttNm?: string;
  asignBdgtAmt?: string | number;
  pstDt?: string;
  opninRgstClseDt?: string;
  lnkUrl?: string;
  bidNtceNoList?: string | string[];
  bfSpecRgstNo?: string;
}

export interface BidAnnounceItem extends DecoratedRow {
  bidNtceNo?: string;
  bidNtceSqNo?: string;
  bidNtceOrd?: string;
  bidNtceNm?: string;
  ntceInsttNm?: string;
  dminsttNm?: string;
  presmptPrce?: string | number;
  bidNtceDt?: string;
  bidClseDt?: string;
  ntceInsttOfclNm?: string;
  ntceInsttOfclTelNo?: string;
  ntceInsttOfclEmailAdrs?: string;
  cntrctCnclsMthdNm?: string;
  dtilPrdctClsfcNoNm?: string;
}

export interface BidResultItem extends DecoratedRow {
  bidNtceNo?: string;
  bidNtceNm?: string;
  dminsttNm?: string;
  bidwinnrNm?: string;
  sucsfbidAmt?: string | number;
  sucsfbidRate?: string | number;
  prtcptCnum?: string | number;
  rlOpengDt?: string;
  fnlSucsfDate?: string;
}

/* ─── 요청 ────────────────────────────────────────────────────────────────── */

/**
 * 검색 질의 파라미터. 원본 buildParams(app.js:1205)가 만들던 것과 같은 집합이다.
 * pageNo = 0 은 "페이징 없이 전부" — 첨부 전수조사가 그 형태로 부른다.
 */
export interface NoticeSearchQuery {
  andTerms?: string;
  orTerms?: string;
  notTerms?: string;
  pageNo?: number;
  perPage?: number | 'all';
  fromDate?: string;
  toDate?: string;
  activeOnly?: 'true';
  bidNtceNo?: string;
  insttNm?: string;
  corpNm?: string;
  bidType?: string;
  sortKey?: string;
  sortDir?: string;
  fileScan?: 'true';
  searchField?: 'item';
  simOr?: 'true';
  simFile?: 'true';
}

/* ─── 응답 ────────────────────────────────────────────────────────────────── */

export interface BidPlanResponse extends PageEnvelope<BidPlanItem>, CachedFlag {
  _analysisQueue?: AnalysisQueue;
}

export interface PreSpecResponse extends PageEnvelope<PreSpecItem>, CachedFlag {
  _analysisQueue?: AnalysisQueue;
}

/** 나라장터 외 소스(D2B · 민간 나라장터)를 합치므로 소스별 집계가 함께 온다. */
export interface SourceStatus {
  g2b: 'ok';
  d2b: 'ok' | 'partial';
  'private-g2b': 'ok' | 'partial';
}

export interface BidAnnounceResponse extends PageEnvelope<BidAnnounceItem>, CachedFlag {
  _analysisQueue?: AnalysisQueue;
  /**
   * 아래 4개는 공고번호 직접조회(bidNtceNo 파라미터)로 들어가면 서버가 아예 안 붙인다.
   * 그래서 전부 선택 필드다 — 있다고 가정하고 읽으면 그 경로에서 터진다.
   */
  sourceCounts?: Record<string, number>;
  sourceCoverage?: string[];
  sourceErrors?: Array<{ source: string; [key: string]: unknown }>;
  sourceStatus?: SourceStatus;
}

export type BidResultResponse = PageEnvelope<BidResultItem> & CachedFlag;

/* ─── 함수 ────────────────────────────────────────────────────────────────── */

function qs(query: NoticeSearchQuery): string {
  return toSearchParams(query as Record<string, QueryValue>).toString();
}

export function fetchBidPlan(query: NoticeSearchQuery): Promise<BidPlanResponse> {
  return get<BidPlanResponse>(`/api/bid-plan?${qs(query)}`);
}

export function fetchPreSpec(query: NoticeSearchQuery): Promise<PreSpecResponse> {
  return get<PreSpecResponse>(`/api/pre-spec?${qs(query)}`);
}

export function fetchBidAnnounce(query: NoticeSearchQuery): Promise<BidAnnounceResponse> {
  return get<BidAnnounceResponse>(`/api/bid-announce?${qs(query)}`);
}

export function fetchBidResult(query: NoticeSearchQuery): Promise<BidResultResponse> {
  return get<BidResultResponse>(`/api/bid-result?${qs(query)}`);
}

/** 첨부 다운로드는 앵커 href 로 쓰인다 — 서버가 스트림으로 내려주므로 XHR 로 받지 않는다. */
export function downloadAttachmentUrl(url: string, name: string): string {
  return `/api/download-attachment?${toSearchParams({ url, name }).toString()}`;
}

/* ─── 첨부 전수조사 ───────────────────────────────────────────────────────── */

export interface AttachmentScanRequest {
  scans: Array<{
    id: string;
    fileEntries: FileEntry[];
    bidNtceNo?: string;
    bidNtceSqNo?: string;
    _type?: string;
    _tab?: string;
    _version?: string;
  }>;
  fileKeywords?: string[];
  excludeBlockingClauses?: boolean;
  scanBlocking?: boolean;
  candidateCount?: number;
  immediateLimit?: number;
  warmLimit?: number;
}

export interface AttachmentScanResponse {
  matches: Array<{
    id: string;
    matchedKeywords: string[];
    excerpt: string;
    documentTags: string[];
  }>;
  exclusions: Array<{ id: string; reasons: string[]; excerpt: string; documentTags: string[] }>;
  scanned: number;
  /** 아래는 조기 반환 경로(스캔할 것이 없을 때)에서는 아예 안 온다. */
  candidateCount?: number;
  cacheHits?: number;
  warmQueueDepth?: number;
  warmActive?: number;
  /**
   * 서버가 async 함수를 await 없이 넣어 Promise 가 `{}` 로 직렬화된다(server.js:4484).
   * 이름과 달리 숫자가 아니므로 읽지 말 것 — 서버가 고쳐지면 number 로 좁힌다.
   */
  warmQueued?: unknown;
}

export function scanAttachments(body: AttachmentScanRequest): Promise<AttachmentScanResponse> {
  return post<AttachmentScanResponse>('/api/scan-attachments', body);
}

/* ─── 개찰 결과 ───────────────────────────────────────────────────────────── */

export interface BidOpeningParticipant {
  bdrNm?: string;
  bidAmt?: string | number;
  bidprcRt?: string | number;
  rank?: string | number;
  sucsfbidYn?: string;
  [key: string]: unknown;
}

export interface BidOpeningResultsResponse {
  bidNtceNo: string;
  bidNtceSqNo: string;
  participants: BidOpeningParticipant[];
}

export function fetchBidOpeningResults(body: {
  bidNtceNo: string;
  bidNtceSqNo?: string;
  type?: string;
}): Promise<BidOpeningResultsResponse> {
  return post<BidOpeningResultsResponse>('/api/bid-opening-results', body);
}

/* ─── 쿼리 훅 ─────────────────────────────────────────────────────────────── */

export const noticeKeys = {
  all: ['notices'] as const,
  bidPlan: (q: NoticeSearchQuery) => ['notices', 'bid-plan', q] as const,
  preSpec: (q: NoticeSearchQuery) => ['notices', 'pre-spec', q] as const,
  bidAnnounce: (q: NoticeSearchQuery) => ['notices', 'bid-announce', q] as const,
  bidResult: (q: NoticeSearchQuery) => ['notices', 'bid-result', q] as const,
  opening: (no: string, sq?: string) => ['notices', 'opening', no, sq ?? ''] as const,
};

/** 검색은 사용자가 '검색'을 눌렀을 때만 나가야 하므로 enabled 로 제어할 수 있게 열어 둔다. */
type SearchHookOptions = { enabled?: boolean };

export function useBidPlan(query: NoticeSearchQuery, options: SearchHookOptions = {}) {
  return useQuery({
    queryKey: noticeKeys.bidPlan(query),
    queryFn: () => fetchBidPlan(query),
    enabled: options.enabled ?? true,
  });
}

export function usePreSpec(query: NoticeSearchQuery, options: SearchHookOptions = {}) {
  return useQuery({
    queryKey: noticeKeys.preSpec(query),
    queryFn: () => fetchPreSpec(query),
    enabled: options.enabled ?? true,
  });
}

export function useBidAnnounce(query: NoticeSearchQuery, options: SearchHookOptions = {}) {
  return useQuery({
    queryKey: noticeKeys.bidAnnounce(query),
    queryFn: () => fetchBidAnnounce(query),
    enabled: options.enabled ?? true,
  });
}

export function useBidResult(query: NoticeSearchQuery, options: SearchHookOptions = {}) {
  return useQuery({
    queryKey: noticeKeys.bidResult(query),
    queryFn: () => fetchBidResult(query),
    enabled: options.enabled ?? true,
  });
}

/** 개찰 현황은 서랍에서 버튼을 눌렀을 때만 부른다 — 그래서 쿼리가 아니라 뮤테이션이다. */
export function useBidOpeningResults() {
  return useMutation({ mutationFn: fetchBidOpeningResults });
}

export function useScanAttachments() {
  return useMutation({ mutationFn: scanAttachments });
}
