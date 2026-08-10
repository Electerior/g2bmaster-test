/*
 * AI · 분석 계열 엔드포인트 — 딜 분석, 공고/품목 요약, 낙찰자 이력, 담당자 조회,
 * 담합 매트릭스, 유사 완본체 비교, 파일 파싱.
 *
 * **가장 중요한 계약:** 이 중 요약 계열은 LLM 이 실패해도 HTTP 200 을 준다.
 * 본문에 aiFallback / aiTimeout / aiDisabled 플래그가 실려 오고 summary 는 메타데이터로
 * 채워진다. axios 인터셉터는 이것을 오류로 보지 않으므로, 화면이 플래그를 직접 읽어야 한다.
 */
import { useMutation } from '@tanstack/react-query';
import { post } from '@/lib/apiClient';
import type {
  AiFallbackFlags,
  DocumentSignals,
  FileEntry,
  FileSummary,
  LegalAssessment,
  SourceTrace,
} from './types';

/* ─── 딜 분석 ─────────────────────────────────────────────────────────────── */

export interface DealAnalysisRequest {
  item: Record<string, unknown>;
  type?: string;
  bidPrice?: number;
  unitCost?: number;
  quantity?: number;
  /** 깊은 분석 — 규격서 첨부까지 열어 부품 단가를 추정한다. **기본 켜짐**(백엔드도 기본 true). */
  deep?: boolean;
  /** 갈래별 on/off. 안 주면 각 true. spec·parts 는 deep 이 켜져 있어야 돈다. */
  include?: { spec?: boolean; parts?: boolean; market?: boolean; opening?: boolean };
  /** 저장된 deep 결과를 무시하고 다시 분석한다. */
  forceRefresh?: boolean;
}

/** 단가 추정 결과는 "못 찾음"과 "찾음"이 형태가 달라 union 으로 온다. */
export type EstimatedUnitCost =
  | { matched: false; reason: string; gpuCount?: number }
  | {
      matched: true;
      low: number;
      high: number;
      mid: number;
      gpuCount: number;
      /** 베어본(완본체 베이스) 행을 포함하는가. 부품과 함께 나열된다. */
      hasBase: boolean;
      /** 모든 행의 가격이 확인됐는가. false 면 일부 행이 미확인(low=null). */
      allPriced?: boolean;
      breakdown: Array<{
        category: string;
        option: string;
        product: string | null;
        qty: number;
        low: number | null;
        high: number | null;
        inferred?: boolean;
        /** 'base'(베어본/완본체 베이스) | 'part'(부품). */
        role?: 'base' | 'part';
        /** 'itmaya'(가격표 색인) | 'danawa'(웹검색). */
        source?: string;
      }>;
      currency: 'KRW';
      /** 'itmaya' | 'danawa' | 'hybrid'(베어본은 색인, 부품은 웹). */
      priceSource?: string;
      /** 부품 합보다 완제품이 나은지 — 완제품이면 유사 완제품 후보를 함께 준다. */
      prebuilt?: {
        isPrebuilt: boolean;
        score?: number;
        reason?: string;
        comparables?: Array<{ name: string; priceKrw: number; url: string; source?: string }>;
      };
    };

export interface DealAnalysisResponse {
  bidNtceNo: string;
  score: number | null;
  facts: {
    productName: string;
    productCode: string;
    quantity: number;
    unitPrice: number;
    budget: number;
  };
  /** include.market=false 면 null 로 온다(백엔드 non_null 직렬화). */
  market: {
    sampleCount: number;
    rateSampleCount: number;
    medianRate: number;
    minRate: number;
    maxRate: number;
    medianAmountRaw: number;
    latestDate: string;
    budget: number;
    expectedAward: number;
    expectedSaving: number;
    expectedSavingPct: number;
    usedBaseline: boolean;
    matchCount: number;
  } | null;
  opening: {
    participantCount: number;
    participants: Array<{
      name: string;
      amount: number;
      rate: number;
      rank: number;
      won: boolean;
    }>;
    /** 참여업체가 있을 때만 붙는다. */
    winningAmount?: number;
    minAmount?: number;
    maxAmount?: number;
    medianAmount?: number;
  };
  deal: {
    budget: number;
    expectedAward: number;
    expectedRate: number;
    unitCost: number;
    quantity: number;
    cost: number;
    hasCost: boolean;
    profitAtBudget: number;
    profitAtExpected: number;
    profitAtBid: number;
    marginPctAtExpected: number;
    breakevenBid: number;
    bidRate: number;
    unitCostSource: 'user' | 'estimated' | null;
  };
  simBidUsed: number | null;
  spec: null | {
    text: string;
    products: unknown[];
    parsedFiles: Array<{ name: string; textLength: number }>;
    /** confidence: confirmed|heuristic|estimated — 규격서 선택의 확신도. */
    files: Array<{ url: string; name: string; confidence?: string }>;
    fileEntryCount: number;
    quantityFound: unknown;
    deliveryFound: string | null;
  };
  estimatedUnitCost: EstimatedUnitCost | null;
  /** 국방전자조달(D2B) 전용 상세. 나라장터 공고에는 null. */
  d2bGw: null | {
    detail: {
      areaLimit: string;
      estimatedPrice: number;
      budget: number;
      lowerBoundRate: number;
      contractMethod: string;
      charger: string;
      chargerTel: string;
      opengDt: string;
      bidPlace: string;
    } | null;
    items: Array<{
      name: string;
      qty: number;
      unit: string;
      unitPrice: number;
      amount: number;
      deliveryDate: string;
      spec: string;
      niin: string;
      fsc: string;
    }>;
    total: number;
  };
  /** 단가를 못 구했을 때만 붙는 안내 문구. */
  note?: string;
  /** 저장된 deep 결과를 재사용했는가. */
  _fromCache?: boolean;
  /** 저장된 결과의 분석 시각(ISO). `_fromCache` 일 때만. */
  _analyzedAt?: string;
}

export function analyzeDeal(body: DealAnalysisRequest): Promise<DealAnalysisResponse> {
  return post<DealAnalysisResponse>('/api/deal-analysis', body);
}

/* ─── 공고 AI 요약 ────────────────────────────────────────────────────────── */

export interface BidSummaryRequest {
  bidNtceNo?: string;
  bidNtceSqNo?: string;
  bidNtceNm?: string;
  ntceInsttNm?: string;
  presmptPrce?: string | number;
  cntrctCnclsMthdNm?: string;
  dtilPrdctClsfcNoNm?: string;
  _type?: string;
  /** 사용자가 직접 붙여 넣은 본문 — 있으면 첨부 파싱을 건너뛴다. */
  manualContent?: string;
  manualFileName?: string;
  companyProfile?: string;
  fileEntries?: FileEntry[];
  rawFields?: Record<string, unknown>;
}

/** source 는 본문을 어디에서 얻었는지. 'meta' = 첨부 없이 메타데이터만으로 만든 요약. */
export type SummarySource = 'manual' | 'auto-file' | 'auto-detail' | 'meta';

export interface BidSummaryResponse extends AiFallbackFlags, DocumentSignals {
  summary: string;
  source: SummarySource;
  /** noPdf === (source === 'meta') 로 서버가 계산해 준다. */
  noPdf: boolean;
  sourceTrace?: SourceTrace;
  fileEntryCount: number;
  parsedFileCount: number;
  parsedFiles: unknown[];
  failedFiles: unknown[];
  fileRecords?: unknown[];
  fileSummary?: FileSummary;
}

export function summarizeBid(body: BidSummaryRequest): Promise<BidSummaryResponse> {
  return post<BidSummaryResponse>('/api/bid-summary', body);
}

/* ─── 품목 요약 (수주 데스크 · 내보내기가 쓰는 깊은 분석) ───────────────────── */

export interface ItemSummaryRequest {
  bidNtceNo?: string;
  bidNtceSqNo?: string;
  bfSpecRgstNo?: string;
  prcrmntReqNo?: string;
  entityType?: string;
  title?: string;
  insttNm?: string;
  itemName?: string;
  amount?: string | number;
  cntrctMthdNm?: string;
  type?: string;
  companyProfile?: string;
  fileEntries?: FileEntry[];
  rawFields?: Record<string, unknown>;
  analysisMode?: string;
  deep?: boolean;
  /** 'deal-radar' 를 주면 프롬프트가 바뀌고 품목 분해 필드가 추가된다. */
  context?: string;
  specText?: string;
  preferFile?: boolean;
  /** 저장된 분석 이력을 무시하고 다시 돌린다. */
  forceRefresh?: boolean;
}

export interface ProductLine {
  name: string;
  count: number;
  countLabel: string;
  unitLow: number;
  unitHigh: number;
  unitMid: number;
  lineMid: number;
  matchedOption: string;
}

export interface ItemSummaryResponse extends AiFallbackFlags, DocumentSignals {
  summary: string;
  factIntegrity?: {
    verifiedFacts: unknown[];
    rejectedCount: number;
    uncertainties: string[];
  };
  productCounts?: Record<string, unknown> | null;
  productGroups?: unknown[] | null;
  productLines?: ProductLine[] | null;
  productTotal?: number | null;
  source: string;
  fileEntryCount: number;
  parsedFileCount: number;
  fileRecords?: unknown[];
  fileSummary?: FileSummary;
  parsedFiles: unknown[];
  failedFiles: unknown[];
  relatedItems: unknown[];
  noFile: boolean;
  sourceTrace?: SourceTrace;
  fastMode: boolean;
  analysisMeta?: {
    entityType: string;
    entityId: string;
    entityOrd: string;
    deepMode: boolean;
    analysisMode: string;
    model: string;
    inputHash: string;
    promptVersion: string;
  };
  _analysisHistoryId?: number;
  /** 캐시(분석 이력) 히트일 때만 붙는다 — 새로 돌린 것이 아니라는 표시. */
  _fromHistory?: boolean;
  _analyzedAt?: string;
}

export function summarizeItem(body: ItemSummaryRequest): Promise<ItemSummaryResponse> {
  return post<ItemSummaryResponse>('/api/item-summary', body);
}

/* ─── 낙찰자 이력 ─────────────────────────────────────────────────────────── */

export interface CompanyHistoryRequest {
  corpNm?: string;
  brnNo?: string;
  fromDate?: string;
  toDate?: string;
}

export interface CompanyParticipation {
  bidNtceNo: string;
  bidNtceNm: string;
  ntceInsttNm: string;
  dminsttNm: string;
  opengDt: string;
  presmptPrce: string | number;
  _type: string;
  bdrNm: string;
  rank: string | number;
  bidAmt: string | number;
  bidprcRt: string | number;
  sucsfbidYn: string;
  _won: boolean;
  totalParticipants?: number;
}

export interface CompanyHistoryResponse {
  corpNm: string;
  from: string;
  to: string;
  wins: Array<Record<string, unknown>>;
  participations: CompanyParticipation[];
  stats: {
    winCount: number;
    participationCount: number;
    winRate: number | null;
    /** 서버가 .toFixed(3) 을 거쳐 문자열로 준다 — 숫자로 쓰려면 Number() 를 거칠 것. */
    avgBidRate: string | null;
    bidRateSeries: Array<{
      date: string;
      rate: number;
      won: boolean;
      name: string;
      rank: string;
    }>;
  };
}

export function fetchCompanyHistory(body: CompanyHistoryRequest): Promise<CompanyHistoryResponse> {
  return post<CompanyHistoryResponse>('/api/company-history', body);
}

/* ─── 담당자 조회 ─────────────────────────────────────────────────────────── */

export interface OfficerSearchRequest {
  insttNm: string;
  fromDate?: string;
  toDate?: string;
}

export interface OfficerRecord {
  name: string;
  tel: string;
  email: string;
  insttNm: string;
  dminsttNm: string;
  bids: Array<{
    bidNtceNo: string;
    bidNtceNm: string;
    bidNtceDt: string;
    bidClseDt: string;
    asignBdgtAmt: string | number;
    dminsttNm: string;
    type: string;
  }>;
}

export interface OfficerSearchResponse {
  insttNm: string;
  from: string;
  to: string;
  totalBids: number;
  officers: OfficerRecord[];
}

export function searchOfficers(body: OfficerSearchRequest): Promise<OfficerSearchResponse> {
  return post<OfficerSearchResponse>('/api/officer-search', body);
}

/* ─── 담합(들러리) 매트릭스 ───────────────────────────────────────────────── */

export interface CollusionAnalysisRequest {
  /** 서버는 최대 20건까지만 처리한다. */
  bids: Array<{ bidNtceNo: string; bidNtceSqNo?: string; _type?: string }>;
}

export interface CollusionAnalysisResponse {
  bids: Array<Record<string, unknown> & { participants: unknown[] }>;
  pairs: Array<{
    a: string;
    b: string;
    total: number;
    aWins: number;
    bWins: number;
    cases: Array<{
      bidNtceNo: string;
      bidNtceNm: string;
      winner: string;
      runnerUp: string;
      winBidprcRt: number;
      runBidprcRt: number;
      opengDate: string;
    }>;
    alterScore: number;
    suspicionScore: number;
  }>;
  companies: Array<{
    name: string;
    wins: number;
    runnerUp: number;
    appearances: number;
    avgRate: number | null;
    rateHistory: number[];
    isMonotonicallyIncreasing: boolean;
    cases: unknown[];
  }>;
}

export function analyzeCollusion(
  body: CollusionAnalysisRequest,
): Promise<CollusionAnalysisResponse> {
  return post<CollusionAnalysisResponse>('/api/collusion-analysis', body);
}

/* ─── 유사 완본체 비교 ────────────────────────────────────────────────────── */

export interface PrebuiltComparablesRequest {
  name: string;
  qty?: number;
  /** 서버는 최대 60개까지만 본다. */
  components: Array<{ name: string; perUnit?: number; unitPrice?: number }>;
}

export interface PrebuiltComparablesResponse {
  eligible: boolean;
  reason: string;
  isPrebuilt: boolean;
  score: number;
  signals: unknown[];
  /** 최대 5건. */
  comparables: unknown[];
  requiredSpec: Record<string, unknown> | null;
  query: string;
  queryBasis: string;
  misses: unknown[];
  searchStatus: string;
}

export function fetchPrebuiltComparables(
  body: PrebuiltComparablesRequest,
): Promise<PrebuiltComparablesResponse> {
  return post<PrebuiltComparablesResponse>('/api/prebuilt-comparables', body);
}

/* ─── 파일 파싱 (multipart) ───────────────────────────────────────────────── */

export interface ParseFileResponse {
  /** markdown 본문. */
  text: string;
  filename: string;
  estimatedUnitCost: EstimatedUnitCost | null;
  documentTags: string[];
  bidBlockingClauses: {
    excluded: boolean;
    reasons: string[];
    matches: Array<{ reason: string; excerpt: string }>;
  };
  legalAssessment: LegalAssessment | null;
}

/**
 * multipart 는 Content-Type 을 axios 가 boundary 와 함께 직접 정해야 하므로
 * apiClient 기본 헤더(application/json)를 undefined 로 덮어 지운다.
 */
export function parseFile(file: File): Promise<ParseFileResponse> {
  const form = new FormData();
  form.append('file', file);
  return post<ParseFileResponse>('/api/parse-file', form, {
    headers: { 'Content-Type': undefined },
  });
}

/* ─── 훅 ──────────────────────────────────────────────────────────────────── */

export function useDealAnalysis() {
  return useMutation({ mutationFn: analyzeDeal });
}

export function useBidSummary() {
  return useMutation({ mutationFn: summarizeBid });
}

export function useItemSummary() {
  return useMutation({ mutationFn: summarizeItem });
}

export function useCompanyHistory() {
  return useMutation({ mutationFn: fetchCompanyHistory });
}

export function useOfficerSearch() {
  return useMutation({ mutationFn: searchOfficers });
}

export function useCollusionAnalysis() {
  return useMutation({ mutationFn: analyzeCollusion });
}

export function usePrebuiltComparables() {
  return useMutation({ mutationFn: fetchPrebuiltComparables });
}

export function useParseFile() {
  return useMutation({ mutationFn: parseFile });
}
